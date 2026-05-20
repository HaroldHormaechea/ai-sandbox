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
     *
     * <p>UC10 § AC4 — [rawMessage] carries the unredacted exception
     * detail (the [SpkiPinningTrustManager]-emitted "SPKI pin mismatch:
     * expected=… observed=…" string, or whatever OkHttp wrapped it in)
     * so the screen's "Show technical details" expander can render it
     * verbatim for operator troubleshooting. Default empty preserves
     * source-compatibility with pre-UC10 call sites during the
     * test-first cascade (Phase 2a lands the field without yet
     * wiring the production catch blocks; Phase 2b passes the real
     * value via [TlsFailureTranslation.translate]).
     */
    data class PinMismatch(
        val expectedPinHex: String,
        val observedPinHex: String,
        val rawMessage: String = "",
    ) : NetworkEvent

    /**
     * UC10 § AC4 — the request URL's host is not in the server cert's
     * SubjectAlternativeName list. Android's default {@code OkHostnameVerifier}
     * fires AFTER the [SpkiPinningTrustManager], so by the time we see
     * this variant the SPKI pin already matched — only the SAN is wrong.
     * Routes to [ServerIdentityChangedScreen]'s "Hostname" variant
     * (Phase 2b will extend the screen with the `cause: Mismatch`
     * parameter per AC8).
     *
     * <p>Phase 2a lands this variant without yet emitting it; Phase 2b
     * wires [TlsFailureTranslation.translate] into the catch blocks of
     * [EnrollmentClient] and [AiSandboxHttpClient].
     */
    data class HostnameMismatch(
        val expectedHost: String,
        val rawMessage: String,
    ) : NetworkEvent

    /**
     * UC10 § AC4 — any TLS / handshake failure that is neither a pin
     * mismatch nor a hostname mismatch (connection reset, protocol
     * downgrade, malformed cert, generic I/O). Carries the raw
     * exception detail so the screen's diagnostics expander can render
     * it verbatim.
     *
     * <p>Phase 2a lands this variant without yet emitting it; Phase 2b
     * wires [TlsFailureTranslation.translate] into the catch blocks of
     * [EnrollmentClient] and [AiSandboxHttpClient].
     */
    data class HandshakeError(val rawMessage: String) : NetworkEvent

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
