package com.aisandbox.android.ui.screens

import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerUpdateApi
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-84 — the Android-free orchestration core [ServerUpdateCoordinator]:
 * check → (confirm) → apply → poll /v1/healthz → re-check → report. Driven
 * through a REAL [ServerUpdateApi] over a pinned-HTTPS [MockWebServer] (the
 * same harness [com.aisandbox.android.net.SessionsApiTest] uses), so the wire
 * contract is exercised end-to-end on a plain JVM (no emulator).
 *
 * <p>Time is collapsed via the [ServerUpdateCoordinator.delayFn] seam ({@code
 * {}} — no real waits during the healthz poll), and each terminal state is
 * awaited off the driving [MutableStateFlow] under a real-time {@code
 * withTimeout} guard.
 *
 * <h2>AC → method map</h2>
 *
 * <ul>
 *   <li>AC3 — {@link #constructing_the_coordinator_does_not_auto_check()}: the
 *       check is explicit, never auto-run.</li>
 *   <li>AC6 — {@link #check_surfaces_update_available()},
 *       {@link #check_surfaces_up_to_date()}.</li>
 *   <li>AC14 — {@link #check_failure_surfaces_error_without_crashing()}.</li>
 *   <li>AC7/AC9 — {@link #confirm_apply_polls_healthz_then_reports_new_version()},
 *       {@link #apply_failure_surfaces_error()}.</li>
 * </ul>
 */
class ServerUpdateCoordinatorTest {

    private fun mockIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        return mock(KeyStoreIdentityManager::class.java).also {
            `when`(it.keyManagerFactory()).thenReturn(factory)
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun startPinnedServer(): Pair<MockWebServer, ServerProfile> {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-update-coord-test")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()
        val handshake = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:${server.port}",
            pinSha256Hex = spkiHex(cert.certificate.publicKey.encoded),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        return server to profile
    }

    private fun apiFor(profile: ServerProfile): ServerUpdateApi =
        ServerUpdateApi(AiSandboxHttpClient(profile, mockIdentity()))

    private fun newCoordinator(
        state: MutableStateFlow<ServerUpdateUiState>,
        api: ServerUpdateApi?,
    ): ServerUpdateCoordinator = ServerUpdateCoordinator(
        state = state,
        scope = CoroutineScope(Dispatchers.IO),
        apiSupplier = { api },
        delayFn = {}, // collapse the healthz-poll backoff
        maxHealthPolls = 5,
    )

    private fun json200(body: String) = MockResponse().setResponseCode(200).setBody(body.trimIndent())

    // ── AC3 — explicit trigger, never auto-run ──────────────────────────────

    @Test
    fun constructing_the_coordinator_does_not_auto_check() {
        val touched = AtomicBoolean(false)
        val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
        ServerUpdateCoordinator(
            state = state,
            scope = CoroutineScope(Dispatchers.IO),
            apiSupplier = { touched.set(true); null },
            delayFn = {},
        )
        // No check happens on construction — the user must press the button (AC3).
        assertThat(touched.get()).isFalse()
        assertThat(state.value).isEqualTo(ServerUpdateUiState.Idle)
    }

    // ── AC6 — check states ──────────────────────────────────────────────────

    @Test
    fun check_surfaces_update_available(): Unit = runBlocking {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                json200(
                    """
                    {"currentVersion":"0.0.9","latestVersion":"9.9.9","updateAvailable":true,
                     "releaseHtmlUrl":"https://gh/server-v9.9.9","debAssetUrl":"https://gh/d/9.9.9_amd64.deb"}
                    """,
                ),
            )
            val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
            newCoordinator(state, apiFor(profile)).check()

            val s = withTimeout(15_000) {
                state.first { it is ServerUpdateUiState.UpdateAvailable || it is ServerUpdateUiState.Error }
            }
            assertThat(s).isInstanceOf(ServerUpdateUiState.UpdateAvailable::class.java)
            val avail = s as ServerUpdateUiState.UpdateAvailable
            assertThat(avail.current).isEqualTo("0.0.9")
            assertThat(avail.latest).isEqualTo("9.9.9")
            assertThat(avail.releaseHtmlUrl).isEqualTo("https://gh/server-v9.9.9")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun check_surfaces_up_to_date(): Unit = runBlocking {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                json200("""{"currentVersion":"9.9.9","latestVersion":"9.9.9","updateAvailable":false}"""),
            )
            val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
            newCoordinator(state, apiFor(profile)).check()

            val s = withTimeout(15_000) {
                state.first { it is ServerUpdateUiState.UpToDate || it is ServerUpdateUiState.Error }
            }
            assertThat(s).isInstanceOf(ServerUpdateUiState.UpToDate::class.java)
            assertThat((s as ServerUpdateUiState.UpToDate).current).isEqualTo("9.9.9")
        } finally {
            server.shutdown()
        }
    }

    // ── AC14 — check failure surfaces an error, nothing crashes ──────────────

    @Test
    fun check_failure_surfaces_error_without_crashing(): Unit = runBlocking {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(502)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"code":"update_github_unreachable","detail":"GitHub down"}"""),
            )
            val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
            newCoordinator(state, apiFor(profile)).check()

            val s = withTimeout(15_000) { state.first { it is ServerUpdateUiState.Error } }
            assertThat((s as ServerUpdateUiState.Error).code).isEqualTo("update_github_unreachable")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun check_with_no_profile_surfaces_no_profile_error(): Unit = runBlocking {
        val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
        newCoordinator(state, api = null).check()
        val s = withTimeout(15_000) { state.first { it is ServerUpdateUiState.Error } }
        assertThat((s as ServerUpdateUiState.Error).code).isEqualTo("no_profile")
    }

    // ── AC7/AC9 — confirm → apply → poll healthz → re-check → report ─────────

    @Test
    fun confirm_apply_polls_healthz_then_reports_new_version(): Unit = runBlocking {
        val (server, profile) = startPinnedServer()
        try {
            // 1) check → available, 2) apply ack, 3) healthz 503 (still down),
            // 4) healthz 200 (back), 5) re-check → now-running version.
            server.enqueue(
                json200(
                    """{"currentVersion":"0.0.9","latestVersion":"9.9.9","updateAvailable":true,
                       "releaseHtmlUrl":"https://gh/server-v9.9.9"}""",
                ),
            )
            server.enqueue(json200("""{"accepted":true,"targetVersion":"9.9.9"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"healthz_fail"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.enqueue(json200("""{"currentVersion":"9.9.9","latestVersion":"9.9.9","updateAvailable":false}"""))

            val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
            val coord = newCoordinator(state, apiFor(profile))

            coord.check()
            withTimeout(15_000) { state.first { it is ServerUpdateUiState.UpdateAvailable } }

            // AC7 — apply only fires after explicit confirm.
            coord.requestConfirm()
            assertThat(state.value).isInstanceOf(ServerUpdateUiState.Confirming::class.java)
            coord.confirmApply()

            val done = withTimeout(20_000) {
                state.first { it is ServerUpdateUiState.Done || it is ServerUpdateUiState.Error }
            }
            assertThat(done).isInstanceOf(ServerUpdateUiState.Done::class.java)
            // AC9 — reports the now-running version once the server is back.
            assertThat((done as ServerUpdateUiState.Done).newVersion).isEqualTo("9.9.9")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun apply_failure_surfaces_error(): Unit = runBlocking {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                json200("""{"currentVersion":"0.0.9","latestVersion":"9.9.9","updateAvailable":true}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(500)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"code":"update_trigger_failed","detail":"io"}"""),
            )
            val state = MutableStateFlow<ServerUpdateUiState>(ServerUpdateUiState.Idle)
            val coord = newCoordinator(state, apiFor(profile))

            coord.check()
            withTimeout(15_000) { state.first { it is ServerUpdateUiState.UpdateAvailable } }
            coord.requestConfirm()
            coord.confirmApply()

            val s = withTimeout(15_000) { state.first { it is ServerUpdateUiState.Error } }
            assertThat((s as ServerUpdateUiState.Error).code).isEqualTo("update_trigger_failed")
        } finally {
            server.shutdown()
        }
    }
}
