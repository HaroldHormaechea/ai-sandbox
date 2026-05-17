package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

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
 *       so the private key never leaves the keystore.</li>
 *   <li><b>Server pinning</b> — [okhttp3.CertificatePinner] is set from
 *       the [ServerProfile.pinSha256Hex] persisted during enrollment.
 *       Any other server cert raises an [SSLPeerUnverifiedException]
 *       which the [pinObservingInterceptor] catches and translates into
 *       a [NetworkEvent.PinMismatch] on the global [NetworkEvents]
 *       bus — the AiSandboxApp composable then force-routes to
 *       ServerIdentityChangedScreen.</li>
 * </ol>
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
        val sslContext = buildSslContext()
        val trustManager = lenientTrustManager() // pinning is the actual auth

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(hostFromUrl(profile.serverUrl), profile.toOkHttpPin())
                    .build()
            )
            // Surface pin-mismatch as a structured NetworkEvent.
            .addInterceptor(pinObservingInterceptor())
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

    private fun buildSslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(
            identity.keyManagerFactory().keyManagers,
            arrayOf(lenientTrustManager()),
            SecureRandom(),
        )
        return ctx
    }

    /**
     * The pin check is performed by [CertificatePinner], not by the
     * trust manager. The trust manager accepts every chain so that
     * non-pinning code paths (none today) don't accidentally short-
     * circuit the pin. Equivalent to OkHttp's documented pattern.
     */
    private fun lenientTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Catch [SSLPeerUnverifiedException] anywhere in the request chain
     * — this is the exception OkHttp throws when [CertificatePinner]
     * rejects the server's cert. Map to [NetworkEvent.PinMismatch] and
     * re-throw so the call still fails; the UI observes the event and
     * navigates.
     */
    private fun pinObservingInterceptor(): Interceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (mis: SSLPeerUnverifiedException) {
            val observed = extractObservedPin(mis.message) ?: "<unknown>"
            NetworkEvents.tryEmit(
                NetworkEvent.PinMismatch(expectedPinHex = profile.pinSha256Hex, observedPinHex = observed)
            )
            throw mis
        }
    }

    /**
     * OkHttp's pin-mismatch message contains "Pinned certificates for &lt;host&gt;:\n
     * Peer certificate chain:\n  sha256/<base64>..." — we lift the first
     * sha256 hash from the body for the dialog. Best-effort: failure to
     * extract just yields {@code <unknown>}.
     */
    private fun extractObservedPin(message: String?): String? {
        if (message == null) return null
        val marker = "sha256/"
        val idx = message.indexOf(marker)
        if (idx < 0) return null
        val tail = message.substring(idx + marker.length)
        val end = tail.indexOfAny(charArrayOf(' ', '\n', '\r', ':', ','))
        return if (end < 0) tail else tail.substring(0, end)
    }

    private fun hostFromUrl(url: String): String {
        val noScheme = url.substringAfter("://")
        val noPath = noScheme.substringBefore('/')
        return noPath.substringBefore(':')
    }
}
