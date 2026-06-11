package com.aisandbox.android.net

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
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
 *       SPKI hex lifted from the structured cause (no more legacy
 *       observed-pin sentinel).</li>
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
     * Shared raw exception detail — declared on the interface so the
     * screen's "Show technical details" expander can render it without
     * per-variant casting. Every concrete variant carries one.
     */
    val rawMessage: String

    /**
     * SPKI pin mismatch — the server presented a cert whose public
     * key hash differs from the QR-time pin we persisted.
     */
    data class Pin(
        val expectedHex: String,
        val observedHex: String,
        override val rawMessage: String,
    ) : Mismatch

    /**
     * Hostname / SAN mismatch — pin verified successfully, but the
     * request URL's host is not in the cert's SAN list. Reachable
     * for real SAN misconfiguration on the server side.
     */
    data class Hostname(
        val expectedHost: String,
        override val rawMessage: String,
    ) : Mismatch

    /**
     * Any other TLS handshake / I/O failure surfaced to the screen
     * via the [NetworkEvent.HandshakeError] route.
     */
    data class HandshakeError(override val rawMessage: String) : Mismatch
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
            // UC-56 — a BARE SSLException (neither a handshake nor a peer-
            // unverified subclass; those arms ran above) is no longer an
            // unconditional HandshakeError. Conscrypt wraps a mid-stream
            // transport drop in a plain SSLException ("Connection reset by
            // peer", "Read error", …), and the old unconditional mapping
            // force-routed that transient drop to the destructive identity
            // screen — the UC-56 conversation→list flicker loop. The arm is
            // now a two-step decision (identity-cause guard FIRST, then a
            // transient socket-drop check) — see [classifyBareSslException].
            is SSLException -> classifyBareSslException(throwable, rawMessage)
            // UC-52 — the SSL handshake / peer-unverified arms above run FIRST
            // and unchanged, so every genuine TLS identity failure (pin /
            // hostname / handshake) keeps its existing identity routing (AC4).
            // Only NON-TLS IOExceptions reach here. A plain connectivity
            // failure (ConnectException /
            // SocketTimeoutException / UnknownHostException / dropped socket)
            // is now a TRANSIENT ServerUnreachable, NOT a HandshakeError, so it
            // no longer force-routes to ServerIdentityChangedScreen (AC1, AC6).
            // SECURITY (AC4): an IOException / SocketException that CARRIES an
            // SSLException somewhere in its cause chain is forced back onto the
            // identity path via hasTlsCause() — when in doubt, identity wins.
            is IOException ->
                if (hasTlsCause(throwable)) {
                    NetworkEvent.HandshakeError(rawMessage = rawMessage)
                } else {
                    NetworkEvent.ServerUnreachable
                }
            else -> null
        }
    }

    /**
     * UC-52 — does [t]'s cause chain carry a TLS-layer failure? Used by
     * [translate] to keep a connectivity-shaped exception (e.g. a
     * {@code SocketException: Connection reset} or generic {@code IOException})
     * that actually WRAPS an {@link SSLException} on the identity path rather
     * than silently re-bucketing it as transient against a possibly-compromised
     * endpoint (the "when in doubt, identity wins" rule, AC4).
     *
     * <p>Iterative walk (no recursion) with the same [MAX_CAUSE_DEPTH] guard as
     * [extractObservedSpkiHex] against pathological / circular chains. This
     * predicates on {@link SSLException}; do NOT conflate it with
     * [extractObservedSpkiHex], which predicates on {@link CertificateException}
     * for the SPKI-pin message.
     */
    private fun hasTlsCause(t: Throwable?): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is SSLException) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * UC-56 — classify a BARE [SSLException] (one that is neither an
     * {@link SSLHandshakeException} nor an {@link SSLPeerUnverifiedException};
     * those subclasses are matched by earlier [translate] arms and keep their
     * identity routing). Two-step decision, in this strict order:
     *
     * <ol>
     *   <li><b>Identity-cause guard FIRST</b> — if the cause chain carries a
     *       genuine identity signal ({@link CertificateException},
     *       {@link SSLHandshakeException}, or {@link SSLPeerUnverifiedException}),
     *       return {@link NetworkEvent.HandshakeError} REGARDLESS of message.
     *       A real identity failure must never be reclassified as transient
     *       (AC5, "when in doubt, identity wins").</li>
     *   <li><b>Transient check</b> — see [isTransientTransportSslException]:
     *       a socket-level cause ({@link SocketException} /
     *       {@link ConnectException} / {@link SocketTimeoutException} /
     *       {@link EOFException} / Conscrypt's {@code ErrnoException}) is the
     *       PRIMARY signal; a narrow socket-drop message ("connection reset" /
     *       "connection closed" / …) is the SECONDARY signal. Either →
     *       {@link NetworkEvent.ServerUnreachable} (transient, never
     *       bus-routed; AC4).</li>
     *   <li><b>Default</b> — an unclassified bare TLS failure (protocol
     *       downgrade, unknown TLS error) stays on the identity path as
     *       {@link NetworkEvent.HandshakeError}.</li>
     * </ol>
     */
    private fun classifyBareSslException(throwable: Throwable, rawMessage: String): NetworkEvent =
        when {
            hasIdentityCause(throwable) -> NetworkEvent.HandshakeError(rawMessage = rawMessage)
            isTransientTransportSslException(throwable) -> NetworkEvent.ServerUnreachable
            else -> NetworkEvent.HandshakeError(rawMessage = rawMessage)
        }

    /**
     * UC-56 — is this bare [SSLException] really a TRANSIENT transport drop
     * rather than a TLS/identity failure? True iff the cause chain carries a
     * socket-level transport failure (PRIMARY signal — [hasSocketLevelCause])
     * OR, failing that, a narrow socket-drop message signature (SECONDARY
     * signal — [hasSocketDropMessage]). Callers MUST run the identity-cause
     * guard ([hasIdentityCause]) FIRST so a genuine identity failure wrapped in
     * a transport-shaped SSLException is never swept in here (AC5).
     */
    private fun isTransientTransportSslException(t: Throwable?): Boolean =
        hasSocketLevelCause(t) || hasSocketDropMessage(t)

    /**
     * UC-56 — does [t]'s cause chain carry a genuine TLS/identity signal?
     * Walks the chain (same [MAX_CAUSE_DEPTH] guard) looking for a
     * {@link CertificateException} (SPKI/cert problem), an
     * {@link SSLHandshakeException}, or an {@link SSLPeerUnverifiedException}.
     * A match means the bare SSLException is really an identity failure
     * wearing a transport-error coat, so it must route to the identity screen.
     */
    private fun hasIdentityCause(t: Throwable?): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is CertificateException ||
                current is SSLHandshakeException ||
                current is SSLPeerUnverifiedException
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * UC-56 — does [t]'s cause chain carry a socket-level transport failure?
     * The PRIMARY transient signal: Conscrypt typically wraps a dropped TCP
     * connection's {@link SocketException} (incl. its {@link ConnectException}
     * subclass), {@link SocketTimeoutException}, {@link EOFException}, or — on
     * the native path — an {@code android.system.ErrnoException} (e.g.
     * {@code recvfrom failed: ECONNRESET}) inside the bare SSLException.
     *
     * <p>{@code ErrnoException} is matched by simple class name rather than an
     * {@code is} check so this translator keeps ZERO {@code android.*} imports
     * and stays JVM-unit-testable (the {@code android.system.ErrnoException}
     * SDK stub throws on construction in unit tests). Same [MAX_CAUSE_DEPTH]
     * guard against pathological / circular chains.
     */
    private fun hasSocketLevelCause(t: Throwable?): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is SocketException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is EOFException ||
                current.javaClass.simpleName == "ErrnoException"
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * UC-56 — SECONDARY transient signal: a NARROW socket-drop message on the
     * bare SSLException (or anything in its chain) when no structured
     * socket-level cause is attached. Deliberately conservative — only the
     * well-known transport-drop phrasings, so a genuine but oddly-worded TLS
     * error is NOT swept into the transient bucket (it falls through to the
     * identity default). Case-insensitive substring match.
     */
    private fun hasSocketDropMessage(t: Throwable?): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val msg = current.message?.lowercase()
            if (msg != null && SOCKET_DROP_MESSAGES.any { msg.contains(it) }) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Convert a [NetworkEvent] error variant into the screen-facing
     * [Mismatch] payload. Returns {@code null} for non-error variants
     * (StreamReconnecting, StreamGaveUp, CertRevoked) and — UC-52 — for
     * the transient [NetworkEvent.ServerUnreachable] connectivity signal,
     * which must NEVER produce a Mismatch / identity screen (AC4): a
     * momentary network drop is a retryable banner, not a re-enroll
     * dead-end. The caller filters to error variants before invoking.
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
        NetworkEvent.ServerUnreachable,
        is NetworkEvent.StreamReconnecting,
        is NetworkEvent.StreamGaveUp,
        -> null
    }

    private const val MAX_CAUSE_DEPTH = 32

    /**
     * UC-56 — narrow allow-list of transport-drop message fragments used as the
     * SECONDARY transient signal by [hasSocketDropMessage] when a bare
     * SSLException carries no structured socket-level cause. Lowercased;
     * matched as substrings. Kept deliberately tight so a genuine TLS/identity
     * failure with an unusual message is NOT misclassified as transient.
     */
    private val SOCKET_DROP_MESSAGES: List<String> = listOf(
        "connection reset",
        "connection closed",
        "connection abort",
        "socket closed",
        "socket is closed",
        "broken pipe",
        "unexpected end of stream",
    )
}
