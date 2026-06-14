package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UC-69 — process-scoped, consume-once carrier for a pending deep-link target
 * (the session id a tapped pending-question notification wants to open).
 *
 * <p>Deliberately a value-holding [StateFlow] (not a {@code replay = 0}
 * [kotlinx.coroutines.flow.SharedFlow]): the deep link must survive the window
 * between a cold-start tap and the moment [com.aisandbox.android.ui.AiSandboxApp]
 * has settled its start destination and is ready to navigate. A replay-0 hot
 * flow would drop the value emitted by {@code MainActivity.onCreate} before the
 * nav graph subscribes; a StateFlow latches it until the collector is ready,
 * then [consume] clears it so a configuration change cannot re-navigate.
 *
 * <p>[request] is called from {@code MainActivity} (cold start {@code onCreate}
 * and warm {@code onNewIntent}); the app shell collects [pendingSession], routes
 * to the conversation once the start destination is decided AND is the sessions
 * list (an un-enrolled cold start has nowhere to deep-link to), then calls
 * [consume].
 */
class DeepLinkEvents {

    private val _pendingSession = MutableStateFlow<Int?>(null)

    /** The session id to deep-link to, or null when there is nothing pending. */
    val pendingSession: StateFlow<Int?> = _pendingSession.asStateFlow()

    /** Record a deep-link request for session [n] (latched until consumed). */
    fun request(n: Int) {
        _pendingSession.value = n
    }

    /** Clear the pending request once it has been acted on (or deliberately dropped). */
    fun consume() {
        _pendingSession.value = null
    }
}
