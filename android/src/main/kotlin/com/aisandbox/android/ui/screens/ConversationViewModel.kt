package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.conversation.ConversationController
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.conversation.ToolDetailState
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.net.ApiResult
import com.aisandbox.android.net.ModelInfo
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.settings.AppearanceSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UC-37 — thin adapter over the process-scoped [ConversationController] (mirrors
 * [TerminalViewModel]'s relationship to [TerminalStreamController]). Re-publishes
 * the controller's flows into stable VM-owned flows so the screen's collectors
 * survive controller swaps, and forwards input. [onCleared] does NOT close the
 * conversation — the controller is process-scoped so the view survives
 * back-navigation (AC22); explicit teardown is the screen's Disconnect action.
 */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container

    private var sessionN: Int = -1
    private var controller: ConversationController? = null

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

    // UC-78 — mirrors the controller's replay/backfill phase so the screen can anchor
    // the conversation to the bottom instantly (no animation) while history replays.
    private val _backfilling = MutableStateFlow(false)
    val backfilling: StateFlow<Boolean> = _backfilling.asStateFlow()

    private val _toolDetail = MutableStateFlow<ToolDetailState?>(null)
    val toolDetail: StateFlow<ToolDetailState?> = _toolDetail.asStateFlow()

    /**
     * UC-66 — state of the model-selection dialog: the catalogue fetch from
     * `GET /v1/models` (loading / empty / error / loaded). [Idle] is the closed
     * state before the first [loadModels]. The screen opens the dialog and calls
     * [loadModels] when the "Model" overflow item is tapped.
     */
    private val _modelMenu = MutableStateFlow<ModelMenuState>(ModelMenuState.Idle)
    val modelMenu: StateFlow<ModelMenuState> = _modelMenu.asStateFlow()

    /**
     * UC-66 (AC5) — the model id last selected for the current target, mirrored from the
     * controller so the dialog can highlight the matching row. Best-effort, client-side
     * last-selection (the harness doesn't surface the active model).
     */
    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    /**
     * UC-53 (AC2) — the conversation-view font scale, projected live from the
     * process-scoped [AppearanceSettingsStore]. Independent of [attach]/the
     * controller — it tracks the user's preference, not the stream — so a change
     * applies the moment it's persisted (AC6).
     */
    val fontScale: StateFlow<Float> =
        container.appearanceSettings.fontSize
            .map { it.scale }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                AppearanceSettingsStore.DEFAULT_FONT_SIZE.scale,
            )

    /**
     * UC-53 (AC3) — whether assistant bubbles render with a subtle agent-color
     * tint, projected live from the [AppearanceSettingsStore].
     */
    val useAgentColor: StateFlow<Boolean> =
        container.appearanceSettings.useAgentColorInBubbles
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                AppearanceSettingsStore.DEFAULT_USE_AGENT_COLOR,
            )

    /** Resolve the controller for [sessionN] and (idempotently) start its loop. */
    fun attach(sessionN: Int) {
        if (this.sessionN == sessionN && controller != null) {
            controller?.attach(sessionN)
            return
        }
        this.sessionN = sessionN
        val c = container.conversationController(sessionN)
        controller = c
        c.attach(sessionN)
        viewModelScope.launch { c.state.collect { _state.value = it } }
        viewModelScope.launch { c.items.collect { _items.value = it } }
        viewModelScope.launch { c.targets.collect { _targets.value = it } }
        viewModelScope.launch { c.selectedTargetId.collect { _selectedTargetId.value = it } }
        viewModelScope.launch { c.pendingSheet.collect { _pendingSheet.value = it } }
        viewModelScope.launch { c.turnPhase.collect { _turnPhase.value = it } }
        viewModelScope.launch { c.backfilling.collect { _backfilling.value = it } }
        viewModelScope.launch { c.toolDetail.collect { _toolDetail.value = it } }
        viewModelScope.launch { c.selectedModelId.collect { _selectedModelId.value = it } }
    }

    fun submitComposer(text: String) = controller?.submitComposer(text) ?: Unit

    fun submitAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) =
        controller?.submitAnswer(questionUuid, questionIndex, selections, freeText) ?: Unit

    /** UC-43 — forward a multi-question batch submit to the controller. */
    fun submitAnswerBatch(questionUuid: String, items: List<com.aisandbox.android.conversation.AnswerItem>) =
        controller?.submitAnswerBatch(questionUuid, items) ?: Unit

    fun selectTarget(targetId: String) = controller?.selectTarget(targetId) ?: Unit

    fun interrupt() = controller?.interrupt() ?: Unit

    /** UC-65 — send `/clear` and wipe the in-app transcript in place (the stream stays connected). */
    fun clear() = controller?.clear() ?: Unit

    /** UC-41 (AC5) — open the detail dialog for a tapped tool bubble. */
    fun openDetail(toolUseId: String, uuid: String) = controller?.openDetail(toolUseId, uuid) ?: Unit

    /** UC-41 — dismiss the detail dialog. */
    fun closeDetail() = controller?.closeDetail() ?: Unit

    /**
     * UC-66 — fetch the server's model catalogue for the dialog. Publishes
     * [ModelMenuState.Loading] immediately, then [Loaded] / [Empty] / [Error]
     * from `GET /v1/models`. A null profile (not enrolled) or any transport /
     * HTTP failure surfaces as [ModelMenuState.Error] so the dialog can render a
     * clear message rather than spinning forever.
     */
    fun loadModels() {
        _modelMenu.value = ModelMenuState.Loading
        viewModelScope.launch {
            val profile = container.profileStore.current()
            if (profile == null) {
                _modelMenu.value = ModelMenuState.Error("Not enrolled")
                return@launch
            }
            val result = try {
                container.modelsApi(container.httpClient(profile)).list()
            } catch (t: Throwable) {
                _modelMenu.value = ModelMenuState.Error(t.message ?: t.javaClass.simpleName)
                return@launch
            }
            _modelMenu.value = when (result) {
                is ApiResult.Success ->
                    if (result.value.isEmpty()) ModelMenuState.Empty
                    else ModelMenuState.Loaded(result.value)
                is ApiResult.HttpFailure ->
                    ModelMenuState.Error(result.detail.ifBlank { result.code })
            }
        }
    }

    /** UC-66 — apply a model choice to the current target (sends `/model <id>`). */
    fun selectModel(id: String) = controller?.selectModel(id) ?: Unit

    /** UC-66 — close the model dialog and reset its state. */
    fun dismissModelMenu() {
        _modelMenu.value = ModelMenuState.Idle
    }

    fun userTriggeredReconnect() = controller?.userTriggeredReconnect() ?: Unit

    /** Disconnect: tear down the conversation stream; the screen navigates back after. */
    fun disconnect() {
        controller?.close("user-disconnect")
        controller = null
        sessionN = -1
    }
}

/**
 * UC-66 — state of the model-selection dialog's catalogue fetch.
 *
 * - [Idle] — dialog closed (pre-fetch / dismissed).
 * - [Loading] — `GET /v1/models` in flight; the dialog shows a spinner.
 * - [Loaded] — the server's catalogue (one or more models).
 * - [Empty] — the server returned an empty catalogue.
 * - [Error] — transport / HTTP / not-enrolled failure, with a display [message].
 */
sealed interface ModelMenuState {
    data object Idle : ModelMenuState
    data object Loading : ModelMenuState
    data class Loaded(val models: List<ModelInfo>) : ModelMenuState
    data object Empty : ModelMenuState
    data class Error(val message: String) : ModelMenuState
}
