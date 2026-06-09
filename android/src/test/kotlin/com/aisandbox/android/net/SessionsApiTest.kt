package com.aisandbox.android.net

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * BUG 1 root / Fix B — [SessionsApi.list] decodes `GET /v1/sessions`
 * by inspecting the ACTUAL JSON shape (bare array vs `{sessions:[…]}`
 * envelope), driven against a real OkHttp + [SpkiPinningTrustManager] +
 * kotlinx stack over a pinned-HTTPS [MockWebServer] (the same harness
 * as [AiSandboxHttpClientTest]).
 *
 * <h2>#5 decode-gate — pre-Fix-B framing (throws, not null)</h2>
 *
 * <p>The bare-array case ([bareJsonArrayBody_parsesToSuccess]) is the
 * regression guard for the BUG 1 root. The developer empirically
 * verified that the pre-Fix-B envelope-FIRST decode
 * (`decodeFromString(SessionsListEnvelope.serializer(), body).sessions ?: <bare list>`)
 * does NOT silently return null on a bare array — kotlinx's structure
 * decoder requires a `{` and THROWS a {@code JsonDecodingException} (a
 * {@code SerializationException}) before the {@code ?:} fallback can
 * run. So pre-Fix-B {@code list()} on the real (bare-array) server body
 * always landed in {@code mapResponse}'s catch and returned
 * {@code HttpFailure(decode_error)} — never {@code Success}. This test
 * pins {@code Success} on a bare array, so it is RED on pre-Fix-B
 * SessionsApi.kt (commit 13f1e86) and GREEN post-Fix-B. (Cannot be run
 * locally — no Android SDK — so this is authored + reasoned for
 * red-green and validated in CI's `:android:test`.)
 */
class SessionsApiTest {

    /**
     * Empty PKCS#12-backed [KeyStoreIdentityManager] — enough for the
     * SSLContext to advance through the KeyManager side without
     * presenting a client cert. MockWebServer is configured NOT to
     * request one (mTLS identity is the KeyManager's job). Mirrors
     * [AiSandboxHttpClientTest.mockIdentity].
     */
    private fun mockIdentity(): com.aisandbox.android.identity.KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        return mock(com.aisandbox.android.identity.KeyStoreIdentityManager::class.java).also {
            `when`(it.keyManagerFactory()).thenReturn(factory)
        }
    }

    private fun startPinnedServer(): Pair<MockWebServer, ServerProfile> {
        val cert = HeldCertificate.Builder()
            .commonName("ai-sandbox-sessions-api-test")
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

    private fun apiFor(profile: ServerProfile): SessionsApi =
        SessionsApi(AiSandboxHttpClient(profile, mockIdentity()))

    @Test
    fun bareJsonArrayBody_parsesToSuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // The REAL server shape: a bare JSON array (ResponseEntity.ok(List)).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":3,"label":"alpha","tmuxTitle":"work","state":"running","uptimeSec":120,"activeStreams":1,"startedAt":"2026-05-22T09:00:00Z"},
                      {"n":5,"label":"beta","tmuxTitle":"(idle)","state":"stopped","uptimeSec":0,"activeStreams":0,"startedAt":null}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result)
                .`as`(
                    "Fix B — a BARE JSON array MUST decode to ApiResult.Success. " +
                        "Pre-Fix-B the envelope-first decode THROWS JsonDecodingException on a " +
                        "'[' (it never returns null), so list() returned HttpFailure(decode_error) " +
                        "— the BUG 1 root. RED on pre-Fix-B SessionsApi.kt (13f1e86), GREEN post-fix.",
                )
                .isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(3, 5)
            assertThat(success.value.map { it.state }).containsExactly("running", "stopped")
            assertThat(success.value[0].label).isEqualTo("alpha")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionsEnvelopeBody_parsesToSuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // Legacy/tolerated shape: a { "sessions": [...] } envelope.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"sessions":[
                      {"n":7,"label":"gamma","tmuxTitle":"main","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null}
                    ]}
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result)
                .`as`("Fix B — the { sessions: [...] } envelope MUST still decode to Success")
                .isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(7)
            assertThat(success.value[0].activeStreams).isEqualTo(2)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bareEmptyArrayBody_parsesToEmptySuccess() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // The common "no sessions yet" case: a bare empty array.
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            assertThat((result as ApiResult.Success).value).isEmpty()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun provisioningStateBody_roundTrips() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // UC-27 — the server can now report `provisioning` (container up,
            // toolchains installing). It must decode verbatim. A second row
            // carries an unknown state token + an unknown field to pin the
            // `ignoreUnknownKeys` + lenient-token tolerance (StatusPill renders
            // an unknown token raw rather than throwing).
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":4,"label":"installing","tmuxTitle":"(unavailable)","state":"provisioning","uptimeSec":3,"activeStreams":0,"startedAt":null},
                      {"n":6,"label":"future","tmuxTitle":"","state":"some_future_state","uptimeSec":0,"activeStreams":0,"startedAt":null,"unknownField":"x"}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val success = result as ApiResult.Success
            assertThat(success.value.map { it.n }).containsExactly(4, 6)
            // UC-27 — provisioning round-trips as the known token.
            assertThat(success.value[0].state).isEqualTo("provisioning")
            assertThat(success.value[0].label).isEqualTo("installing")
            // ignoreUnknownKeys tolerance: unknown state token + unknown field
            // still decode without throwing.
            assertThat(success.value[1].state).isEqualTo("some_future_state")
        } finally {
            server.shutdown()
        }
    }

    /**
     * UC-47 AC2 — the server-provided `conversationName` field decodes onto
     * [SessionSummary] over REST: a row that carries it exposes it, a row that
     * OMITS it (the server's `@JsonInclude(NON_NULL)`) defaults to null, and a
     * row with an explicit null also reads null. The client reads the field —
     * it never synthesizes a name (AC2).
     */
    @Test
    fun conversationNameField_decodesFromRestAndDefaultsToNullWhenAbsent() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":1,"label":"a","tmuxTitle":"(idle)","state":"running","uptimeSec":1,"activeStreams":0,"startedAt":null,"conversationName":"Refactor the SessionRow"},
                      {"n":2,"label":"b","tmuxTitle":"vim","state":"running","uptimeSec":2,"activeStreams":0,"startedAt":null},
                      {"n":3,"label":"c","tmuxTitle":"(idle)","state":"running","uptimeSec":3,"activeStreams":0,"startedAt":null,"conversationName":null}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val rows = (result as ApiResult.Success).value
            assertThat(rows.map { it.n }).containsExactly(1, 2, 3)
            // Present → exposed verbatim.
            assertThat(rows[0].conversationName).isEqualTo("Refactor the SessionRow")
            // Omitted (server NON_NULL) → null default, row falls back to tmuxTitle.
            assertThat(rows[1].conversationName).isNull()
            // Explicit null → null.
            assertThat(rows[2].conversationName).isNull()
        } finally {
            server.shutdown()
        }
    }

    /**
     * UC-48 AC3 — the server-provided `working` flag decodes onto [SessionSummary]
     * over REST: a row carrying `true`/`false` exposes it, and a row that OMITS it
     * (older server payload) defaults to `false` (no spinner). The client reads the
     * field — it never infers working from the tmux-title string (AC3).
     */
    @Test
    fun workingField_decodesFromRestAndDefaultsToFalseWhenAbsent() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":1,"label":"a","tmuxTitle":"(idle)","state":"running","uptimeSec":1,"activeStreams":0,"startedAt":null,"working":true},
                      {"n":2,"label":"b","tmuxTitle":"vim","state":"running","uptimeSec":2,"activeStreams":0,"startedAt":null,"working":false},
                      {"n":3,"label":"c","tmuxTitle":"(idle)","state":"running","uptimeSec":3,"activeStreams":0,"startedAt":null}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val rows = (result as ApiResult.Success).value
            assertThat(rows.map { it.n }).containsExactly(1, 2, 3)
            // Present true → exposed verbatim (row animates the spinner).
            assertThat(rows[0].working).isTrue()
            // Present false → exposed verbatim (no spinner).
            assertThat(rows[1].working).isFalse()
            // Omitted (older server) → false default; never a stuck spinner.
            assertThat(rows[2].working).isFalse()
        } finally {
            server.shutdown()
        }
    }

    /**
     * UC-49 AC3 — the server-provided `pendingQuestion` flag decodes onto
     * [SessionSummary] over REST: a row carrying `true`/`false` exposes it, and a
     * row that OMITS it (older server payload) defaults to `false` (no badge). The
     * client reads the field — it never infers the pending state from the pane text.
     */
    @Test
    fun pendingQuestionField_decodesFromRestAndDefaultsToFalseWhenAbsent() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    [
                      {"n":1,"label":"a","tmuxTitle":"(idle)","state":"running","uptimeSec":1,"activeStreams":0,"startedAt":null,"working":false,"pendingQuestion":true},
                      {"n":2,"label":"b","tmuxTitle":"vim","state":"running","uptimeSec":2,"activeStreams":0,"startedAt":null,"working":true,"pendingQuestion":false},
                      {"n":3,"label":"c","tmuxTitle":"(idle)","state":"running","uptimeSec":3,"activeStreams":0,"startedAt":null}
                    ]
                    """.trimIndent(),
                ),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val rows = (result as ApiResult.Success).value
            assertThat(rows.map { it.n }).containsExactly(1, 2, 3)
            // Present true → exposed verbatim (row shows the "?" badge).
            assertThat(rows[0].pendingQuestion).isTrue()
            // Present false → exposed verbatim (no badge).
            assertThat(rows[1].pendingQuestion).isFalse()
            // Omitted (older server) → false default; never a stuck badge.
            assertThat(rows[2].pendingQuestion).isFalse()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun problemJsonError_mapsToHttpFailureWithCode() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            // A non-2xx problem+json body must surface as HttpFailure with
            // the parsed `code` — guards parseProblem() against the
            // bare-array/envelope changes (it shares the same JSON config).
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("""{"code":"session_not_found","detail":"session 9 not found"}"""),
            )

            val result = apiFor(profile).list()

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(404)
            assertThat(failure.code).isEqualTo("session_not_found")
        } finally {
            server.shutdown()
        }
    }

    // ── UC-46 — lifecycle() ──────────────────────────────────────────────────

    /**
     * UC-46 — [SessionsApi.lifecycle] dispatches a bodyless
     * `POST /v1/sessions/{n}/{action}` where {action} is the action's wire
     * token, and maps a 204 to [ApiResult.Success]. Asserts the OUTBOUND
     * request shape (method + path + empty body), not just a handled success.
     */
    @Test
    fun lifecycle_dispatches_post_to_action_path_and_204_is_success() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(MockResponse().setResponseCode(204))

            val result = apiFor(profile).lifecycle(5, LifecycleAction.STOP)

            assertThat(result).isInstanceOf(ApiResult.Success::class.java)
            val rr = server.takeRequest()
            assertThat(rr.method).isEqualTo("POST")
            assertThat(rr.path).isEqualTo("/v1/sessions/5/stop")
            assertThat(rr.body.size).isEqualTo(0L)
        } finally {
            server.shutdown()
        }
    }

    /** UC-46 — each action maps to its lowercase wire token in the path. */
    @Test
    fun lifecycle_uses_each_actions_wire_token_in_the_path() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            val expected = listOf(
                LifecycleAction.START to "start",
                LifecycleAction.PAUSE to "pause",
                LifecycleAction.UNPAUSE to "unpause",
            )
            for ((action, token) in expected) {
                server.enqueue(MockResponse().setResponseCode(204))
                apiFor(profile).lifecycle(8, action)
                val rr = server.takeRequest()
                assertThat(rr.path).`as`("%s → token %s", action, token).isEqualTo("/v1/sessions/8/$token")
            }
        } finally {
            server.shutdown()
        }
    }

    /**
     * UC-46 AC7 — a 409 session_state_conflict problem+json surfaces as
     * [ApiResult.HttpFailure] carrying the parsed code + status (the coordinator
     * turns this into the user-visible error).
     */
    @Test
    fun lifecycle_409_conflict_maps_to_http_failure_with_code() = runTest {
        val (server, profile) = startPinnedServer()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setBody(
                        """{"code":"session_state_conflict","detail":"cannot start session 5 in state 'running'"}""",
                    ),
            )

            val result = apiFor(profile).lifecycle(5, LifecycleAction.START)

            assertThat(result).isInstanceOf(ApiResult.HttpFailure::class.java)
            val failure = result as ApiResult.HttpFailure
            assertThat(failure.status).isEqualTo(409)
            assertThat(failure.code).isEqualTo("session_state_conflict")
        } finally {
            server.shutdown()
        }
    }

    private fun spkiHex(spkiBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spkiBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
