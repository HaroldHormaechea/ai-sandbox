package com.aisandbox.android.ui.screens

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.TerminatingSessionsStore
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
     *  - GET /v1/sessions      → 200 [listBody]  (BARE array, the real shape)
     *  - POST /v1/sessions     → 201 [spawnBody]
     *  - DELETE /v1/sessions/{n} → 204
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
    fun spawn_dispatches_outbound_post_to_server(): Unit = runBlocking {
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
    fun spawn_with_no_profile_rolls_back_optimistic_row_and_flags_no_profile(): Unit = runBlocking {
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
    fun create_then_delete_round_trip_dispatches_post_and_delete(): Unit = runBlocking {
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

    // ── UC20 delete-path coverage (AC4 / AC5) ──────────────────────────────
    //
    // The existing startFixture() hardcodes DELETE → 204. UC20's delete
    // contract has three distinct outcomes the success-only fixture cannot
    // exercise, so each test below stands up a purpose-built pinned server
    // via [pinnedServer]:
    //   1. AC4 — 204 tears the row down: the post-delete refresh re-lists
    //      WITHOUT the deleted N, so the row disappears and does not reappear.
    //   2. AC5 — a non-204 (500 clean_failed / 404 not_found) is surfaced as
    //      "<code> (<status>)" in lastError, NEVER a silent no-op, and the row
    //      stays at its resting position (no optimistic removal on failure).
    //   3. AC5 — a transport failure (connection refused) is single-surfaced
    //      via the full-screen NetworkEvent path (HandshakeError) and the
    //      throwable is swallowed (no viewModelScope crash).

    private val seedListBody =
        """[{"n":1,"label":"existing","tmuxTitle":"","state":"running","uptimeSec":10,"activeStreams":0,"startedAt":null}]"""

    /** Build a pinned-HTTPS MockWebServer + matching profile around [dispatcher]. */
    private fun pinnedServer(dispatcher: Dispatcher): Fixture {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-coordinator-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            this.dispatcher = dispatcher
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

    /**
     * UC-52 — build a pinned-HTTPS MockWebServer whose profile is pinned to the
     * WRONG SPKI hex (all-zeros) against the real served cert. The real
     * [SpkiPinningTrustManager] then throws a CertificateException inside
     * {@code checkServerTrusted}, which JSSE surfaces as an SSLHandshakeException
     * carrying the structured "SPKI pin mismatch: expected=… observed=…" message
     * — a GENUINE TLS/identity failure (not connectivity). Used to prove the
     * UC-52 fix did NOT weaken the identity path (AC4).
     */
    private fun pinnedServerWithWrongPin(dispatcher: Dispatcher): Fixture {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-coordinator-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            this.dispatcher = dispatcher
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            // Deliberately NOT the served cert's pin → SPKI mismatch on handshake.
            pinSha256Hex = "00".repeat(32),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        return Fixture(server, profile)
    }

    /**
     * AC4 — a 204 DELETE actually tears the container down: the server stops
     * enumerating the row, so the coordinator's post-delete refresh() drops it
     * from the list and it does NOT reappear. The list body flips to "[]" only
     * AFTER a DELETE arrives, so observing an empty list proves the outbound
     * DELETE was dispatched and processed (not merely a handled local state).
     */
    @Test
    fun delete_success_tears_row_down_and_it_does_not_reappear(): Unit = runBlocking {
        val listRef = AtomicReference(seedListBody)
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(listRef.get())
                    request.method == "DELETE" && path == "/v1/sessions/1" -> {
                        // Container torn down server-side → row no longer enumerated.
                        listRef.set("[]")
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
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
                state.first { !it.loading && it.sessions.any { s -> s.n == 1 } }
            }

            coordinator.delete(1, force = false)
            // After the 204 the coordinator refreshes; the re-list omits n=1.
            withTimeout(10_000) {
                state.first { !it.loading && it.sessions.isEmpty() }
            }

            assertThat(state.value.sessions)
                .`as`("a 204 DELETE must remove the row via the post-delete refresh (AC4)")
                .noneMatch { it.n == 1 }
            assertThat(state.value.lastError)
                .`as`("a successful delete must not surface any error")
                .isNull()
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC5 — a 500 problem+json (the server's clean-non-zero → 500 mapping)
     * is surfaced as "clean_failed (500)" and the row stays put (no refresh
     * fires on failure, so nothing is removed).
     */
    @Test
    fun delete_http_500_surfaces_code_and_status_and_keeps_row(): Unit = runBlocking {
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(seedListBody)
                    request.method == "DELETE" && path.startsWith("/v1/sessions/") ->
                        MockResponse().setResponseCode(500).setBody(
                            """{"code":"clean_failed","detail":"compose down exit 1; containers still present"}""",
                        )
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
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
                state.first { !it.loading && it.sessions.any { s -> s.n == 1 } }
            }

            coordinator.delete(1, force = false)
            withTimeout(10_000) { state.first { it.lastError != null } }

            assertThat(state.value.lastError)
                .`as`("non-204 DELETE must surface '<code> (<status>)', never a silent no-op (AC5)")
                .isEqualTo("clean_failed (500)")
            assertThat(state.value.sessions)
                .`as`("a failed delete must leave the row at its resting position (AC5)")
                .anyMatch { it.n == 1 }
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC5 — a 404 not_found is surfaced as "not_found (404)". Drives delete()
     * straight off a seeded state (no prior refresh needed), proving the
     * surfacing does not depend on a successful list first.
     */
    @Test
    fun delete_http_404_surfaces_code_and_status(): Unit = runBlocking {
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.method) {
                    "DELETE" -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val seed = SessionSummary(n = 1, label = "existing", state = "running")
            val state = MutableStateFlow(SessionsUiState(sessions = listOf(seed), profile = fx.profile))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.delete(1, force = false)
            withTimeout(10_000) { state.first { it.lastError != null } }

            assertThat(state.value.lastError).isEqualTo("not_found (404)")
            assertThat(state.value.sessions).containsExactly(seed)
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * UC-52 AC1 / AC2 / AC5 / AC6 — when the connection is refused the OkHttp
     * interceptor now translates the IOException (ConnectException) to a
     * TRANSIENT [NetworkEvent.ServerUnreachable] and deliberately does NOT
     * bus-route it (so NO full-screen ServerIdentityChangedScreen), then
     * re-throws. delete()'s try/catch swallows the throwable (no viewModelScope
     * crash) and surfaceTransportThrow() raises the retryable
     * {@code unreachable} banner with {@code lastError == null}
     * (single-surface). This REPLACES the pre-UC-52 expectation that a
     * connectivity drop emitted a [NetworkEvent.HandshakeError] identity event —
     * the headline bug this use case fixes.
     */
    @Test
    fun delete_connectivity_failure_raises_unreachable_banner_and_does_not_escape(): Unit = runBlocking {
        // A real pinned fixture yields a valid profile (real 64-hex pin); then
        // we close the port so the next connect is refused (ConnectException,
        // an IOException with no TLS cause) — exercising the catch's transient
        // connectivity branch.
        val fx = startFixture()
        fx.shutdown()

        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val events = CopyOnWriteArrayList<NetworkEvent>()
            val subscribed = CompletableDeferred<Unit>()
            collectorScope.launch {
                NetworkEvents.flow
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { events.add(it) }
            }
            subscribed.await()

            val state = MutableStateFlow(
                SessionsUiState(sessions = listOf(SessionSummary(n = 1, state = "running"))),
            )
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            // Snapshot the work-scope children that exist right after construction:
            // UC-28's init {} block launches a permanent StateFlow mirror-collector
            // (terminatingSessions.flow.collect) that never completes. Joining ALL
            // children would block forever on it, so we join only the NEW child(ren)
            // spawned by delete() below.
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.delete(1, force = false)

            // Join only the delete() child so its catch has finished — NOT the
            // long-lived init mirror-collector captured in `preExisting`. (No bus
            // event is emitted for a transient drop, so we synchronise on the
            // child completing, not on a NetworkEvent.)
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(uncaught.get())
                .`as`("delete() MUST swallow the transport throwable — nothing escapes viewModelScope")
                .isNull()
            assertThat(events)
                .`as`(
                    "AC6 — a transient connectivity drop is NEVER bus-routed (no full-screen identity event); " +
                        "the interceptor guards `event !is ServerUnreachable` before tryEmit",
                )
                .noneMatch {
                    it is NetworkEvent.HandshakeError ||
                        it is NetworkEvent.PinMismatch ||
                        it is NetworkEvent.HostnameMismatch
                }
            assertThat(state.value.unreachable)
                .`as`("AC1/AC2 — a connectivity failure raises the retryable `unreachable` banner")
                .isTrue()
            assertThat(state.value.lastError)
                .`as`("AC5 — single-surface: the banner is the surface, so lastError stays null (no snackbar)")
                .isNull()
        } finally {
            workScope.cancel()
            collectorScope.cancel()
        }
    }

    /**
     * UC-52 AC4 / AC5 — a GENUINE TLS/identity failure (SPKI pin mismatch) is
     * UNCHANGED: the interceptor translates it to a real identity event
     * ([NetworkEvent.PinMismatch] / [NetworkEvent.HandshakeError]) and bus-routes
     * it to the full-screen ServerIdentityChangedScreen, and the coordinator's
     * surfaceTransportThrow() leaves BOTH {@code unreachable} AND {@code lastError}
     * untouched (no double-surface, UC-51's no-double-surface preserved). Staged
     * by pinning the profile to the WRONG SPKI hex against a real served cert, so
     * SpkiPinningTrustManager throws inside checkServerTrusted.
     */
    @Test
    fun delete_genuine_tls_mismatch_routes_full_screen_and_suppresses_banner_and_snackbar(): Unit = runBlocking {
        val fx = pinnedServerWithWrongPin(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(204)
        })
        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val events = CopyOnWriteArrayList<NetworkEvent>()
            val subscribed = CompletableDeferred<Unit>()
            collectorScope.launch {
                NetworkEvents.flow
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { events.add(it) }
            }
            subscribed.await()

            val state = MutableStateFlow(
                SessionsUiState(sessions = listOf(SessionSummary(n = 1, state = "running"))),
            )
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.delete(1, force = false)

            // A genuine TLS event IS bus-routed; wait for it, then join the child.
            withTimeout(15_000) {
                while (
                    events.none {
                        it is NetworkEvent.PinMismatch || it is NetworkEvent.HandshakeError
                    }
                ) {
                    delay(50)
                }
            }
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(uncaught.get())
                .`as`("delete() MUST swallow the TLS throwable — nothing escapes viewModelScope")
                .isNull()
            assertThat(events)
                .`as`("AC4 — a genuine pin mismatch is still routed full-screen (identity event on the bus)")
                .anyMatch { it is NetworkEvent.PinMismatch || it is NetworkEvent.HandshakeError }
            assertThat(state.value.unreachable)
                .`as`("AC4 — a genuine TLS failure does NOT raise the transient banner")
                .isFalse()
            assertThat(state.value.lastError)
                .`as`(
                    "AC5 — single-surface preserved: a genuine TLS event is surfaced full-screen, " +
                        "so the coordinator sets neither the banner nor the snackbar",
                )
                .isNull()
        } finally {
            workScope.cancel()
            collectorScope.cancel()
        }
    }

    // ── UC-28 — terminating-state transitions (AC2 / AC6 / AC7 / AC8 / AC9) ──
    //
    // These inject a controllable [TerminatingSessionsStore] (the same
    // production class SessionsViewModel passes in) and drive the coordinator
    // against a real pinned server. The optimistic mark happens BEFORE the
    // outbound DELETE, so a dispatcher reading the store at request time
    // observes the flag — the deterministic way to assert "enter on confirm"
    // (AC2) without racing the in-flight coroutine.

    /**
     * AC2 + AC7 — confirming a delete marks the row terminating BEFORE the
     * DELETE leaves the client (captured server-side at dispatch time), then a
     * 204 + post-delete refresh that no longer enumerates the row CLEARS the
     * optimistic flag and removes the row (it does not reappear).
     */
    @Test
    fun delete_marks_terminating_before_request_then_clears_on_success_removal(): Unit = runBlocking {
        val listRef = AtomicReference(seedListBody)
        val flagAtDeleteTime = AtomicReference<Set<Int>>(emptySet())
        val store = TerminatingSessionsStore()
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(listRef.get())
                    request.method == "DELETE" && path == "/v1/sessions/1" -> {
                        // The mark MUST already be visible by the time the DELETE
                        // request arrives (mark precedes the network call).
                        flagAtDeleteTime.set(store.flow.value)
                        listRef.set("[]") // container torn down → no longer enumerated
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.any { s -> s.n == 1 } } }

            coordinator.delete(1, force = false)
            // Success → refresh re-lists without n=1 → row gone + flag cleared.
            withTimeout(10_000) { state.first { !it.loading && it.sessions.isEmpty() } }

            assertThat(flagAtDeleteTime.get())
                .`as`("AC2 — the row MUST be optimistically terminating before the DELETE is dispatched")
                .contains(1)
            assertThat(store.flow.value)
                .`as`("AC7 — a successful delete that removes the row clears the optimistic flag")
                .doesNotContain(1)
            assertThat(state.value.terminating).doesNotContain(1)
            assertThat(state.value.sessions).noneMatch { it.n == 1 }
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC8 — an explicit non-success (500) exits terminating: the optimistic flag
     * is reverted so the row falls back to its real server status, AND the error
     * is surfaced (never a silent no-op). The mark was still applied first.
     */
    @Test
    fun delete_http_failure_reverts_terminating_and_surfaces_error(): Unit = runBlocking {
        val flagAtDeleteTime = AtomicReference<Set<Int>>(emptySet())
        val store = TerminatingSessionsStore()
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(seedListBody)
                    request.method == "DELETE" && path.startsWith("/v1/sessions/") -> {
                        flagAtDeleteTime.set(store.flow.value)
                        MockResponse().setResponseCode(500).setBody(
                            """{"code":"clean_failed","detail":"compose down exit 1"}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val seed = SessionSummary(n = 1, label = "existing", state = "running")
            val state = MutableStateFlow(SessionsUiState(sessions = listOf(seed), profile = fx.profile))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.delete(1, force = false)
            withTimeout(10_000) { state.first { it.lastError != null } }

            assertThat(flagAtDeleteTime.get())
                .`as`("AC2 — the optimistic flag is applied before the (failing) DELETE is dispatched")
                .contains(1)
            assertThat(store.flow.value)
                .`as`("AC8 — an explicit failure reverts terminating (back to the real status)")
                .doesNotContain(1)
            assertThat(state.value.terminating).doesNotContain(1)
            assertThat(state.value.lastError)
                .`as`("AC8 — the failure is surfaced, never a silent no-op")
                .isEqualTo("clean_failed (500)")
            assertThat(state.value.sessions).anyMatch { it.n == 1 }
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC8 (throwable/transport branch) — when the delete call THROWS (transport
     * drop, TLS, or any error mid-request), delete()'s {@code catch (Throwable)}
     * exits terminating so the row reverts to its real status rather than
     * wedging on the optimistic pill, surfaces the error (the throwable is not a
     * translated TLS failure here → snackbar lastError), and swallows the
     * throwable (nothing escapes the scope).
     *
     * <p>Deterministic by construction: the injected api factory throws on use,
     * exercising the exact catch branch without the real-network connect-refused
     * timing that makes the pinned-MockWebServer transport path CI-only.
     */
    @Test
    fun delete_throwable_reverts_terminating_and_surfaces_error(): Unit = runBlocking {
        val store = TerminatingSessionsStore()
        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:1",
            pinSha256Hex = "00".repeat(32),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        try {
            val state = MutableStateFlow(
                SessionsUiState(sessions = listOf(SessionSummary(n = 1, state = "running")), profile = profile),
            )
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { profile },
                // The factory throws when delete() tries to build/use the client
                // → drops straight into delete()'s catch (Throwable) branch.
                apiFactory = { error("transport boom") },
                terminatingSessions = store,
            )

            coordinator.delete(1, force = false)
            // Wait until the flag has been reverted AND the error surfaced.
            withTimeout(10_000) {
                state.first { it.lastError != null && !it.terminating.contains(1) }
            }

            assertThat(store.flow.value)
                .`as`("AC8 — a thrown delete reverts terminating (the delete did not land)")
                .doesNotContain(1)
            assertThat(state.value.lastError)
                .`as`("AC8 — a non-TLS throwable is surfaced (snackbar), never a silent no-op")
                .isNotNull()
            assertThat(uncaught.get())
                .`as`("delete() must swallow the throwable — nothing escapes the scope")
                .isNull()
        } finally {
            workScope.cancel()
        }
    }

    /**
     * AC9 — an in-flight refresh that finds the row STILL reported running
     * (possibly stale just after a confirm) does NOT spuriously resurrect a
     * running pill: the optimistic terminating flag is KEPT until a resolving
     * status (or removal) arrives. No client-side timeout forces it out early.
     */
    @Test
    fun refresh_keeps_optimistic_terminating_while_server_still_reports_running(): Unit = runBlocking {
        val store = TerminatingSessionsStore()
        store.mark(1)
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(seedListBody) // n=1 still "running"
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.any { s -> s.n == 1 } } }

            assertThat(store.flow.value)
                .`as`("AC9 — a stale `running` refresh must NOT clear the optimistic terminating flag")
                .contains(1)
            assertThat(state.value.terminating).contains(1)
            assertThat(state.value.effectiveState(state.value.sessions.first { it.n == 1 }))
                .isEqualTo("terminating")
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC7/AC9 — a refresh that reports the row {@code stopped} is a resolution:
     * the optimistic flag is cleared (the teardown completed / the row settled).
     */
    @Test
    fun refresh_clears_optimistic_when_server_reports_stopped(): Unit = runBlocking {
        val store = TerminatingSessionsStore()
        store.mark(1)
        val stoppedBody =
            """[{"n":1,"label":"existing","tmuxTitle":"","state":"stopped","uptimeSec":0,"activeStreams":0,"startedAt":null}]"""
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(stoppedBody)
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.any { s -> s.n == 1 } } }

            assertThat(store.flow.value)
                .`as`("AC9 — a resolving `stopped` status clears the optimistic flag")
                .doesNotContain(1)
            assertThat(state.value.terminating).doesNotContain(1)
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC9 handoff — when the server itself starts reporting {@code terminating},
     * the optimistic flag hands off to the server token: the optimistic set is
     * cleared (no longer needed) but the pill stays terminating via the server
     * status (the union still resolves to terminating).
     */
    @Test
    fun refresh_hands_off_optimistic_to_server_terminating_token(): Unit = runBlocking {
        val store = TerminatingSessionsStore()
        store.mark(1)
        val terminatingBody =
            """[{"n":1,"label":"existing","tmuxTitle":"","state":"terminating","uptimeSec":0,"activeStreams":0,"startedAt":null}]"""
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(terminatingBody)
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.any { s -> s.n == 1 } } }

            assertThat(store.flow.value)
                .`as`("AC9 — the optimistic flag hands off to the server's terminating token")
                .doesNotContain(1)
            // The pill is STILL terminating — now via the server-reported status.
            assertThat(state.value.effectiveState(state.value.sessions.first { it.n == 1 }))
                .`as`("the union keeps the pill terminating via the server token after handoff")
                .isEqualTo("terminating")
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC6 — per-session isolation: deleting session 1 marks ONLY session 1
     * terminating; session 2 is never flagged and remains in the list. Captured
     * at DELETE-dispatch time so the assertion sees the in-flight flag set.
     */
    @Test
    fun delete_is_per_session_isolated(): Unit = runBlocking {
        val twoRowBody =
            """[{"n":1,"label":"a","tmuxTitle":"","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null},""" +
                """{"n":2,"label":"b","tmuxTitle":"","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}]"""
        val afterDeleteBody =
            """[{"n":2,"label":"b","tmuxTitle":"","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}]"""
        val listRef = AtomicReference(twoRowBody)
        val flagAtDeleteTime = AtomicReference<Set<Int>>(emptySet())
        val store = TerminatingSessionsStore()
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(listRef.get())
                    request.method == "DELETE" && path == "/v1/sessions/1" -> {
                        flagAtDeleteTime.set(store.flow.value)
                        listRef.set(afterDeleteBody)
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
                terminatingSessions = store,
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.size == 2 } }

            coordinator.delete(1, force = false)
            withTimeout(10_000) { state.first { !it.loading && it.sessions.size == 1 } }

            assertThat(flagAtDeleteTime.get())
                .`as`("AC6 — only the targeted session is flagged terminating, never its sibling")
                .containsExactly(1)
            assertThat(state.value.sessions.map { it.n })
                .`as`("AC6 — the sibling session remains fully present and deletable")
                .containsExactly(2)
            assertThat(state.value.terminating).doesNotContain(2)
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    // ── UC-46 — lifecycle action: optimistic pending / reconcile / surface ───
    //
    // Mirrors the delete-path coverage. The optimistic `pendingActions` mark
    // happens BEFORE the outbound POST, so a dispatcher reading state at request
    // time observes it (AC6). On 204 → refresh reconciles to the authoritative
    // state and pending clears; on a 409/HTTP failure → the error surfaces and
    // pending clears in `finally` (AC7 — never a stuck disabled control).

    private val runningRowBody =
        """[{"n":1,"label":"alpha","tmuxTitle":"","state":"running","uptimeSec":10,"activeStreams":0,"startedAt":null}]"""
    private val pausedRowBody =
        """[{"n":1,"label":"alpha","tmuxTitle":"","state":"paused","uptimeSec":10,"activeStreams":0,"startedAt":null}]"""

    /**
     * AC6 — a lifecycle action marks the row pending BEFORE the POST leaves the
     * client (captured server-side at dispatch time), then a 204 + post-action
     * refresh reconciles the row to its new authoritative state (running →
     * paused) and CLEARS the pending flag.
     */
    @Test
    fun lifecycle_marks_pending_before_request_then_clears_and_reconciles_on_success(): Unit = runBlocking {
        val listRef = AtomicReference(runningRowBody)
        val pendingAtRequestTime = AtomicReference<Set<Int>>(emptySet())
        lateinit var stateRef: MutableStateFlow<SessionsUiState>
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(listRef.get())
                    request.method == "POST" && path == "/v1/sessions/1/pause" -> {
                        // The pending mark MUST be visible by the time the POST arrives.
                        pendingAtRequestTime.set(stateRef.value.pendingActions)
                        listRef.set(pausedRowBody) // server now reports the row paused
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            stateRef = state
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.any { s -> s.n == 1 && s.state == "running" } } }

            coordinator.lifecycle(1, com.aisandbox.android.net.LifecycleAction.PAUSE)
            // Success → refresh re-lists with state=paused and pending cleared.
            withTimeout(10_000) { state.first { it.sessions.any { s -> s.n == 1 && s.state == "paused" } } }

            assertThat(pendingAtRequestTime.get())
                .`as`("AC6 — the row MUST be optimistically pending before the POST is dispatched")
                .contains(1)
            assertThat(state.value.pendingActions)
                .`as`("AC6 — pending clears once the action resolves (control re-enabled)")
                .doesNotContain(1)
            assertThat(state.value.lastError)
                .`as`("a successful lifecycle action surfaces no error")
                .isNull()
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC7 — a 409 session_state_conflict (state drifted from under the client)
     * is surfaced as "session_state_conflict (409)", the pending flag is cleared
     * (control re-enabled), and the row is left at its prior state — never a
     * stuck fake state.
     */
    @Test
    fun lifecycle_http_409_surfaces_error_and_clears_pending_and_keeps_state(): Unit = runBlocking {
        val pendingAtRequestTime = AtomicReference<Set<Int>>(emptySet())
        lateinit var stateRef: MutableStateFlow<SessionsUiState>
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    request.method == "GET" && path == "/v1/sessions" ->
                        MockResponse().setResponseCode(200).setBody(runningRowBody)
                    request.method == "POST" && path.startsWith("/v1/sessions/1/") -> {
                        pendingAtRequestTime.set(stateRef.value.pendingActions)
                        MockResponse().setResponseCode(409).setBody(
                            """{"code":"session_state_conflict","detail":"cannot start session 1 in state 'running'"}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found"}""")
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val seed = SessionSummary(n = 1, label = "alpha", state = "running")
            val state = MutableStateFlow(SessionsUiState(sessions = listOf(seed), profile = fx.profile))
            stateRef = state
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.lifecycle(1, com.aisandbox.android.net.LifecycleAction.START)
            withTimeout(10_000) { state.first { it.lastError != null } }

            assertThat(pendingAtRequestTime.get())
                .`as`("AC6 — pending applied before the (failing) POST is dispatched")
                .contains(1)
            assertThat(state.value.lastError)
                .`as`("AC7 — a 409 must surface '<code> (<status>)', never a silent no-op")
                .isEqualTo("session_state_conflict (409)")
            assertThat(state.value.pendingActions)
                .`as`("AC7 — pending clears in finally so the control is re-enabled")
                .doesNotContain(1)
            assertThat(state.value.sessions)
                .`as`("AC7 — the row is left at its prior state (no fake/stuck state)")
                .anyMatch { it.n == 1 && it.state == "running" }
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC7 (transport branch) — a transport throw (connection refused) is
     * swallowed (no scope crash) and pending is still cleared in `finally`.
     * UC-52 — the connectivity drop now raises the transient {@code unreachable}
     * banner (not a full-screen identity event); the pending-clear contract is
     * unchanged.
     */
    @Test
    fun lifecycle_transport_failure_is_swallowed_and_clears_pending(): Unit = runBlocking {
        val fx = startFixture()
        fx.shutdown() // closed port → next connect refused (IOException)

        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        try {
            val state = MutableStateFlow(
                SessionsUiState(sessions = listOf(SessionSummary(n = 1, state = "running"))),
            )
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.lifecycle(1, com.aisandbox.android.net.LifecycleAction.PAUSE)

            // Join only the lifecycle() child so its catch+finally have finished.
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(uncaught.get())
                .`as`("lifecycle() MUST swallow the transport throwable — nothing escapes the scope")
                .isNull()
            assertThat(state.value.pendingActions)
                .`as`("AC7 — pending clears in finally even when the call throws")
                .doesNotContain(1)
            assertThat(state.value.unreachable)
                .`as`("UC-52 — the connectivity drop raises the transient unreachable banner")
                .isTrue()
            assertThat(state.value.lastError)
                .`as`("UC-52 single-surface — banner only, no snackbar")
                .isNull()
        } finally {
            workScope.cancel()
        }
    }

    /**
     * No-profile path — lifecycle() flags `no_profile` and dispatches NOTHING
     * (the api factory must never be invoked).
     */
    @Test
    fun lifecycle_with_no_profile_flags_no_profile_and_dispatches_nothing(): Unit = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(
                SessionsUiState(sessions = listOf(SessionSummary(n = 1, state = "running"))),
            )
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { null },
                apiFactory = { error("apiFactory must not be called when there is no profile") },
            )

            coordinator.lifecycle(1, com.aisandbox.android.net.LifecycleAction.PAUSE)
            withTimeout(10_000) { state.first { it.lastError == "no_profile" } }

            assertThat(state.value.lastError).isEqualTo("no_profile")
            assertThat(state.value.pendingActions).doesNotContain(1)
        } finally {
            scope.cancel()
        }
    }

    // ── UC-51 + UC-52 — refresh()/spawn() transport-throw hardening ──────────
    //
    // refresh() and spawn() previously called list()/spawn(label) with NO
    // try/catch, so a transport throw (connection refused, timeout, unknown
    // host, TLS) escaped uncaught on viewModelScope (Main) → FATAL EXCEPTION:
    // main (the "ai-sandbox keeps stopping" crash). UC-51 added the try/catch;
    // UC-52 then RE-CLASSIFIED the connectivity throw: a real ConnectException
    // (closed-port fixture) is an IOException with no TLS cause, so the
    // interceptor translates it to a TRANSIENT NetworkEvent.ServerUnreachable
    // and DOES NOT bus-route it — there is no full-screen identity event for a
    // mere network drop (the headline UC-52 fix). The coordinator's catch then
    // raises the retryable `unreachable` banner with lastError == null
    // (single-surface). These tests therefore assert the banner + absence of
    // any identity event, REPLACING the pre-UC-52 HandshakeError expectation.
    // A throwing fake apiFactory is impossible here: SessionsApi is a final
    // class with non-open suspend methods, so the closed-port fixture is the
    // established way to make a real call throw.

    /**
     * UC-52 AC1 / AC2 / AC3(crash-safety) / AC5 — a connectivity throw out of
     * refresh()'s list() call is caught: nothing escapes the scope (no
     * main-thread crash), NO identity event is bus-routed (a transient drop
     * never force-routes the identity screen), the retryable `unreachable`
     * banner is raised with lastError == null (single-surface), `loading` is
     * cleared (no stuck spinner), and the last-known `sessions` list is
     * preserved (the screen renders it, recovering on the next successful
     * refresh).
     */
    @Test
    fun refresh_connectivity_failure_raises_unreachable_banner_and_does_not_escape(): Unit = runBlocking {
        // Real pinned fixture → valid profile (real 64-hex pin); then close the
        // port so the next connect is refused (ConnectException, an IOException).
        val fx = startFixture()
        fx.shutdown()

        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val events = CopyOnWriteArrayList<NetworkEvent>()
            val subscribed = CompletableDeferred<Unit>()
            collectorScope.launch {
                NetworkEvents.flow
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { events.add(it) }
            }
            subscribed.await()

            val seeded = listOf(
                SessionSummary(n = 1, label = "existing", state = "running"),
                SessionSummary(n = 2, label = "other", state = "running"),
            )
            val state = MutableStateFlow(SessionsUiState(sessions = seeded))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            // Snapshot the init {} mirror-collector child so we join only the
            // refresh() child below (see the delete-transport test for why).
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.refresh()

            // No bus event for a transient drop — synchronise on the refresh()
            // child completing (its catch raises the banner before it returns).
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(uncaught.get())
                .`as`("refresh() MUST swallow the transport throwable; nothing escapes viewModelScope (no crash)")
                .isNull()
            assertThat(events)
                .`as`("AC6 — a transient connectivity drop is NEVER bus-routed (no full-screen identity event)")
                .noneMatch {
                    it is NetworkEvent.HandshakeError ||
                        it is NetworkEvent.PinMismatch ||
                        it is NetworkEvent.HostnameMismatch
                }
            assertThat(state.value.unreachable)
                .`as`("AC1/AC2 — refresh() raises the retryable `unreachable` banner on a connectivity drop")
                .isTrue()
            assertThat(state.value.loading)
                .`as`("loading is always cleared so there's no stuck spinner")
                .isFalse()
            assertThat(state.value.lastError)
                .`as`("AC5 — single-surface: the banner is the surface, so lastError stays null (no snackbar)")
                .isNull()
            assertThat(state.value.sessions)
                .`as`("the last-known list survives a failed refresh (sessions is NOT nulled, so the screen recovers)")
                .isEqualTo(seeded)
        } finally {
            workScope.cancel()
            collectorScope.cancel()
        }
    }

    /**
     * UC-52 AC1 / AC2 — tapping Spawn while the server is unreachable: spawn()
     * inserts the optimistic "starting" row, the spawn() call throws a
     * connectivity exception, and the catch rolls that row back (same predicate
     * as the HttpFailure branch) so a phantom session can't persist. Nothing
     * escapes the scope (no crash), NO identity event is bus-routed, the
     * retryable `unreachable` banner is raised with lastError == null
     * (single-surface), and `spawning` is reset in `finally` so the FAB never
     * sticks disabled.
     */
    @Test
    fun spawn_connectivity_failure_rolls_back_optimistic_row_and_raises_unreachable(): Unit = runBlocking {
        val fx = startFixture()
        fx.shutdown() // closed port → next connect refused (ConnectException)

        val uncaught = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, t -> uncaught.set(t) }
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val events = CopyOnWriteArrayList<NetworkEvent>()
            val subscribed = CompletableDeferred<Unit>()
            collectorScope.launch {
                NetworkEvents.flow
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { events.add(it) }
            }
            subscribed.await()

            val seeded = listOf(
                SessionSummary(n = 1, label = "a", state = "running"),
                SessionSummary(n = 2, label = "b", state = "running"),
            )
            val state = MutableStateFlow(SessionsUiState(sessions = seeded))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.spawn("new-session")

            // No bus event for a transient drop — join the spawn() child so its
            // catch + finally have finished.
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(uncaught.get())
                .`as`("spawn() MUST swallow the transport throwable; nothing escapes viewModelScope (no crash)")
                .isNull()
            assertThat(events)
                .`as`("AC6 — a transient connectivity drop is NEVER bus-routed (no full-screen identity event)")
                .noneMatch {
                    it is NetworkEvent.HandshakeError ||
                        it is NetworkEvent.PinMismatch ||
                        it is NetworkEvent.HostnameMismatch
                }
            assertThat(state.value.sessions)
                .`as`(
                    "AC1 — the optimistic 'starting' row is rolled back on a transport throw (same predicate as " +
                        "the HttpFailure branch); only the seed rows remain, so no phantom session persists",
                )
                .isEqualTo(seeded)
            assertThat(state.value.spawning)
                .`as`("spawning is reset in finally so the FAB never sticks disabled")
                .isFalse()
            assertThat(state.value.unreachable)
                .`as`("AC2 — spawn() raises the retryable `unreachable` banner on a connectivity drop")
                .isTrue()
            assertThat(state.value.lastError)
                .`as`("AC5 — single-surface: banner only, no snackbar (lastError stays null)")
                .isNull()
        } finally {
            workScope.cancel()
            collectorScope.cancel()
        }
    }

    // ── UC-52 — auto-recovery: any operation that proves the server responded
    //            clears the transient `unreachable` banner (AC3), and the
    //            single-surface invariant (unreachable XOR lastError) holds. ──

    /**
     * AC3 — a successful list() (the server is reachable again) clears a stale
     * `unreachable` banner WITHOUT the user re-scanning a QR. Seeds the state
     * with unreachable=true (as if a prior drop set it) and a live fixture, then
     * refresh() → 200 Success → banner auto-clears. This is the recovery half of
     * the cycle whose failure half is
     * [refresh_connectivity_failure_raises_unreachable_banner_and_does_not_escape].
     */
    @Test
    fun refresh_success_clears_stale_unreachable_banner(): Unit = runBlocking {
        val fx = startFixture()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState(unreachable = true))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            withTimeout(10_000) {
                state.first { !it.loading && it.sessions.isNotEmpty() }
            }

            assertThat(state.value.unreachable)
                .`as`("AC3 — a successful list proves reachability and auto-clears the banner (no re-scan needed)")
                .isFalse()
            assertThat(state.value.lastError).isNull()
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC3 / single-surface — an HTTP status (even a 500 error) proves the server
     * ANSWERED, so refresh() clears the `unreachable` banner and surfaces the
     * error via lastError instead. Asserts the two surfaces are mutually
     * exclusive (never unreachable && lastError simultaneously).
     */
    @Test
    fun refresh_http_failure_clears_unreachable_and_surfaces_snackbar(): Unit = runBlocking {
        val fx = pinnedServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("""{"code":"boom","detail":"x"}""")
        })
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState(unreachable = true))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.lastError != null } }

            assertThat(state.value.unreachable)
                .`as`("an HTTP answer proves reachability → the banner clears")
                .isFalse()
            assertThat(state.value.lastError)
                .`as`("the HTTP error is surfaced as a snackbar instead ('<code> (<status>)')")
                .isEqualTo("boom (500)")
            // Single-surface invariant.
            assertThat(state.value.unreachable && state.value.lastError != null)
                .`as`("single-surface — never `unreachable` AND `lastError` simultaneously")
                .isFalse()
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * AC3 — a UC-32 push Snapshot proves the server is reachable, so
     * applySnapshot() auto-clears the `unreachable` banner even without a manual
     * Retry (the live feed recovering is a valid recovery path). Pure/synchronous
     * (no scope.launch), so the assertion is immediate.
     */
    @Test
    fun applySnapshot_clears_unreachable_banner(): Unit = runBlocking {
        // De-flake (test-only): run the coordinator's init collector inline on
        // Dispatchers.Unconfined instead of Dispatchers.IO. The init collector
        // (terminatingSessions.flow → state.value = state.value.copy(...)) and
        // the synchronous applySnapshot() below both read-modify-write the same
        // MutableStateFlow. On Dispatchers.IO the collector runs on a separate
        // thread, so its copy() could clobber the banner-clear from a stale
        // read (the flake). Unconfined makes the collector consume its initial
        // emission inline during construction (then suspend), so applySnapshot
        // runs single-threaded — no cross-thread race. Production is unaffected:
        // SessionsViewModel uses viewModelScope (Main.immediate), single-threaded.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState(unreachable = true))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { null },
                apiFactory = { error("apiFactory must not be called by applySnapshot") },
            )

            coordinator.applySnapshot(listOf(SessionSummary(n = 1, state = "running")))

            assertThat(state.value.unreachable)
                .`as`("AC3 — an inbound push Snapshot proves reachability and clears the banner")
                .isFalse()
            assertThat(state.value.sessions).anyMatch { it.n == 1 }
        } finally {
            scope.cancel()
        }
    }

    /**
     * AC3 — a UC-32 push Delta likewise proves reachability and clears the
     * `unreachable` banner. Pure/synchronous.
     */
    @Test
    fun applyDelta_clears_unreachable_banner(): Unit = runBlocking {
        // De-flake (test-only): run the coordinator's init collector inline on
        // Dispatchers.Unconfined instead of Dispatchers.IO. See the sibling
        // applySnapshot_clears_unreachable_banner for the full rationale — the
        // init collector and the synchronous applyDelta() below both
        // read-modify-write the shared MutableStateFlow, and an IO-thread
        // collector could clobber the banner-clear (the flake). Unconfined makes
        // the collector run inline, so applyDelta runs single-threaded.
        // Production is unaffected (viewModelScope / Main.immediate).
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState(unreachable = true))
            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { null },
                apiFactory = { error("apiFactory must not be called by applyDelta") },
            )

            coordinator.applyDelta(
                upserts = listOf(SessionSummary(n = 2, state = "running")),
                removed = emptyList(),
            )

            assertThat(state.value.unreachable)
                .`as`("AC3 — an inbound push Delta proves reachability and clears the banner")
                .isFalse()
            assertThat(state.value.sessions).anyMatch { it.n == 2 }
        } finally {
            scope.cancel()
        }
    }

    // ── UC-54 — tri-state connectivity wired through the real client stack ───
    //
    // These extend the live MockWebServer fixture suite (startFixture / the
    // unreachable scaffolding) to prove the coordinator flips
    // SessionsUiState.serverResponded at exactly the server-answered sites and
    // that the derived `connectivity` reads REACHABLE/UNREACHABLE end-to-end
    // (AC2/AC4). The pure precedence/recovery matrix lives in
    // SessionsUiStateTest; here we assert the coordinator drives the inputs.

    /**
     * UC-54 AC2 — a successful (HTTP-answered) refresh flips
     * `serverResponded = true`, leaving the derived dot at REACHABLE (green).
     * Seeds from the default UNKNOWN state to prove the transition.
     */
    @Test
    fun refresh_success_marks_server_responded_and_dot_is_reachable(): Unit = runBlocking {
        val fx = startFixture()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            // Pre-condition: a brand-new state derives UNKNOWN (yellow).
            assertThat(state.value.connectivity).isEqualTo(Connectivity.UNKNOWN)

            val coordinator = SessionsCoordinator(
                state = state,
                scope = scope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.sessions.isNotEmpty() } }

            assertThat(state.value.serverResponded)
                .`as`("AC2 — a 200 list proves the server answered → serverResponded is latched true")
                .isTrue()
            assertThat(state.value.connectivity)
                .`as`("AC2 — server answered, not in-flight, not unreachable → REACHABLE (green)")
                .isEqualTo(Connectivity.REACHABLE)
        } finally {
            scope.cancel()
            fx.shutdown()
        }
    }

    /**
     * UC-54 AC4 — a connectivity drop (port closed → ConnectException, a
     * non-TLS IOException classified ServerUnreachable) leaves the derived dot
     * at UNREACHABLE (red). Reuses the connectivity-drop scaffolding from
     * [refresh_connectivity_failure_raises_unreachable_banner_and_does_not_escape].
     */
    @Test
    fun refresh_connectivity_drop_dot_is_unreachable(): Unit = runBlocking {
        val fx = startFixture()
        fx.shutdown() // close the port → next connect refused

        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { fx.profile },
                apiFactory = apiFactory(),
            )
            val preExisting = workScope.coroutineContext.job.children.toSet()

            coordinator.refresh()
            withTimeout(15_000) {
                (workScope.coroutineContext.job.children.toSet() - preExisting).forEach { it.join() }
            }

            assertThat(state.value.unreachable)
                .`as`("a transient connectivity drop sets unreachable")
                .isTrue()
            assertThat(state.value.connectivity)
                .`as`("AC4 — last interaction failed (unreachable), not in-flight → UNREACHABLE (red)")
                .isEqualTo(Connectivity.UNREACHABLE)
        } finally {
            workScope.cancel()
        }
    }

    /**
     * UC-54 null-branch guard — an ordinary, translator-UNOWNED throw (a
     * non-IOException → `TlsFailureTranslation.translate` returns null) goes
     * through surfaceTransportThrow's `null` arm, which sets `lastError`,
     * clears `unreachable`, and DELIBERATELY does NOT touch `serverResponded`.
     * Proven from BOTH seeds:
     *   • serverResponded=false → stays false → dot stays UNKNOWN (not green);
     *   • serverResponded=true  → stays true  → dot stays REACHABLE.
     * The injected factory throws an IllegalStateException (via `error(...)`),
     * exactly the non-TLS, non-IO throwable the translator does not own.
     */
    @Test
    fun ordinary_throw_does_not_flip_server_responded_from_unknown_seed(): Unit = runBlocking {
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:1",
            pinSha256Hex = "00".repeat(32),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        try {
            // Seed A: nothing answered yet (UNKNOWN). An ordinary throw must
            // NOT promote the dot to green.
            val state = MutableStateFlow(SessionsUiState())
            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { profile },
                // IllegalStateException — not an SSL/IO exception → translate() → null.
                apiFactory = { error("ordinary boom") },
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.lastError != null } }

            assertThat(state.value.serverResponded)
                .`as`("null-branch — an ordinary throw never proves the server answered")
                .isFalse()
            assertThat(state.value.unreachable)
                .`as`("null-branch clears unreachable (ordinary error → snackbar, not banner)")
                .isFalse()
            assertThat(state.value.connectivity)
                .`as`("serverResponded still false, not in-flight, not unreachable → stays UNKNOWN (not green)")
                .isEqualTo(Connectivity.UNKNOWN)
        } finally {
            workScope.cancel()
        }
    }

    @Test
    fun ordinary_throw_does_not_clear_server_responded_from_reachable_seed(): Unit = runBlocking {
        val workScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:1",
            pinSha256Hex = "00".repeat(32),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        try {
            // Seed B: the server had answered before (REACHABLE). An ordinary
            // throw surfaces a snackbar but must NOT downgrade the dot — only a
            // genuine connectivity drop (unreachable) does that.
            val state = MutableStateFlow(SessionsUiState(serverResponded = true))
            assertThat(state.value.connectivity).isEqualTo(Connectivity.REACHABLE)

            val coordinator = SessionsCoordinator(
                state = state,
                scope = workScope,
                profileSupplier = { profile },
                apiFactory = { error("ordinary boom") },
            )

            coordinator.refresh()
            withTimeout(10_000) { state.first { !it.loading && it.lastError != null } }

            assertThat(state.value.serverResponded)
                .`as`("null-branch — serverResponded is never cleared once set")
                .isTrue()
            assertThat(state.value.unreachable).isFalse()
            assertThat(state.value.connectivity)
                .`as`("an ordinary error keeps the dot REACHABLE (green); only unreachable turns it red")
                .isEqualTo(Connectivity.REACHABLE)
        } finally {
            workScope.cancel()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
