package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.conversation.ConversationController
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    fun submitComposer(text: String) = controller?.submitComposer(text) ?: Unit

    fun submitAnswer(questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) =
        controller?.submitAnswer(questionUuid, questionIndex, selections, freeText) ?: Unit

    fun selectTarget(targetId: String) = controller?.selectTarget(targetId) ?: Unit

    fun interrupt() = controller?.interrupt() ?: Unit

    fun userTriggeredReconnect() = controller?.userTriggeredReconnect() ?: Unit

    /** Disconnect: tear down the conversation stream; the screen navigates back after. */
    fun disconnect() {
        controller?.close("user-disconnect")
        controller = null
        sessionN = -1
    }
}
