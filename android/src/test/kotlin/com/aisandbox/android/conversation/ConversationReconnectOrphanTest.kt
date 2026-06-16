package com.aisandbox.android.conversation

import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ConversationClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.ui.screens.TerminalState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.stubbing.Answer

/**
 * UC-88 — [ConversationController] must force-drop the prior in-flight client
 * BEFORE relaunching the connect loop, so repeated reconnects (the chat→list
 * bounce / "tap to reconnect") do NOT orphan a string of still-live clients
 * whose sockets linger and pile up past the server's per-fingerprint cap.
 *
 * <p>Mechanism under test (the new line in {@code startConnectLoop}):
 * cancelling the loop coroutine does not cancel the OkHttp socket the client
 * owns, so {@code startConnectLoop} now calls {@code client?.close("reconnect")}
 * before spinning a fresh loop. This pins that contract at the controller seam:
 * after K relaunches there is exactly ONE live (un-closed) client, regardless of
 * K — every prior client was closed.
 *
 * <p>Pre-fix this assertion fails: the relaunch left each prior client open, so
 * K+1 live clients accumulated.
 *
 * <p>Driven on the controller's real (Default-dispatcher) scope with bounded
 * polling — the connect loop is given a client whose state is permanently Open,
 * so each loop parks on the open connection (one client per relaunch, no
 * back-off churn) until the next [ConversationController.userTriggeredReconnect].
 */
class ConversationReconnectOrphanTest {

    private val created = CopyOnWriteArrayList<ConversationClient>()
    private var controller: ConversationController? = null

    @AfterEach
    fun tearDown() {
        controller?.close("test-teardown")
    }

    /** A profile store whose suspend `current()` returns a profile (see SessionEventsControllerStatusTest). */
    private fun profileStoreReturning(profile: ServerProfile?): ServerProfileStore =
        Mockito.mock(ServerProfileStore::class.java, Answer { inv ->
            if (inv.method.name == "current") profile else Mockito.RETURNS_DEFAULTS.answer(inv)
        })

    /** A client that parks the loop at Open: state stays Open, incoming never emits. */
    private fun openClient(): ConversationClient {
        val c = mock(ConversationClient::class.java)
        Mockito.`when`(c.state).thenReturn(MutableStateFlow(ConversationClient.State.Open))
        Mockito.`when`(c.incoming).thenReturn(MutableSharedFlow())
        Mockito.`when`(c.streamId).thenReturn("conv-test")
        Mockito.`when`(c.sendEnumerate()).thenReturn(true)
        Mockito.`when`(c.sendSelectTarget(anyString())).thenReturn(true)
        return c
    }

    /**
     * Wait until the loop has created [n] clients AND the latest is parked Open.
     * The Open gate matters: the loop increments [created] (inside the factory)
     * one statement BEFORE it assigns the `client` field + sets state Open, so
     * gating only on [created] could relaunch in the gap and orphan a client the
     * relaunch never saw. Open is reached only after `client = c` ran.
     */
    private fun awaitClientLive(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while ((created.size < n || controller?.state?.value != TerminalState.Open) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertThat(created.size).`as`("loop created client #$n within the timeout").isGreaterThanOrEqualTo(n)
        assertThat(controller?.state?.value).`as`("client #$n is parked Open").isEqualTo(TerminalState.Open)
    }

    @Test
    fun `repeated reconnect closes each prior client so only one stays live`() {
        val relaunches = 5
        controller = ConversationController(
            sessionN = 7,
            profileStore = profileStoreReturning(mock(ServerProfile::class.java)),
            httpClientFactory = { mock(AiSandboxHttpClient::class.java) },
            clientFactory = { _, _ -> openClient().also { created.add(it) } },
            onClosed = {},
        )

        controller!!.attach(7)
        awaitClientLive(1)

        repeat(relaunches) { i ->
            controller!!.userTriggeredReconnect()
            awaitClientLive(i + 2) // attach made #1; each relaunch makes the next
        }

        // attach + `relaunches` reconnects → relaunches+1 clients created total.
        assertThat(created).hasSize(relaunches + 1)

        // Every client EXCEPT the last must have been closed with "reconnect"
        // before the next relaunch — so at most one client is ever live at once.
        for (i in 0 until created.size - 1) {
            verify(created[i], timeout(2_000)).close("reconnect")
        }
        // The latest client is still live (owns the current, healthy connection).
        verify(created.last(), never()).close(anyString())
    }
}
