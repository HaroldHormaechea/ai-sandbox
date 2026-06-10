package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Single OkHttp instance the Android app uses for ALL traffic to the
 * configured server (UC04 AC7, AC18).
 *
 * <p>Two TLS guarantees:
 *
 * <ol>
 *   <li><b>mTLS identity</b> — the client cert + key live in the Android
 *       KeyStore. [KeyStoreIdentityManager.keyManagerFactory] wires the
 *       SSLContext with a hardware-backed [javax.net.ssl.X509KeyManager]
 *       so the private key never leaves the keystore. UC10 leaves this
 *       half UNTOUCHED — the only change is on the trust-manager side.</li>
 *   <li><b>Server pinning via [SpkiPinningTrustManager]</b> — UC10
 *       replaces the pre-UC10 {@code lenient TrustManager +
 *       okhttp3.CertificatePinner} pair with a single trust manager
 *       that performs the SPKI check inside {@code checkServerTrusted}.
 *       That sidesteps OkHttp 5.3.2's
 *       {@code BasicCertificateChainCleaner} trap (empty
 *       {@code getAcceptedIssuers()} → cleaner throws →
 *       {@code Handshake.peerCertificates_delegate} swallows →
 *       {@code CertificatePinner} iterates an empty chain → unconditional
 *       "Certificate pinning failure!" regardless of the real pin).</li>
 * </ol>
 *
 * <p>UC10 § AC4 — exception dispatch is structural (by class) via
 * [TlsFailureTranslation.translate]: SPKI mismatch routes to
 * [NetworkEvent.PinMismatch] with the REAL observed cert SPKI hex
 * (lifted from the structured [SpkiPinningTrustManager] exception);
 * hostname / SAN mismatch routes to [NetworkEvent.HostnameMismatch];
 * everything else routes to [NetworkEvent.HandshakeError]. The
 * pre-UC10 {@code extractObservedPin} message-prefix parser, the
 * lenient TM, and the {@code pinObservingInterceptor} are all gone.
 *
 * <p>The OkHttp client is intentionally short-lived: re-scanning a QR
 * builds a new client. The expensive bit is the SSLContext init, not
 * the client itself.
 */
class AiSandboxHttpClient(
    private val profile: ServerProfile,
    private val identity: KeyStoreIdentityManager,
) {

    val client: OkHttpClient by lazy { build() }

    /** Convenience base-URL accessor; trailing slash stripped for path concat. */
    val baseUrl: String = profile.serverUrl.trimEnd('/')

    private fun build(): OkHttpClient {
        // One trust manager instance — passed both to SSLContext.init and
        // to OkHttp's sslSocketFactory(.., TrustManager) overload (OkHttp
        // uses it to build chain-cleaning structures, but since the
        // pinning decision happens BEFORE the cleaner runs and our TM
        // accepts/rejects in checkServerTrusted, the cleaner is no longer
        // on the security-critical path).
        val trustManager = SpkiPinningTrustManager(HexCodec.hexToBytes(profile.pinSha256Hex))
        val sslContext = buildSslContext(trustManager)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // No CertificatePinner — SpkiPinningTrustManager performs
            // the pin check inside checkServerTrusted, so the cleaner
            // + CertificatePinner path is no longer used. Default
            // HostnameVerifier is left untouched so SAN matching still
            // fires after the TM accepts.
            .addInterceptor(tlsFailureTranslatingInterceptor())
            // ai-sandbox.v1 subprotocol is enforced by StreamClient on
            // the WebSocket; REST has no subprotocol.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Keep the WS happy; REST ignores ping.
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun buildSslContext(trustManager: SpkiPinningTrustManager): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(
            identity.keyManagerFactory().keyManagers,
            arrayOf(trustManager),
            SecureRandom(),
        )
        return ctx
    }

    /**
     * UC10 § AC4 — translate TLS-layer exceptions into structured
     * [NetworkEvent] variants and re-throw so the call still fails.
     * Replaces the pre-UC10 [pinObservingInterceptor] which lifted the
     * observed pin from OkHttp's pin-mismatch message text (a parser
     * that returned {@code <unknown>} on the chain-cleaning-trap path).
     */
    private fun tlsFailureTranslatingInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            chain.proceed(request)
        } catch (t: Throwable) {
            val event = TlsFailureTranslation.translate(
                throwable = t,
                expectedPinHex = profile.pinSha256Hex,
                expectedHost = request.url.host,
            )
            // UC-52 — only bus-route GENUINE identity/TLS events. A transient
            // ServerUnreachable is consumed at the call site (sessions banner /
            // onboarding inline failure), NEVER on the global bus, so a momentary
            // connectivity drop can't force-route to ServerIdentityChangedScreen
            // (AC6). We still re-throw t so the call fails and the call site's
            // catch can surface the retryable state.
            if (event != null && event !is NetworkEvent.ServerUnreachable) {
                NetworkEvents.tryEmit(event)
            }
            throw t
        }
    }
}
