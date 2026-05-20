package com.aisandbox.android.net

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Discriminator that translates a TLS-layer exception thrown from an
 * OkHttp call into a structured [NetworkEvent].
 *
 * <p>UC10 § AC4 — the three failure modes are distinguishable by
 * exception CLASS, not by fragile message-prefix matching:
 *
 * <ul>
 *   <li>{@link SSLHandshakeException} whose cause chain contains a
 *       {@link CertificateException} with the
 *       {@link SpkiPinningTrustManager}-emitted
 *       {@code "SPKI pin mismatch: expected=… observed=…"} message
 *       → {@link NetworkEvent.PinMismatch} with the REAL observed
 *       SPKI hex (no more {@code <bootstrap>} sentinel).</li>
 *   <li>{@link SSLPeerUnverifiedException} (Android's default
 *       {@code OkHostnameVerifier} fires AFTER the trust manager
 *       — by the time we see this class, the SPKI pin already
 *       matched; the host just isn't in the cert's SAN)
 *       → {@link NetworkEvent.HostnameMismatch}.</li>
 *   <li>Any other {@link SSLException} / {@link IOException}
 *       → {@link NetworkEvent.HandshakeError}.</li>
 * </ul>
 *
 * <p>The [Mismatch] sealed type below is the parameter shape
 * {@code ServerIdentityChangedScreen} consumes — three variants that
 * mirror the three [NetworkEvent] error kinds, decoupled from the
 * global event bus so the screen can be exercised in isolation by
 * Compose tests.
 *
 * <p>Phase 2a (UC10 test-first cascade) lands this file WITHOUT yet
 * invoking [translate] from {@code EnrollmentClient} /
 * {@code AiSandboxHttpClient}. Phase 2b wires it in.
 */
sealed interface Mismatch {

    /**
     * SPKI pin mismatch — the server presented a cert whose public
     * key hash differs from the QR-time pin we persisted.
     */
    data class Pin(
        val expectedHex: String,
        val observedHex: String,
        val rawMessage: String,
    ) : Mismatch

    /**
     * Hostname / SAN mismatch — pin verified successfully, but the
     * request URL's host is not in the cert's SAN list. Reachable
     * for real SAN misconfiguration on the server side.
     */
    data class Hostname(
        val expectedHost: String,
        val rawMessage: String,
    ) : Mismatch

    /**
     * Any other TLS handshake / I/O failure surfaced to the screen
     * via the [NetworkEvent.HandshakeError] route.
     */
    data class HandshakeError(val rawMessage: String) : Mismatch
}

object TlsFailureTranslation {

    /**
     * Producer-consumer regex pinned to the exact message emitted by
     * {@link SpkiPinningTrustManager}. Both endpoints are in-repo;
     * a unit test (QA scope) pins the format so accidental drift
     * surfaces immediately.
     */
    private val SPKI_MISMATCH_REGEX: Regex =
        Regex("expected=([0-9a-f]{64}) observed=([0-9a-f]{64})")

    /**
     * Walk the cause chain to find a {@link CertificateException} whose
     * message matches the [SpkiPinningTrustManager] format. Returns the
     * observed SPKI hex on match, or {@code null} otherwise.
     *
     * <p>Iterative walk (no recursion) and an internal depth cap protect
     * against pathological / circular cause chains — should never happen
     * in practice but the guard is cheap.
     */
    fun extractObservedSpkiHex(throwable: Throwable?): String? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is CertificateException) {
                val msg = current.message ?: ""
                val match = SPKI_MISMATCH_REGEX.find(msg)
                if (match != null) {
                    return match.groupValues[2]
                }
            }
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * Translate a TLS-layer throwable from an OkHttp call into the
     * corresponding {@link NetworkEvent} variant.
     *
     * @param throwable       the exception caught around an OkHttp call
     * @param expectedPinHex  the SPKI hex from the persisted profile or QR
     *                        payload, used to populate the resulting event
     * @param expectedHost    the host portion of the request URL, used
     *                        in {@link NetworkEvent.HostnameMismatch}
     * @return the structured event, or {@code null} if {@code throwable}
     *         is not a TLS-layer error this translator handles (the
     *         caller falls back to its own generic IOException path).
     */
    fun translate(
        throwable: Throwable,
        expectedPinHex: String,
        expectedHost: String,
    ): NetworkEvent? {
        val rawMessage = throwable.message ?: throwable.javaClass.simpleName
        return when (throwable) {
            is SSLHandshakeException -> {
                val observedHex = extractObservedSpkiHex(throwable)
                if (observedHex != null) {
                    NetworkEvent.PinMismatch(
                        expectedPinHex = expectedPinHex,
                        observedPinHex = observedHex,
                        rawMessage = rawMessage,
                    )
                } else {
                    NetworkEvent.HandshakeError(rawMessage = rawMessage)
                }
            }
            is SSLPeerUnverifiedException -> NetworkEvent.HostnameMismatch(
                expectedHost = expectedHost,
                rawMessage = rawMessage,
            )
            is SSLException -> NetworkEvent.HandshakeError(rawMessage = rawMessage)
            is IOException -> NetworkEvent.HandshakeError(rawMessage = rawMessage)
            else -> null
        }
    }

    /**
     * Convert a [NetworkEvent] error variant into the screen-facing
     * [Mismatch] payload. Returns {@code null} for non-error variants
     * (StreamReconnecting, StreamGaveUp, CertRevoked) — the caller is
     * expected to filter to error variants before invoking.
     */
    fun toMismatch(event: NetworkEvent): Mismatch? = when (event) {
        is NetworkEvent.PinMismatch -> Mismatch.Pin(
            expectedHex = event.expectedPinHex,
            observedHex = event.observedPinHex,
            rawMessage = event.rawMessage,
        )
        is NetworkEvent.HostnameMismatch -> Mismatch.Hostname(
            expectedHost = event.expectedHost,
            rawMessage = event.rawMessage,
        )
        is NetworkEvent.HandshakeError -> Mismatch.HandshakeError(
            rawMessage = event.rawMessage,
        )
        NetworkEvent.CertRevoked,
        is NetworkEvent.StreamReconnecting,
        is NetworkEvent.StreamGaveUp,
        -> null
    }

    private const val MAX_CAUSE_DEPTH = 32
}
