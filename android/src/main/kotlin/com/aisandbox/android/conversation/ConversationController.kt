package com.aisandbox.android.conversation

import android.util.Log
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ConversationClient
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.net.ReconnectController
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.screens.TerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * UC-37 — process-scoped owner of one session's structured-conversation channel.
 * The conversation sibling of [TerminalStreamController]: it owns the
 * [ConversationClient], the reconnect loop (on an app-scoped [SupervisorJob]
 * cancelled only by [close], so it can't leak past disconnect), the parsed
 * conversation item list + turn/spinner/pending-sheet/target state, and the 3s
 * enumerate poll. Held by [com.aisandbox.android.AppContainer], so the view
 * survives back-navigation (AC22 continuity).
 *
 * <p>Single-active: opening a conversation for a different session tears down the
 * prior one (the container enforces this), and a conversation and a tmux view of
 * the SAME session may coexist — they drive one tmux session so input from either
 * reflects in both (AC23).
 */
class ConversationController(
    val sessionN: Int,
    private val profileStore: ServerProfileStore,
    private val httpClientFactory: (ServerProfile) -> AiSandboxHttpClient,
    private val clientFactory: (AiSandboxHttpClient, Int) -> ConversationClient,
    private val onClosed: (Int) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconnect = ReconnectController()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var client: ConversationClient? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<TerminalState>(TerminalState.Idle)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _items = MutableStateFlow<List<ConversationItem>>(emptyList())
    val items: StateFlow<List<ConversationItem>> = _items.asStateFlow()

    private val _targets = MutableStateFlow<List<StreamTarget>>(emptyList())
    val targets: StateFlow<List<StreamTarget>> = _targets.asStateFlow()

    private val _selectedTargetId = MutableStateFlow(TerminalStreamController.MAIN_TARGET_ID)
    val selectedTargetId: StateFlow<String> = _selectedTargetId.asStateFlow()

    private val _pendingSheet = MutableStateFlow<PendingSheet?>(null)
    val pendingSheet: StateFlow<PendingSheet?> = _pendingSheet.asStateFlow()

    private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
    val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

    /** UC-41 — the currently-open tool-detail dialog state (AC5/AC9); null when none is open. */
    private val _toolDetail = MutableStateFlow<ToolDetailState?>(null)
    val toolDetail: StateFlow<ToolDetailState?> = _toolDetail.asStateFlow()

    /**
     * UC-41 — in-flight `fetch-detail` requests keyed by `toolUseId`. Guarded by
     * [detailLock]. Each is completed by the matching `tool-detail` frame, the 8 s
     * timeout, a disconnect, or [closeDetail] — and PRUNED on EVERY one of those exit
     * paths so a request can never leak.
     */
    private val detailLock = Any()
    private val pendingDetail = HashMap<String, CompletableDeferred<ToolDetailState>>()

    /** The toolUseId of the dialog currently shown, so a stale in-flight reply can't overwrite a newer open. */
    @Volatile
    private var activeDetailId: String? = null

    /** Ordered, deduped item store (key → item). Guarded by [itemLock]. */
    private val itemLock = Any()
    private val itemMap = LinkedHashMap<String, ConversationItem>()

    @Volatile
    private var backfilling = false

    /** Start (or no-op resume) the connect/reconnect loop. Idempotent. */
    fun attach(n: Int) {
        require(n == sessionN) { "controller bound to $sessionN, attach($n)" }
        if (connectJob?.isActive == true) return
        startConnectLoop()
    }

    /** AC8/AC9 — submit composer text. Shows the working spinner immediately (AC14). */
    fun submitComposer(text: String) {
        if (text.isBlank()) return
        if (_pendingSheet.value != null) return // AC12 — composer locked while a sheet is pending
        _turnPhase.value = TurnPhase.WORKING
        client?.sendComposer(text)
    }

    /** AC11 — submit a structured answer; optimistically dismiss the sheet and show the spinner. */
    fun submitAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) {
        client?.sendAnswer(questionUuid, questionIndex, selections, freeText)
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.WORKING
    }

    /** AC17 — switch the tailed/inject target; clear the view for the new target's transcript. */
    fun selectTarget(targetId: String) {
        _selectedTargetId.value = targetId
        clearItems()
        closeDetail() // the open dialog belongs to the old target's tool call
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.IDLE
        client?.sendSelectTarget(targetId)
    }

    /** Interrupt the active turn (ESC). */
    fun interrupt() {
        client?.sendInterrupt()
        _turnPhase.value = TurnPhase.IDLE
    }

    /**
     * UC-41 (AC5/AC6/AC9) — open the detail dialog for a tapped tool bubble: show
     * [ToolDetailState.Loading], send a `fetch-detail`, and await the matching
     * `tool-detail` frame with an 8 s timeout. The in-flight request is registered in
     * [pendingDetail] and PRUNED on every exit path — frame, timeout, cancellation
     * (from [closeDetail]), or disconnect — so it can never leak. A stale reply for a
     * superseded open is dropped via [activeDetailId].
     */
    fun openDetail(toolUseId: String, uuid: String) {
        if (toolUseId.isBlank()) return
        activeDetailId = toolUseId
        _toolDetail.value = ToolDetailState.Loading
        val deferred = CompletableDeferred<ToolDetailState>()
        synchronized(detailLock) {
            // A prior in-flight request for the same id is superseded; complete it so its
            // awaiter unblocks and prunes, then replace it.
            pendingDetail.put(toolUseId, deferred)?.complete(ToolDetailState.Unavailable)
        }
        client?.sendFetchDetail(toolUseId, uuid)
        scope.launch {
            val resolved = try {
                withTimeout(DETAIL_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                ToolDetailState.Unavailable
            } catch (e: CancellationException) {
                null // closeDetail cancelled this await; do not publish
            } finally {
                synchronized(detailLock) {
                    if (pendingDetail[toolUseId] === deferred) pendingDetail.remove(toolUseId)
                }
            }
            // Publish only if this is still the dialog the user is looking at.
            if (resolved != null && activeDetailId == toolUseId) {
                _toolDetail.value = resolved
            }
        }
    }

    /** UC-41 — dismiss the detail dialog and cancel+prune every in-flight fetch (no leaks). */
    fun closeDetail() {
        activeDetailId = null
        _toolDetail.value = null
        synchronized(detailLock) {
            pendingDetail.values.forEach { it.cancel() }
            pendingDetail.clear()
        }
    }

    fun userTriggeredReconnect() {
        reconnect.reset()
        startConnectLoop()
    }

    fun close(reason: String = "controller-close") {
        connectJob = null
        scope.cancel()
        client?.close(reason)
        client = null
        _state.value = TerminalState.Idle
        onClosed(sessionN)
    }

    // ──────────────────────── frame handling ────────────────────────

    private fun onFrame(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "turn-start" -> {
                val t = str(obj, "text")
                if (!t.isNullOrBlank()) addItem(ConversationItem.UserMessage(uuid(obj), source(obj), sidechain(obj), t))
                // A new turn means any prior question resolved/aborted (AC12).
                _pendingSheet.value = null
                if (!backfilling) _turnPhase.value = TurnPhase.WORKING
            }
            "thinking" -> {
                addItem(ConversationItem.Thinking(uuid(obj), source(obj), sidechain(obj), str(obj, "text") ?: ""))
                if (!backfilling) _turnPhase.value = TurnPhase.THINKING
            }
            "assistant-text" -> {
                addItem(ConversationItem.AssistantMessage(uuid(obj), source(obj), sidechain(obj), str(obj, "text") ?: ""))
                if (!backfilling && _turnPhase.value != TurnPhase.IDLE) _turnPhase.value = TurnPhase.WORKING
            }
            "tool-use" -> upsertToolUse(
                uuid(obj), source(obj), sidechain(obj),
                toolName = str(obj, "toolName") ?: "tool",
                toolUseId = str(obj, "toolUseId") ?: "",
                inputSummary = str(obj, "inputSummary") ?: "",
                primaryText = str(obj, "primaryText") ?: "",
            )
            "tool-result" -> upsertToolResult(
                uuid(obj), source(obj), sidechain(obj),
                toolUseId = str(obj, "toolUseId") ?: "",
                isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
                summary = str(obj, "summary") ?: "",
            )
            "tool-detail" -> onToolDetail(obj)
            "system-note" -> addItem(
                // UC-42 (AC4) — a harness-injected line with no host bubble. Render-only:
                // it does NOT advance the turn phase or touch the pending sheet.
                ConversationItem.SystemNote(
                    uuid(obj), source(obj), sidechain(obj),
                    label = str(obj, "label") ?: "",
                    detail = str(obj, "detail") ?: "",
                ),
            )
            "question" -> {
                val toolUseId = str(obj, "toolUseId") ?: uuid(obj)
                val questions = parseQuestions(obj["questions"] as? JsonArray)
                addItem(ConversationItem.Question(uuid(obj), source(obj), sidechain(obj), toolUseId, questions))
                _pendingSheet.value = PendingSheet.Questions(toolUseId, questions)
            }
            "plan-approval" -> {
                val toolUseId = str(obj, "toolUseId") ?: uuid(obj)
                val plan = str(obj, "plan") ?: ""
                addItem(ConversationItem.PlanApproval(uuid(obj), source(obj), sidechain(obj), toolUseId, plan))
                _pendingSheet.value = PendingSheet.Plan(toolUseId, plan)
            }
            "turn-end" -> {
                if (!backfilling) _turnPhase.value = TurnPhase.IDLE
                // The transcript advanced past any pending question (AC12/AC15).
                _pendingSheet.value = null
            }
            "targets" -> onTargets(obj)
            "target-selected" -> str(obj, "targetId")?.let { _selectedTargetId.value = it }
            "backfill-start" -> backfilling = true
            "backfill-end" -> {
                backfilling = false
                if (_pendingSheet.value == null) _turnPhase.value = TurnPhase.IDLE
            }
            "error" -> Log.w(TAG, "conversation error frame: ${text.take(200)}")
            else -> { /* unknown frame — ignore */ }
        }
    }

    private fun onTargets(obj: JsonObject) {
        val arr = obj["targets"] as? JsonArray ?: JsonArray(emptyList())
        _targets.value = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = str(o, "id") ?: return@mapNotNull null
            StreamTarget(
                id = id,
                kind = str(o, "kind") ?: "swarm",
                title = str(o, "title") ?: str(o, "agentName") ?: id,
                agentName = str(o, "agentName"),
                agentType = str(o, "agentType"),
                agentColor = str(o, "agentColor"),
                teamName = str(o, "teamName"),
                pendingActivity = o["pendingActivity"]?.jsonPrimitive?.booleanOrNull ?: false,
                pendingQuestion = o["pendingQuestion"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        str(obj, "selectedId")?.let { _selectedTargetId.value = it }
    }

    private fun parseQuestions(arr: JsonArray?): List<ConvQuestion> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { e ->
            val q = e as? JsonObject ?: return@mapNotNull null
            val opts = (q["options"] as? JsonArray)?.mapNotNull { oe ->
                val o = oe as? JsonObject ?: return@mapNotNull null
                ConvOption(str(o, "label") ?: "", str(o, "description") ?: "")
            } ?: emptyList()
            ConvQuestion(
                question = str(q, "question") ?: "",
                header = str(q, "header") ?: "",
                multiSelect = q["multiSelect"]?.jsonPrimitive?.booleanOrNull ?: false,
                options = opts,
            )
        }
    }

    // ──────────────────────── item store ────────────────────────

    private fun addItem(item: ConversationItem) {
        synchronized(itemLock) {
            if (itemMap.containsKey(item.key)) return // AC6/AC22 — dedupe backfill overlap
            itemMap[item.key] = item
            _items.value = itemMap.values.toList()
        }
    }

    /**
     * UC-41 (AC4/AC8) — additive upsert of the `tool_use` half of a merged
     * [ConversationItem.ToolActivity], keyed on [toolUseId]. If the row already exists
     * (e.g. its `tool_result` arrived FIRST across a backfill boundary), its [result]
     * is preserved; otherwise the row is created with `result = null` (the "awaiting
     * result" state). On a backfill-overlap re-delivery the use fields are idempotent.
     */
    private fun upsertToolUse(
        uuid: String,
        source: String,
        isSidechain: Boolean,
        toolName: String,
        toolUseId: String,
        inputSummary: String,
        primaryText: String,
    ) {
        val key = "toolactivity|$toolUseId"
        synchronized(itemLock) {
            val existing = itemMap[key] as? ConversationItem.ToolActivity
            itemMap[key] = ConversationItem.ToolActivity(
                uuid = uuid,
                source = source,
                isSidechain = isSidechain,
                toolName = toolName,
                toolUseId = toolUseId,
                inputSummary = inputSummary,
                primaryText = primaryText,
                result = existing?.result, // preserve a result that arrived first
            )
            _items.value = itemMap.values.toList()
        }
    }

    /**
     * UC-41 (AC4/AC7/AC8) — additive upsert of the `tool_result` half. If the row's
     * `tool_use` arrived first, it is merged in place (the awaiting state clears);
     * otherwise a placeholder row is created carrying only the result, so a
     * result-before-use ordering still renders one merged bubble (the use fields fill
     * in when the `tool-use` frame lands).
     */
    private fun upsertToolResult(
        uuid: String,
        source: String,
        isSidechain: Boolean,
        toolUseId: String,
        isError: Boolean,
        summary: String,
    ) {
        val key = "toolactivity|$toolUseId"
        val data = ToolResultData(isError = isError, summary = summary)
        synchronized(itemLock) {
            val existing = itemMap[key] as? ConversationItem.ToolActivity
            itemMap[key] = if (existing != null) {
                existing.copy(result = data)
            } else {
                ConversationItem.ToolActivity(
                    uuid = uuid,
                    source = source,
                    isSidechain = isSidechain,
                    toolName = "tool",
                    toolUseId = toolUseId,
                    inputSummary = "",
                    primaryText = "",
                    result = data,
                )
            }
            _items.value = itemMap.values.toList()
        }
    }

    /** UC-41 (AC5/AC9) — route a `tool-detail` frame to its in-flight [openDetail] awaiter. */
    private fun onToolDetail(obj: JsonObject) {
        val toolUseId = str(obj, "toolUseId") ?: return
        val available = obj["available"]?.jsonPrimitive?.booleanOrNull ?: false
        val resolved: ToolDetailState = if (available) {
            ToolDetailState.Loaded(
                input = str(obj, "input") ?: "",
                result = str(obj, "result") ?: "",
                isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } else {
            ToolDetailState.Unavailable
        }
        synchronized(detailLock) {
            pendingDetail[toolUseId]?.complete(resolved)
        }
    }

    /** UC-41 (AC9) — on any disconnect, fail every in-flight detail fetch so the dialog degrades cleanly. */
    private fun failPendingDetailsOnDisconnect() {
        synchronized(detailLock) {
            pendingDetail.values.forEach { it.complete(ToolDetailState.Unavailable) }
        }
    }

    private fun clearItems() {
        synchronized(itemLock) {
            itemMap.clear()
            _items.value = emptyList()
        }
    }

    // ──────────────────────── connect loop ────────────────────────

    private fun startConnectLoop() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val profile = profileStore.current()
            if (profile == null) {
                _state.value = TerminalState.Failed("no_profile")
                return@launch
            }
            val http = httpClientFactory(profile)
            while (isActive) {
                val c = clientFactory(http, sessionN)
                client = c
                _state.value = TerminalState.Connecting
                try {
                    c.connect()
                } catch (t: Throwable) {
                    Log.w(TAG, "conv connect threw: $t")
                }
                when (c.state.value) {
                    is ConversationClient.State.Open -> {
                        reconnect.reset()
                        _state.value = TerminalState.Open
                        c.sendEnumerate()
                        if (_selectedTargetId.value != TerminalStreamController.MAIN_TARGET_ID) {
                            c.sendSelectTarget(_selectedTargetId.value)
                        }
                        val pump = launch { c.incoming.collect { onFrame(it) } }
                        val enumerate = launch {
                            while (isActive) {
                                delay(TerminalStreamController.ENUMERATE_INTERVAL_MS)
                                c.sendEnumerate()
                            }
                        }
                        val terminal = c.state.first { it !is ConversationClient.State.Open }
                        pump.cancel()
                        enumerate.cancel()
                        failPendingDetailsOnDisconnect() // AC9 — disconnect-while-pending → Unavailable
                        if (terminal is ConversationClient.State.Revoked) {
                            _state.value = TerminalState.Revoked
                            return@launch
                        }
                        _state.value = TerminalState.Connecting
                    }
                    is ConversationClient.State.Revoked -> {
                        _state.value = TerminalState.Revoked
                        return@launch
                    }
                    else -> { /* failed to open — fall through to back-off */ }
                }
                if (!isActive) break
                if (reconnect.shouldGiveUp()) {
                    _state.value = TerminalState.GaveUp
                    NetworkEvents.tryEmit(NetworkEvent.StreamGaveUp(c.streamId))
                    return@launch
                }
                val delayMs = reconnect.nextDelayMs()
                NetworkEvents.tryEmit(NetworkEvent.StreamReconnecting(c.streamId, reconnect.attemptCount, delayMs))
                _state.value = TerminalState.Reconnecting(reconnect.attemptCount, delayMs)
                delay(delayMs)
            }
        }
    }

    // ──────────────────────── JSON helpers ────────────────────────

    private fun str(o: JsonObject, key: String): String? = o[key]?.jsonPrimitive?.contentOrNull
    private fun uuid(o: JsonObject): String = str(o, "uuid") ?: ""
    private fun source(o: JsonObject): String = str(o, "source") ?: "main"
    private fun sidechain(o: JsonObject): Boolean = o["isSidechain"]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        private const val TAG = "ConversationCtrl"

        /** UC-41 (AC9) — client-side timeout on a `fetch-detail` round-trip; matches the server's 8 s helper cap. */
        private const val DETAIL_TIMEOUT_MS = 8_000L
    }
}
