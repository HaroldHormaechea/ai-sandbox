package com.aisandbox.android.ui.screens

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * BUG 1 regression guard for [SessionsCoordinator] — the production
 * create / list / delete orchestration seam extracted from
 * SessionsViewModel.
 *
 * <h2>Why this drives the REAL client stack, not a fake</h2>
 *
 * <p>The coordinator is wired with a real [SessionsApi] bound (per
 * profile) to a real [AiSandboxHttpClient] (real OkHttp + real
 * SpkiPinningTrustManager + real kotlinx) pointed at a pinned-HTTPS
 * [MockWebServer]. Only the three Android touch points are injected:
 * the profile supplier, the api factory, and the coroutine scope. The
 * coordinator references {@code android.util.Log} only, which is a
 * no-op under {@code testOptions.unitTests.isReturnDefaultValues=true}
 * — no Robolectric.
 *
 * <h2>Why real-time {@code runBlocking}, not StandardTestDispatcher</h2>
 *
 * <p>{@link SessionsApi} hops to {@code Dispatchers.IO} internally
 * ({@code withContext(Dispatchers.IO)}) for every call, and that
 * dispatcher is NOT injectable. A virtual-time {@code TestScope} /
 * {@code advanceUntilIdle()} cannot observe work that ran on the real
 * IO dispatcher, so it would assert before the network round trip
 * completed. Instead the coordinator runs on a real
 * {@code Dispatchers.IO} scope and the test synchronises on natural
 * real barriers: {@code server.takeRequest(timeout)} (blocks until an
 * outbound request actually arrives) and {@code state.first { … }}
 * (suspends until the StateFlow reaches a terminal value). This is the
 * only reliable way to assert OUTBOUND DISPATCH for code that crosses a
 * non-injectable real dispatcher.
 *
 * <h2>THE non-negotiable assertion</h2>
 *
 * <p>{@link #spawn_dispatches_outbound_post_to_server} asserts that a
 * {@code POST /v1/sessions} actually LEFT the client (via
 * {@code server.takeRequest}), not merely that a success response was
 * handled. Pre-fix, {@code spawn()} sourced the profile from
 * {@code state.profile} (null whenever the initial list failed) and
 * short-circuited — NO POST was ever dispatched while the optimistic
 * "starting" row leaked. A happy-response-only assertion would miss
 * that silent non-dispatch entirely; asserting the outbound POST is
 * what catches BUG 1.
 *
 * <p>CI note: authored + reasoned for red-green; validated by CI's
 * {@code :android:test} (no Android SDK locally).
 */
class SessionsCoordinatorTest {

    // ── the live pinned-HTTPS fixture ────────────────────────────────────

    private class Fixture(val server: MockWebServer, val profile: ServerProfile) {
        fun shutdown() = server.shutdown()
    }

    private fun mockIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        return mock(KeyStoreIdentityManager::class.java).also {
            `when`(it.keyManagerFactory()).thenReturn(factory)
        }
    }

    /**
     * Start a pinned-HTTPS MockWebServer whose dispatcher answers the
     * three session verbs by shape:
     *  - GET /v1/sessions     → 200 [listBody]  (BARE array, the real shape)
     *  - POST /v1/sessions    → 201 [spawnBody]
     *  - DELETE /v1/sessions/* → 204
     */
    private fun startFixture(
        listBody: String = """[{"n":1,"label":"existing","tmuxTitle":"","state":"running","uptimeSec":10,"activeStreams":0,"startedAt":null}]""",
        spawnBody: String = """{"n":9,"label":"","tmuxTitle":"","state":"starting","uptimeSec":0,"activeStreams":0,"startedAt":null}""",
    ): Fixture {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-coordinator-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        request.method == "GET" && path == "/v1/sessions" ->
                            MockResponse().setResponseCode(200).setBody(listBody)
                        request.method == "POST" && path == "/v1/sessions" ->
                            MockResponse().setResponseCode(201).setBody(spawnBody)
                        request.method == "DELETE" && path.startsWith("/v1/sessions/") ->
                            MockResponse().setResponseCode(204)
                        else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            pinSha256Hex = spkiHex(cert.certificate.publicKey.encoded),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        return Fixture(server, profile)
    }

    private fun apiFactory(): (ServerProfile) -> SessionsApi =
        { profile -> SessionsApi(AiSandboxHttpClient(profile, mockIdentity())) }

    // ── tests ─────────────────────────────────────────────────────────────

    /**
     * THE BUG 1 guard — a successful refresh followed by spawn() MUST
     * put a real {@code POST /v1/sessions} on the wire.
     */
    @Test
    fun spawn_dispatches_outbound_post_to_server() = runBlocking {
        val fx = startFixture()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            // Wait until the initial list completed and the profile/sessions
            // are published (GET round trip done over the real IO dispatcher).
            withTimeout(10_000) {
                state.first { !it.loading && it.profile != null && it.sessions.isNotEmpty() }
            }

            coordinator.spawn("release-build")

            // Drain recorded requests until we observe the outbound POST.
            // Pre-fix this loop would time out (no POST ever dispatched).
            val seen = mutableListOf<String>()
            var sawPost = false
            withTimeout(10_000) {
                while (!sawPost) {
                    val rr = fx.server.takeRequest(10, TimeUnit.SECONDS) ?: break
                    seen.add("${rr.method} ${rr.path}")
                    if (rr.method == "POST" && rr.path == "/v1/sessions") sawPost = true
                }
            }

            assertThat(sawPost)
                .`as`(
                    "BUG 1 — spawn() MUST dispatch an outbound POST /v1/sessions. " +
                        "Asserting outbound dispatch (not just a handled success) is what catches " +
                        "the pre-fix silent non-dispatch (profile sourced from null state.profile). " +
                        "Recorded requests: $seen",
                )
                .isTrue()
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * No-profile path — spawn() inserts the optimistic "starting" row,
     * then the supplier returns null, so it MUST roll the optimistic row
     * back, set {@code lastError == "no_profile"}, clear {@code spawning},
     * and dispatch NOTHING (the api factory must never be invoked).
     */
    @Test
    fun spawn_with_no_profile_rolls_back_optimistic_row_and_flags_no_profile() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val seed = SessionSummary(
                n = 2,
                label = "seed",
                tmuxTitle = "",
                state = "running",
                uptimeSec = 0L,
                activeStreams = 0,
                startedAt = null,
            )
            val state = MutableStateFlow(SessionsUiState(sessions = listOf(seed)))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { null },
                // If spawn() ever builds an API client with a null profile,
                // that's the bug — fail loudly.
                apiFactory = { error("apiFactory must not be called when there is no profile") },
            )

            coordinator.spawn("should-not-leave")

            withTimeout(10_000) {
                state.first { it.lastError == "no_profile" && !it.spawning }
            }

            val finalState = state.value
            assertThat(finalState.lastError).isEqualTo("no_profile")
            assertThat(finalState.spawning).isFalse()
            assertThat(finalState.sessions)
                .`as`("the optimistic 'starting' row MUST be rolled back; only the seed row remains")
                .containsExactly(seed)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Client-side create → delete round trip: spawn() puts a POST on the
     * wire and, on success, refreshes; delete(n,false) puts a
     * {@code DELETE /v1/sessions/{n}} on the wire and, on 204, refreshes.
     * Asserts both outbound mutations actually left the client.
     */
    @Test
    fun create_then_delete_round_trip_dispatches_post_and_delete() = runBlocking {
        val fx = startFixture()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            withTimeout(10_000) {
                state.first { !it.loading && it.profile != null && it.sessions.isNotEmpty() }
            }

            coordinator.spawn("create-me")
            coordinator.delete(1, force = false)

            val verbs = mutableSetOf<String>()
            withTimeout(15_000) {
                while (!(verbs.contains("POST /v1/sessions") && verbs.contains("DELETE /v1/sessions/1"))) {
                    val rr = fx.server.takeRequest(10, TimeUnit.SECONDS) ?: break
                    verbs.add("${rr.method} ${rr.path}")
                }
            }

            assertThat(verbs)
                .`as`("create→delete round trip MUST dispatch both the POST and the DELETE outbound")
                .contains("POST /v1/sessions", "DELETE /v1/sessions/1")
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
