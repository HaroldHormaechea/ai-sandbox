package com.aisandbox.android.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * [X509TrustManager] that performs the production SPKI pin check INSIDE
 * {@link #checkServerTrusted}, bypassing OkHttp's
 * {@code CertificatePinner} + {@code BasicCertificateChainCleaner} path
 * entirely.
 *
 * <p>UC10 § AC1 — replaces the {@code lenient-TrustManager +
 * CertificatePinner} pair that triggered the OkHttp 5.3.2 chain-cleaning
 * bug:
 *
 * <ol>
 *   <li>The lenient TM returns empty {@code getAcceptedIssuers()}.</li>
 *   <li>{@code BasicCertificateChainCleaner.clean()} can't find a trusted
 *       cert that signed the chain → throws
 *       {@code SSLPeerUnverifiedException("Failed to find a trusted cert
 *       that signed …")}.</li>
 *   <li>{@code Handshake.peerCertificates_delegate} swallows the
 *       exception → exposes an empty peer-cert list.</li>
 *   <li>{@code CertificatePinner.check()} iterates the empty chain →
 *       unconditional {@code "Certificate pinning failure!"} regardless
 *       of whether the real pin would have matched.</li>
 * </ol>
 *
 * <p>The TM-based check sidesteps the cleaner entirely: the SPKI digest
 * is computed against the FIRST entry of the chain OkHttp hands to the
 * trust manager, which is the leaf the server actually presented. No
 * cleaning, no `getAcceptedIssuers` round-trip, no silent swallowing.
 *
 * <p><b>On mismatch:</b> throws {@link CertificateException} with the
 * fixed-shape detail message
 *
 * <pre>SPKI pin mismatch: expected=&lt;hex&gt; observed=&lt;hex&gt;</pre>
 *
 * where both hexes are 64-char lowercase SHA-256 digests. This is the
 * producer half of the
 * {@link TlsFailureTranslation#extractObservedSpkiHex} contract — the
 * consumer parses the {@code observed=} group with a fixed regex to
 * fill {@link NetworkEvent.PinMismatch#observedPinHex}.
 *
 * <p><b>Constant-time comparison</b> via {@link MessageDigest#isEqual} is
 * mandatory per UC10's pitfalls section — {@code java.util.Arrays.equals}
 * would leak timing.
 *
 * <p><b>{@link #getAcceptedIssuers}</b> returns an empty array
 * intentionally. The cleaner is no longer on the verification path, so
 * the OkHttp 5.3.2 bug is unreachable — but to be defensive,
 * {@link #checkClientTrusted} is a no-op (the Android client cert is
 * supplied via the {@code KeyManager} side of the {@code SSLContext},
 * never validated by the trust manager).
 *
 * <p>Phase 2a (UC10 test-first cascade) lands this class WITHOUT yet
 * wiring it into {@code EnrollmentClient} or {@code AiSandboxHttpClient}
 * — that wiring is Phase 2b, after QA has landed the failing tests
 * that the wiring will turn green.
 */
class SpkiPinningTrustManager(private val expectedSpki: ByteArray) : X509TrustManager {

    init {
        require(expectedSpki.size == SHA256_DIGEST_LENGTH) {
            "expected SPKI must be a $SHA256_DIGEST_LENGTH-byte SHA-256 digest, got ${expectedSpki.size}"
        }
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server presented no certificate chain")
        }
        val leaf = chain[0]
        val observed = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (!MessageDigest.isEqual(expectedSpki, observed)) {
            // Producer half of the SpkiPinningTrustManager → TlsFailureTranslation
            // contract. Detail message MUST start with the literal
            // "SPKI pin mismatch:" and contain both 64-char hex groups —
            // a unit test (added by QA in Phase 3) pins the exact format.
            val expectedHex = HexCodec.bytesToHex(expectedSpki)
            val observedHex = HexCodec.bytesToHex(observed)
            throw CertificateException("SPKI pin mismatch: expected=$expectedHex observed=$observedHex")
        }
    }

    /**
     * No-op. The client cert (when present) is supplied by the app via the
     * {@code KeyManager} side of the {@code SSLContext} init; the trust
     * manager never validates the local identity.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // intentional no-op
    }

    /**
     * Empty by design — the OkHttp chain-cleaner is no longer on the
     * verification path, so the historical
     * {@code getAcceptedIssuers().isEmpty()} hazard (UC10's root cause)
     * is unreachable. The pin check inside {@link #checkServerTrusted}
     * is the sole authority.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = EMPTY_ISSUERS

    companion object {
        private const val SHA256_DIGEST_LENGTH = 32
        private val EMPTY_ISSUERS = emptyArray<X509Certificate>()
    }
}
