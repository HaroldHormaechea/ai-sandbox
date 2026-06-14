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
    /**
     * UC-75 — conservative spinner safety-net timeout. If an answer is submitted and no
     * forward-progress frame arrives within this window while still pinned WORKING, the
     * watchdog recovers the spinner to IDLE (usable) — it NEVER aborts the turn. Injectable
     * so tests can drive it without a 45 s wait.
     */
    private val answerWatchdogMs: Long = ANSWER_WATCHDOG_MS,
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

    /**
     * UC-66 — last model the user picked for each target (targetId → model id), so the
     * picker can highlight it (AC5). Best-effort, client-side last-selection ONLY: Claude
     * Code does not surface the active model over the conversation frames, so this is what
     * the user last chose in-app, not an authoritative read of the running model. Written by
     * [selectModel]; deliberately NOT wiped by [selectTarget]/[clearItems] (a model choice
     * outlives a transcript reset). Guarded by [modelLock].
     */
    private val modelLock = Any()
    private val selectedModelByTarget = HashMap<String, String>()

    /**
     * UC-66 — the selected model id for the CURRENTLY-selected target, re-published whenever
     * the target changes ([selectTarget] / `targets` / `target-selected`) or the user picks a
     * model ([selectModel]). Null when no model has been chosen for the current target. The
     * picker reflects this as the highlighted row (AC5).
     */
    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
    val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

    /**
     * UC-75 — spinner safety-net. [awaitingAnswerKey] holds the `questionUuid` of the
     * answer most recently submitted while we are still waiting for the turn to advance;
     * [answerWatchdogJob] is the conservative timeout armed alongside it. Any forward-progress
     * frame (turn-start/thinking/assistant-text/tool-use/tool-result/turn-end/backfill-start)
     * proves the answer landed and disarms both; a `pending-clear` while still awaiting + WORKING
     * recovers the spinner to IDLE; the watchdog is the last-resort fallback. Recovery is ALWAYS
     * to IDLE (a usable state) and NEVER aborts/interrupts the turn.
     */
    @Volatile
    private var awaitingAnswerKey: String? = null
    private var answerWatchdogJob: Job? = null

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

    /**
     * UC-65 — post-`/clear` suppression guard. While true, [onFrame] drops content-producing
     * frames belonging to the pre-clear epoch so a late `assistant-text`/`tool-result` (or the
     * `/clear` command echo itself) can't resurrect the locally-wiped transcript (AC3). Lifted
     * after [CLEAR_SUPPRESS_MS], or immediately on the next user action (any submit*), whichever
     * comes first. Control frames (targets/selection/turn boundaries) always pass through.
     */
    @Volatile
    private var clearSuppressActive = false

    /**
     * UC-45 — optimistic local-echo state, all guarded by [itemLock] (same lock as
     * [itemMap], so the optimistic insert and the reconcile mutate the store atomically).
     *
     * [localSeqCounter] mints a monotonic id per submission, used as the optimistic
     * bubble's stable [ConversationItem.UserMessage.localSeq] / dedupe key.
     * [pendingEchoes] is a FIFO of the local keys of optimistic bubbles still awaiting
     * their authoritative `turn-start` echo, so echoes reconcile in submission order
     * (AC5; rapid multi-submit, pitfall). [reconciledServerKeys] records the
     * server-derived keys of bubbles already reconciled, so a reconnect/backfill replay
     * of an already-reconciled line is a no-op rather than a duplicate (AC8).
     */
    private var localSeqCounter = 0L
    private val pendingEchoes = ArrayDeque<String>()
    private val reconciledServerKeys = HashSet<String>()

    /** Start (or no-op resume) the connect/reconnect loop. Idempotent. */
    fun attach(n: Int) {
        require(n == sessionN) { "controller bound to $sessionN, attach($n)" }
        if (connectJob?.isActive == true) return
        startConnectLoop()
    }

    /**
     * AC8/AC9 — submit composer text. Shows the working spinner immediately (AC14).
     *
     * UC-45 — also echoes the message locally as an optimistic bubble the instant the
     * user hits send (AC1), so the transcript reflects what they typed without waiting
     * on the server round-trip. The bubble carries a stable [localSeq] key and is pushed
     * onto [pendingEchoes]; the authoritative `turn-start` echo later reconciles it in
     * place via [reconcileOrAddUserMessage] (AC3). The text sent to the session is
     * unchanged (AC7) — local echo is display-only.
     */
    fun submitComposer(text: String) {
        clearSuppressActive = false // UC-65 (AC5) — a new user action deterministically lifts the clear guard
        if (text.isBlank()) return
        if (_pendingSheet.value != null) return // AC12 — composer locked while a sheet is pending
        clearAnswerWatchdog() // UC-75 — a fresh composer turn supersedes any pending answer safety-net
        _turnPhase.value = TurnPhase.WORKING
        synchronized(itemLock) {
            val seq = localSeqCounter++
            val key = "localuser|$seq"
            // uuid="" until the server echo backfills it; source="main", isSidechain=false
            // match a user's own line in the structured (non-sidechain) conversation.
            itemMap[key] = ConversationItem.UserMessage(
                uuid = "",
                source = "main",
                isSidechain = false,
                text = text,
                localSeq = seq,
            )
            pendingEchoes.addLast(key)
            _items.value = itemMap.values.toList()
        }
        client?.sendComposer(text) // AC7 — unchanged; injects byte-for-byte the same text
    }

    /** AC11 — submit a structured answer; optimistically dismiss the sheet and show the spinner. */
    fun submitAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) {
        clearSuppressActive = false // UC-65 (AC5) — a new user action deterministically lifts the clear guard
        // UC-75 — normalize the "Other" free text (fold CRLF/CR→LF, trim surrounding newlines/
        // whitespace) so a stray leading/trailing newline can't commit/decline early; interior
        // newlines survive end-to-end (server injects them with C-j). Indices are untouched (UC-57).
        client?.sendAnswer(questionUuid, questionIndex, selections, normalizeFreeText(freeText))
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.WORKING
        armAnswerWatchdog(questionUuid) // UC-75 — spinner safety-net
    }

    /**
     * UC-43 (AC2/AC3/AC4) — submit all answers of a multi-question (N>1) sheet in a
     * single `answer-batch` frame; optimistically dismiss the sheet and show the
     * spinner, exactly like [submitAnswer]. [items] are in `questionIndex` order.
     */
    fun submitAnswerBatch(questionUuid: String, items: List<AnswerItem>) {
        clearSuppressActive = false // UC-65 (AC5) — a new user action deterministically lifts the clear guard
        // UC-75 — normalize each item's "Other" free text (see [submitAnswer]); copy preserves
        // questionIndex/selections exactly, so the UC-57 option/Other index mapping is unchanged.
        val normalized = items.map { it.copy(freeText = normalizeFreeText(it.freeText)) }
        client?.sendAnswerBatch(questionUuid, normalized)
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.WORKING
        armAnswerWatchdog(questionUuid) // UC-75 — spinner safety-net
    }

    /**
     * UC-75 — defensively normalize an "Other" free-text answer before it is sent.
     * CRLF/CR are folded to LF, and leading/trailing newlines + surrounding whitespace
     * are trimmed so a stray leading/trailing newline can NEVER commit or decline the ask
     * prematurely. INTERIOR newlines are PRESERVED — the server's newline-safe injection
     * ([typeMultiline], `C-j` between lines) delivers them, so a genuine multi-line answer
     * survives end-to-end (AC2/AC4). This only rewrites the text payload — selections and
     * indices are never touched (no UC-57 regression).
     */
    internal fun normalizeFreeText(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n").trim()

    /**
     * UC-75 — arm the spinner safety-net for the just-submitted answer [key] (its
     * `questionUuid`). Records [awaitingAnswerKey] and starts a single conservative
     * [answerWatchdogMs] timeout; if it fires while still awaiting this key AND pinned
     * WORKING, the spinner is recovered to IDLE (usable) — never aborting the turn. Any
     * earlier watchdog is cancelled first so only one is ever in flight.
     */
    private fun armAnswerWatchdog(key: String) {
        awaitingAnswerKey = key
        answerWatchdogJob?.cancel()
        answerWatchdogJob = scope.launch {
            delay(answerWatchdogMs)
            if (awaitingAnswerKey == key && _turnPhase.value == TurnPhase.WORKING) {
                _turnPhase.value = TurnPhase.IDLE // recover to usable; never interrupt/abort
            }
            if (awaitingAnswerKey == key) awaitingAnswerKey = null
        }
    }

    /** UC-75 — disarm the spinner safety-net (forward progress, a new user action, or teardown). */
    private fun clearAnswerWatchdog() {
        awaitingAnswerKey = null
        answerWatchdogJob?.cancel()
        answerWatchdogJob = null
    }

    /** AC17 — switch the tailed/inject target; clear the view for the new target's transcript. */
    fun selectTarget(targetId: String) {
        _selectedTargetId.value = targetId
        clearItems()
        closeDetail() // the open dialog belongs to the old target's tool call
        clearAnswerWatchdog() // UC-75 — the pending answer belongs to the old target
        _pendingSheet.value = null
        _turnPhase.value = TurnPhase.IDLE
        // UC-66 — re-publish the new target's last-picked model (NOT wiped by the switch).
        republishSelectedModel(targetId)
        client?.sendSelectTarget(targetId)
    }

    /** Interrupt the active turn (ESC). */
    fun interrupt() {
        client?.sendInterrupt()
        clearAnswerWatchdog() // UC-75 — explicit interrupt resolves the turn; no safety-net needed
        _turnPhase.value = TurnPhase.IDLE
    }

    /**
     * UC-66 — change the model of the currently-selected target by sending Claude Code's
     * `/model <id>` slash command down the SAME composer-input path the server already routes
     * to `ctx.selectedTarget` (AC4: main conversation when none selected, else the
     * AgentSwitcherBar selection).
     *
     * Deliberate decisions (documented per the proposal):
     * - Sent via the RAW [ConversationClient.sendComposer] path, NOT [submitComposer], so it
     *   leaves NO optimistic `/model …` user bubble in the transcript. The command echo and
     *   Claude's confirmation render normally through the live frames — the model change is
     *   observable that way, so no optimistic UI is needed.
     * - Does NOT touch [_turnPhase] and arms NO suppression guard: a model switch is a quick
     *   command, not a content turn, so the spinner/clear-guard machinery stays out of it.
     * - Records the choice in [selectedModelByTarget] for the current target and re-publishes
     *   [selectedModelId] so the picker can highlight it (AC5, best-effort last-selection).
     */
    fun selectModel(id: String) {
        if (id.isBlank()) return
        val target = _selectedTargetId.value
        synchronized(modelLock) {
            selectedModelByTarget[target] = id
        }
        _selectedModelId.value = id
        client?.sendComposer("/model $id")
    }

    /** UC-66 — re-publish [selectedModelId] for [targetId] (null when none chosen yet). */
    private fun republishSelectedModel(targetId: String) {
        _selectedModelId.value = synchronized(modelLock) { selectedModelByTarget[targetId] }
    }

    /**
     * UC-65 — reset the conversation in place: wipe the locally-rendered transcript AND send
     * `/clear` to the session's Claude so its context is reset too (AC2/AC3). Unlike [close]/
     * Disconnect, the stream stays connected and the composer stays usable (AC5/AC6).
     *
     * Sequencing matters:
     * - The suppression guard ([clearSuppressActive]) is armed BEFORE anything is sent, so a fast
     *   `/clear` command echo — or any in-flight pre-clear frame — is dropped by [onFrame] rather
     *   than resurrecting the wiped transcript. It auto-lifts after [CLEAR_SUPPRESS_MS] or on the
     *   next user submit, whichever comes first.
     * - Any pending question/plan sheet is dismissed immediately (AC4). When a sheet was open the
     *   session is mid-blocking-turn, so we [sendInterrupt] first, wait [CLEAR_INTERRUPT_GAP_MS]
     *   for the harness to settle, then send `/clear`. The happy path (no sheet) sends `/clear`
     *   directly with no interrupt.
     * - `/clear` is sent via the raw [ConversationClient.sendComposer] path, NOT [submitComposer],
     *   so no optimistic `/clear` user bubble is left behind in the wiped transcript (pitfall).
     */
    fun clear() {
        val hadSheet = _pendingSheet.value != null
        // Arm the guard BEFORE sending so a fast `/clear` echo is caught (AC3).
        clearSuppressActive = true
        scope.launch {
            delay(CLEAR_SUPPRESS_MS)
            clearSuppressActive = false
        }
        clearItems()
        closeDetail()
        clearAnswerWatchdog() // UC-75 — /clear resolves the turn locally; drop the safety-net
        _pendingSheet.value = null // AC4 — dismiss any pending sheet immediately
        // Composer enablement is gated on pendingSheet == null, so it stays enabled (AC5).
        _turnPhase.value = TurnPhase.IDLE
        if (hadSheet) {
            scope.launch {
                client?.sendInterrupt()
                delay(CLEAR_INTERRUPT_GAP_MS)
                client?.sendComposer("/clear")
            }
        } else {
            client?.sendComposer("/clear")
        }
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
        clearAnswerWatchdog() // UC-75 — drop the spinner safety-net on teardown
        scope.cancel()
        client?.close(reason)
        client = null
        synchronized(itemLock) {
            // UC-45 — clear optimistic-echo bookkeeping alongside the rest of the teardown.
            pendingEchoes.clear()
            reconciledServerKeys.clear()
        }
        _state.value = TerminalState.Idle
        onClosed(sessionN)
    }

    // ──────────────────────── frame handling ────────────────────────

    private fun onFrame(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        // UC-75 — any forward-progress frame proves the submitted answer landed and the turn is
        // advancing, so disarm the spinner safety-net (the watchdog must not later flip a
        // legitimately-working turn to IDLE). Checked BEFORE the clear-suppress early-returns so a
        // genuine forward frame always disarms.
        if (type != null && type in ANSWER_PROGRESS_FRAMES) clearAnswerWatchdog()
        // UC-65 — while the post-`/clear` suppression guard is active, drop content-producing
        // frames belonging to the pre-clear epoch so a late assistant-text/tool-result (or the
        // `/clear` command echo itself) can't resurrect the locally-wiped transcript (AC3).
        // Control frames (targets/selection/turn boundaries/errors) fall through untouched.
        if (clearSuppressActive) {
            when (type) {
                "turn-start", "thinking", "assistant-text", "tool-use", "tool-result",
                "system-note", "question", "plan-approval", "pending-question",
                -> return
                // Defensive: a fresh transcript stream beginning under the guard means the
                // session is replaying its (now post-clear) history — re-wipe, lift the guard,
                // and let the backfill render the clean transcript from the start.
                "backfill-start" -> {
                    clearItems()
                    clearSuppressActive = false
                    backfilling = true
                    return
                }
                // targets, target-selected, backfill-end, pending-clear, turn-end, error → pass
                else -> { /* fall through to normal handling */ }
            }
        }
        when (type) {
            "turn-start" -> {
                val t = str(obj, "text")
                // Preserve today's non-blank guard: a blank turn-start must neither add a
                // bubble nor pop a pending echo (else it could blank out the optimistic text).
                if (!t.isNullOrBlank()) {
                    reconcileOrAddUserMessage(
                        ConversationItem.UserMessage(uuid(obj), source(obj), sidechain(obj), t),
                    )
                }
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
            "tool-result" -> {
                val toolUseId = str(obj, "toolUseId") ?: ""
                upsertToolResult(
                    uuid(obj), source(obj), sidechain(obj),
                    toolUseId = toolUseId,
                    isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
                    summary = str(obj, "summary") ?: "",
                )
                // UC-44 AC3a — the tool-result that resolves the pending ask (its
                // toolUseId == the sheet's questionUuid) must dismiss the sheet, so it
                // can never linger after the underlying ask is resolved — including the
                // failure path where an "Other" answer declined/aborted the ask
                // server-side while the conversation has already moved on. Additive: the
                // turn-start/turn-end/select-target clears remain (AC12/AC15). Covers
                // both Questions and Plan sheets (both carry questionUuid == toolUseId).
                if (toolUseId.isNotBlank() && _pendingSheet.value?.questionUuid == toolUseId) {
                    _pendingSheet.value = null
                }
            }
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
            // UC-50 — a LIVE, pane-delivered pending prompt (the transcript carried
            // nothing for the blocking turn). Set the sheet ONLY — add NO inline item,
            // so the later transcript write owns the single inline bubble (dedupe). Also
            // clear the perpetual "Working…" spinner: a pending prompt is at-rest waiting.
            "pending-question" -> {
                val promptKey = str(obj, "promptKey") ?: return
                val kind = str(obj, "kind") ?: "questions"
                val answerable = obj["answerable"]?.jsonPrimitive?.booleanOrNull ?: true
                _pendingSheet.value = if (kind == "plan") {
                    PendingSheet.Plan(promptKey, str(obj, "plan") ?: "", answerable)
                } else {
                    PendingSheet.Questions(promptKey, parseQuestions(obj["questions"] as? JsonArray), answerable)
                }
                _turnPhase.value = TurnPhase.IDLE
            }
            // UC-50 — the pane prompt's chrome disappeared (answered/dismissed in tmux
            // with no resolving transcript line). Clear ONLY our own pane-delivered sheet
            // (key match), never a transcript-delivered one.
            "pending-clear" -> {
                val promptKey = str(obj, "promptKey") ?: ""
                if (_pendingSheet.value?.questionUuid == promptKey) {
                    _pendingSheet.value = null
                }
                // UC-75 — recovery gap: a pending-clear that arrives while we are still awaiting a
                // submitted answer AND pinned WORKING means the ask was resolved/declined in the pane
                // with no forward frame to flip the spinner. Recover to IDLE (usable) — never abort.
                if (awaitingAnswerKey != null && _turnPhase.value == TurnPhase.WORKING) {
                    _turnPhase.value = TurnPhase.IDLE
                }
                clearAnswerWatchdog()
            }
            "turn-end" -> {
                if (!backfilling) _turnPhase.value = TurnPhase.IDLE
                // The transcript advanced past any pending question (AC12/AC15).
                _pendingSheet.value = null
            }
            "targets" -> onTargets(obj)
            "target-selected" -> str(obj, "targetId")?.let {
                _selectedTargetId.value = it
                republishSelectedModel(it) // UC-66 — keep the highlighted model in sync with the target
            }
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
        str(obj, "selectedId")?.let {
            _selectedTargetId.value = it
            republishSelectedModel(it) // UC-66 — keep the highlighted model in sync with the target
        }
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
            // UC-45 (AC8) — a reconnect/backfill replay of a user line we already reconciled
            // into an optimistic bubble must not re-add a second (server-keyed) bubble.
            if (item is ConversationItem.UserMessage && reconciledServerKeys.contains(item.key)) return
            itemMap[item.key] = item
            _items.value = itemMap.values.toList()
        }
    }

    /**
     * UC-45 (AC3/AC5/AC8) — reconcile an authoritative `turn-start` user line against the
     * oldest outstanding optimistic bubble, or add it as a fresh item if there is nothing to
     * reconcile. Caller guarantees [serverMsg].text is non-blank.
     *
     * - **Live (`!backfilling`) with a pending echo** → FIFO reconcile: pop the oldest pending
     *   local key and replace that bubble *in place* with the server's uuid/text/source/
     *   isSidechain (localSeq preserved, so [ConversationItem.UserMessage.key] is unchanged and
     *   Compose updates the row without flicker — AC3). The server-derived key is remembered in
     *   [reconciledServerKeys] so a later replay is deduped (AC8). If the pending key is somehow
     *   absent from the map (deque/map out of sync), fall through to [addItem] rather than NPE.
     * - **Backfill with a pending echo** → text-gated reconcile: only reconcile the oldest
     *   pending bubble whose normalized text matches, so history replay can't mis-match a
     *   pending submission; otherwise add normally.
     * - **Otherwise** → [addItem] as today.
     */
    private fun reconcileOrAddUserMessage(serverMsg: ConversationItem.UserMessage) {
        synchronized(itemLock) {
            if (pendingEchoes.isEmpty()) {
                addItem(serverMsg)
                return
            }
            if (!backfilling) {
                val localKey = pendingEchoes.removeFirst()
                reconcileInPlace(localKey, serverMsg)
                return
            }
            // backfilling: only reconcile if the oldest pending bubble's text matches the
            // server text (normalized), else treat as an ordinary (replayed) line.
            val oldestKey = pendingEchoes.first()
            val pending = itemMap[oldestKey] as? ConversationItem.UserMessage
            if (pending != null && normalizeText(pending.text) == normalizeText(serverMsg.text)) {
                pendingEchoes.removeFirst()
                reconcileInPlace(oldestKey, serverMsg)
            } else {
                addItem(serverMsg)
            }
        }
    }

    /**
     * UC-45 — replace the optimistic bubble at [localKey] with the server copy in place,
     * preserving its [ConversationItem.UserMessage.localSeq] (hence its key) so the row is
     * updated, not removed+re-added. Records the server-derived key for replay dedupe (AC8).
     * Must be called under [itemLock]. If the key is absent (deque/map drift), falls back to
     * [addItem] so the server line is never lost.
     */
    private fun reconcileInPlace(localKey: String, serverMsg: ConversationItem.UserMessage) {
        val pending = itemMap[localKey] as? ConversationItem.UserMessage
        if (pending == null) {
            addItem(serverMsg)
            return
        }
        itemMap[localKey] = pending.copy(
            uuid = serverMsg.uuid,
            text = serverMsg.text,
            source = serverMsg.source,
            isSidechain = serverMsg.isSidechain,
        )
        // The server-derived key (localSeq=null) is what a replayed server line would carry.
        reconciledServerKeys.add("${serverMsg.uuid}|user|${serverMsg.text.hashCode()}")
        _items.value = itemMap.values.toList()
    }

    /** UC-45 — trim + collapse internal whitespace, for tolerant text-gated reconcile (AC5). */
    private fun normalizeText(s: String): String = s.trim().replace(Regex("\\s+"), " ")

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
            // UC-45 — drop optimistic-echo bookkeeping in lockstep with the item store, so a
            // target switch can't reconcile a new target's echo against a stale pending bubble.
            pendingEchoes.clear()
            reconciledServerKeys.clear()
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
                        clearAnswerWatchdog() // UC-75 — a disconnect ends the turn locally; drop the safety-net
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
                // UC-71 — this give-up branch only fires under an injected finite
                // retry budget; with the unlimited default ctor it is unreachable.
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

        /**
         * UC-65 — how long the post-`/clear` suppression guard stays armed before auto-lifting.
         * Covers the `/clear` command echo + any in-flight pre-clear frames settling. A heuristic
         * upper bound; the guard also lifts immediately on the next user submit. Tunable.
         */
        private const val CLEAR_SUPPRESS_MS = 1_500L

        /**
         * UC-65 — gap between the interrupt and the `/clear` send when a question/plan sheet was
         * open at clear time, giving the harness a moment to settle out of the blocking turn before
         * the slash command lands. A heuristic; tunable.
         */
        private const val CLEAR_INTERRUPT_GAP_MS = 150L

        /**
         * UC-75 — default spinner safety-net timeout. Conservative on purpose: long enough that a
         * slow-but-valid answer (the harness is still processing) is NOT flipped to IDLE early, but
         * bounded so a declined/failed answer with no forward frame can't pin the spinner forever.
         * Event-driven recovery (forward frame / `pending-clear`) is preferred; this is the fallback.
         */
        internal const val ANSWER_WATCHDOG_MS = 45_000L

        /**
         * UC-75 — frame types that prove the turn is advancing after an answer was submitted; any of
         * them disarms the spinner safety-net so the watchdog can never flip a working turn to IDLE.
         */
        private val ANSWER_PROGRESS_FRAMES = setOf(
            "turn-start", "thinking", "assistant-text", "tool-use", "tool-result", "turn-end", "backfill-start",
        )
    }
}
