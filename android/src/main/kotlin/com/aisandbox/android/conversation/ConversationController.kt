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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.IDLE
        client?.sendSelectTarget(targetId)
    }

    /** Interrupt the active turn (ESC). */
    fun interrupt() {
        client?.sendInterrupt()
        _turnPhase.value = TurnPhase.IDLE
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
            "tool-use" -> addItem(
                ConversationItem.ToolUse(
                    uuid(obj), source(obj), sidechain(obj),
                    str(obj, "toolName") ?: "tool", str(obj, "toolUseId") ?: "", str(obj, "inputSummary") ?: "",
                ),
            )
            "tool-result" -> addItem(
                ConversationItem.ToolResult(
                    uuid(obj), source(obj), sidechain(obj),
                    str(obj, "toolUseId") ?: "", obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
                    str(obj, "summary") ?: "",
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
    }
}
