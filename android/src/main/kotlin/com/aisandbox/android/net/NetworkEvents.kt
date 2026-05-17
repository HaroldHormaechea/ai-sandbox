package com.aisandbox.android.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus for "interesting" network events that the UI layer
 * needs to react to globally — chief among them, AC7 pin-mismatch
 * surfacing (UC04 § "ServerIdentityChangedScreen") and AC25 cert-revoke
 * (UC04-7 dialog).
 *
 * <p>Using a [SharedFlow] (replay = 0, extraBufferCapacity = 16) so the
 * UI receives every event without blocking the emitter even under
 * bursty reconnect storms. The [AiSandboxApp] composable subscribes
 * once at root level and force-routes via [navController] when a
 * relevant event fires.
 *
 * <p>This is a singleton — every [AiSandboxHttpClient] instance, every
 * [StreamClient], and every Compose ViewModel write to the same emitter.
 */
object NetworkEvents {

    private val _flow = MutableSharedFlow<NetworkEvent>(replay = 0, extraBufferCapacity = 16)

    val flow: SharedFlow<NetworkEvent> = _flow.asSharedFlow()

    suspend fun emit(event: NetworkEvent) {
        _flow.emit(event)
    }

    fun tryEmit(event: NetworkEvent): Boolean = _flow.tryEmit(event)
}

/**
 * Sealed event hierarchy — adding a new variant updates every
 * subscriber via exhaustive when (Kotlin doesn't enforce exhaustiveness
 * on expressions today, but the convention is to compile-error on the
 * subscriber side).
 */
sealed interface NetworkEvent {

    /**
     * AC7 — the server's TLS cert no longer matches the pin we stored
     * at enrollment. Routes to [ServerIdentityChangedScreen]. The
     * pinned hex and observed hex are surfaced for the "Quit / Scan
     * new QR" copy.
     */
    data class PinMismatch(val expectedPinHex: String, val observedPinHex: String) : NetworkEvent

    /**
     * AC25 — the server tore the WebSocket down with close code 4401
     * ("revoked") because the operator removed our cert from the
     * allowlist. Routes to the cert-revoked dialog (UC04-7).
     */
    data object CertRevoked : NetworkEvent

    /**
     * AC25 also includes a 5-min reconnect cap that ends in a manual
     * "Disconnected — tap to reconnect" terminal state. The terminal
     * screen consumes this; everyone else ignores.
     */
    data class StreamGaveUp(val streamId: String) : NetworkEvent

    /** Generic terminal-side reconnect ticker (1, 2, 4, 8, 16, 30 s). */
    data class StreamReconnecting(val streamId: String, val attempt: Int, val nextDelayMs: Long) : NetworkEvent
}
