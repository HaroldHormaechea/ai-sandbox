package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-88 — **the meaningful regression**: under a half-open / stalled transport,
 * repeatedly opening and closing WebSocket clients must NOT accumulate lingering
 * OkHttp sockets past the server's per-fingerprint cap.
 *
 * <h2>Root cause this reproduces</h2>
 * The analyst measured the live subscription count climb 8 → 17 → 29 (past the
 * cap of 20) on repeated chat→list under a half-open link, because a graceful
 * {@link okhttp3.WebSocket#close} alone lets an in-flight / half-open socket
 * linger 30–60 s (connect/read timeout + okhttp's `cancelAfterCloseMillis`)
 * while the reconnect loop replenishes faster than they drain. The fix has each
 * client's [close] also call {@link okhttp3.WebSocket#cancel}, which aborts the
 * in-flight connect in ~0 ms.
 *
 * <h2>How the test makes that observable (git-free A/B)</h2>
 * QA cannot revert production to demonstrate the pre-fix failure, so the precursor
 * is reproduced and the fix is proven in a single test against one stalling
 * server, measuring the client's live OkHttp call count
 * ([okhttp3.Dispatcher.runningCallsCount]) — the direct proxy for "live sockets
 * the client is holding", i.e. the subscriptions the server sees:
 *
 * <ul>
 *   <li><b>Arm A — precursor (graceful close only, no cancel)</b>: open N raw
 *       sockets against the stalling server and close each with
 *       {@code webSocket.close(1000, …)} ONLY. They LINGER — the live-call count
 *       stays at ≈ N (past the cap). This is exactly the pre-fix mechanism.</li>
 *   <li><b>Arm B — the fix (production [close], which also cancels)</b>: open N
 *       [SessionEventsClient]s the same way and close each via the production
 *       {@code close()}. The live-call count drains back to ≈ 0 within a bounded
 *       window. On the pre-fix code this arm would behave like Arm A and the
 *       drain assertion would TIME OUT — so this arm is itself a pre-fix/post-fix
 *       discriminator.</li>
 * </ul>
 *
 * <p>The transport stalls the WebSocket UPGRADE ({@link SocketPolicy#NO_RESPONSE}
 * — TLS + pin succeed, the GET is sent, no 101 ever comes), so the sockets are
 * genuinely half-open / in-flight. The client's read timeout is 30 s, far longer
 * than the few-second test window, so a lingering (un-cancelled) socket is still
 * counted as live throughout the test.
 */
class WsReconnectAccumulationTest {

    /** Cap the analyst measured the live count blow past (server per-fingerprint cap). */
    private val cap = 20

    /** Cycles to drive — chosen > [cap] so accumulation is unambiguously past it. */
    private val cycles = 24

    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile

    @BeforeEach
    fun setUp() {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            // Stall EVERY request: accept the TCP/TLS connection, read the upgrade
            // GET, then never respond — a half-open / in-flight WebSocket handshake.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val pinHex = MessageDigest.getInstance("SHA-256")
            .digest(cert.certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            pinSha256Hex = pinHex,
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
    }

    @AfterEach
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // best-effort — stalled sockets are cancelled per-arm below
        }
    }

    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    private fun newHttp(): AiSandboxHttpClient = AiSandboxHttpClient(profile, fakeIdentity())

    /** Poll [probe] until it satisfies [pred] or [timeoutMs] elapses; return the last value. */
    private fun pollUntil(timeoutMs: Long, pred: (Int) -> Boolean, probe: () -> Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var v = probe()
        while (!pred(v) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            v = probe()
        }
        return v
    }

    private fun drainClient(c: OkHttpClient) {
        c.dispatcher.cancelAll()
        c.dispatcher.executorService.shutdownNow()
        c.connectionPool.evictAll()
    }

    @Test
    fun `production close cancels half-open sockets so they do not accumulate past the cap`() = runBlocking {
        // ── Arm A — PRECURSOR: graceful close only (no cancel) lingers ────────
        // This is the pre-fix mechanism reproduced directly with raw OkHttp.
        val httpA = newHttp()
        val rawSockets = mutableListOf<WebSocket>()
        repeat(cycles) {
            val req = Request.Builder()
                .url("${profile.serverUrl.replace("https://", "wss://")}/v1/sessions/events")
                .header("Sec-WebSocket-Protocol", SessionEventsClient.SUBPROTOCOL)
                .build()
            rawSockets += httpA.client.newWebSocket(req, object : WebSocketListener() {})
        }
        // All N upgrades are in flight (half-open) — live calls climb to ≈ N.
        val peakA = pollUntil(15_000, { it >= cycles }) { httpA.client.dispatcher.runningCallsCount() }
        // Close each GRACEFULLY ONLY — the pre-fix close() path (no cancel()).
        rawSockets.forEach { it.close(SessionEventsClient.NORMAL_CLOSE_CODE, "reconnect") }
        // Give the graceful close the same settle budget Arm B gets; on a stalled
        // (half-open) socket close() cannot complete the handshake, so the call
        // keeps running until the 30 s read timeout — it LINGERS.
        val lingeringA = pollUntil(4_000, { it < cap }) { httpA.client.dispatcher.runningCallsCount() }

        assertThat(peakA)
            .`as`("all $cycles half-open upgrades are live at once (precursor blows past the cap of $cap)")
            .isGreaterThanOrEqualTo(cap)
        assertThat(lingeringA)
            .`as`("graceful close WITHOUT cancel leaves half-open sockets lingering — they accumulate past the cap of $cap")
            .isGreaterThanOrEqualTo(cap)

        drainClient(httpA.client)

        // ── Arm B — THE FIX: production close() also cancels → drains to baseline
        val httpB = newHttp()
        val clients = (0 until cycles).map { SessionEventsClient(httpB) }
        val jobs = mutableListOf<Job>()
        clients.forEach { c -> jobs += launch(Dispatchers.IO) { runCatching { c.connect() } } }

        // All N client upgrades are in flight (half-open) — live calls climb to ≈ N.
        val peakB = pollUntil(15_000, { it >= cycles }) { httpB.client.dispatcher.runningCallsCount() }
        assertThat(peakB)
            .`as`("the fix is not hiding accumulation by failing to open — all $cycles sockets are live first")
            .isGreaterThanOrEqualTo(cap)

        // Close each via the PRODUCTION close() (graceful close + cancel()).
        clients.forEach { it.close("reconnect") }

        // cancel() aborts each in-flight connect in ~0 ms, so the live-call count
        // drains back to baseline. Pre-fix (no cancel) this would stay at ≈ N for
        // the full 30 s read timeout and this poll would TIME OUT → assertion fails.
        val drainedB = pollUntil(10_000, { it <= 2 }) { httpB.client.dispatcher.runningCallsCount() }
        assertThat(drainedB)
            .`as`("production close() cancels the half-open socket so live calls return to baseline (no accumulation)")
            .isLessThanOrEqualTo(2)

        jobs.forEach { it.cancel() }
        drainClient(httpB.client)
    }
}
