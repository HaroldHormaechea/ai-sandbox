package com.aisandbox.android.net

import com.aisandbox.android.identity.KeyStoreIdentityManager
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * UC-88 — close() contract for the three WebSocket clients.
 *
 * <p>The wedge root cause was that cancelling a controller's coroutine does NOT
 * cancel the OkHttp socket it owns, so an abandoned half-open socket lingers
 * 30–60 s and piles up past the server's per-fingerprint cap. The fix has each
 * client's [close] call {@link okhttp3.WebSocket#cancel} AFTER the graceful close
 * frame (cancel-before-open is documented as safe).
 *
 * <p>This class pins the two close()-contract guarantees that are deterministic
 * without a live socket:
 * <ul>
 *   <li><b>cancel-before-open is safe + idempotent</b> — close() before any
 *       connect() (ws == null) must not throw and must land in Disconnected,
 *       and a second close() is a harmless no-op. This is the exact path the
 *       controllers now take when they force-drop an in-flight client before a
 *       relaunch (the socket may still be mid-handshake, ws not yet open).</li>
 *   <li><b>server-refusal close-code constants</b> — the SERVICE_OVERLOAD (1013)
 *       / POLICY_VIOLATION (1008) wire constants the clients + controllers now
 *       branch on are pinned so an Android/server drift surfaces here rather than
 *       in a flaky live run.</li>
 * </ul>
 *
 * <p>The "cancel actually tears the socket down so half-open sockets do not
 * accumulate" guarantee — the meaningful regression — is covered by
 * [WsReconnectAccumulationTest] (it needs a real, stalling transport).
 */
class WsClientCloseCancelTest {

    private fun fakeIdentity(): KeyStoreIdentityManager {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        val emptyP12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        factory.init(emptyP12, charArrayOf())
        val m = mock(KeyStoreIdentityManager::class.java)
        `when`(m.keyManagerFactory()).thenReturn(factory)
        return m
    }

    private fun http(): AiSandboxHttpClient {
        val profile = ServerProfile(
            serverUrl = "https://127.0.0.1:1",
            pinSha256Hex = "00".repeat(32),
            clientCertCn = "alice-phone",
            clientCertExpiresAtMs = 0L,
        )
        return AiSandboxHttpClient(profile, fakeIdentity())
    }

    // ── cancel-before-open is safe + idempotent ──────────────────────────────

    @Test
    fun `SessionEventsClient close before connect is a safe idempotent no-op`() {
        val client = SessionEventsClient(http())
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect") // idempotent — second close must not throw
        }.doesNotThrowAnyException()
        val state = client.state.value
        assertThat(state).isInstanceOf(SessionEventsClient.State.Disconnected::class.java)
        assertThat((state as SessionEventsClient.State.Disconnected).reason).isEqualTo("reconnect")
    }

    @Test
    fun `StreamClient close before connect is a safe idempotent no-op`() {
        val client = StreamClient(http(), sessionN = 7)
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect")
        }.doesNotThrowAnyException()
        val state = client.state.value
        assertThat(state).isInstanceOf(StreamClient.State.Disconnected::class.java)
        assertThat((state as StreamClient.State.Disconnected).reason).isEqualTo("reconnect")
    }

    @Test
    fun `ConversationClient close before connect is a safe idempotent no-op`() {
        val client = ConversationClient(http(), sessionN = 7)
        assertThatCode {
            client.close("reconnect")
            client.close("reconnect")
        }.doesNotThrowAnyException()
        val state = client.state.value
        assertThat(state).isInstanceOf(ConversationClient.State.Disconnected::class.java)
        assertThat((state as ConversationClient.State.Disconnected).reason).isEqualTo("reconnect")
    }

    // ── server-refusal close-code constants pinned ───────────────────────────

    @Test
    fun `SessionEventsClient pins the server-refusal close codes`() {
        // RFC 6455 1013 SERVICE_OVERLOAD (per-fingerprint cap) and 1008
        // POLICY_VIOLATION — the wedge-by-overload signals the controller logs
        // distinctly. Pin them so a drift from the server's Spring CloseStatus
        // surfaces here.
        assertThat(SessionEventsClient.SERVICE_OVERLOAD_CLOSE_CODE).isEqualTo(1013)
        assertThat(SessionEventsClient.POLICY_VIOLATION_CLOSE_CODE).isEqualTo(1008)
    }

    @Test
    fun `StreamClient pins the server-refusal close codes`() {
        assertThat(StreamClient.SERVICE_OVERLOAD_CLOSE_CODE).isEqualTo(1013)
        assertThat(StreamClient.POLICY_VIOLATION_CLOSE_CODE).isEqualTo(1008)
    }
}
