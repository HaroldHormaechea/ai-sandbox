package com.aisandbox.android.terminal

import android.content.Context
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.StreamClient
import com.aisandbox.android.ui.screens.TerminalState
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * UC-21 — pure-JVM coverage for [TerminalStreamController], the process-scoped
 * owner of one session's stream. Runs on the JUnit-5 (Jupiter) unit lane
 * (`:android:testDebugUnitTest`) — no Robolectric, no emulator, no real network.
 *
 * <h2>What this pins</h2>
 *
 * <ul>
 *   <li>AC#13 client mirror — [TerminalStreamController.onControlFrame] parses
 *       the server's {@code targets} / {@code target-selected} / {@code error}
 *       frames into the [targets] / [selectedTargetId] state, degrading
 *       gracefully on malformed / unknown frames (driven by reflection so the
 *       parse contract is tested deterministically, without the reconnect
 *       loop's coroutines / suspend network calls).</li>
 *   <li>AC#11 — [selectTarget] optimistically records the selection AND routes
 *       to the existing client via {@code sendSelectTarget} + a re-asserting
 *       {@code sendResize} (no reconnect — the {@code streamClientFactory} is
 *       never re-invoked).</li>
 *   <li>AC#4 — [sendResize] mirrors geometry and ignores non-positive dims.</li>
 *   <li>AC#7 — [close] tears the client down and reports closed.</li>
 *   <li>AC#10 — the always-present main target id is {@code "main"}; the
 *       controller starts on it with an empty target row (AC#12 hidden state).</li>
 * </ul>
 *
 * <p>The streamClient is normally private and assigned only inside the connect
 * loop; the reflection seam below injects a mock so the no-reconnect routing +
 * teardown paths are exercised without spinning the loop (which would require
 * stubbing suspend {@code StreamClient.connect()} and
 * {@code ServerProfileStore.current()} — fragile under plain Mockito). The
 * enumerate-on-open + reconnect-re-apply loop wiring is the integration concern
 * verified by {@code StreamClientControlFrameTest} (the wire JSON) plus review.
 */
class TerminalStreamControllerTest {

    private val factoryCount = AtomicInteger(0)
    private val closedSessions = mutableListOf<Int>()
    private lateinit var controller: TerminalStreamController

    @BeforeEach
    fun setUp() {
        val ctx = mock(Context::class.java)
        controller = TerminalStreamController(
            appContext = ctx,
            sessionN = 7,
            profileStore = mock(ServerProfileStore::class.java),
            httpClientFactory = { mock(AiSandboxHttpClient::class.java) },
            streamClientFactory = { _, _ ->
                factoryCount.incrementAndGet()
                mock(StreamClient::class.java)
            },
            onClosed = { closedSessions.add(it) },
        )
    }

    // ── initial state ───────────────────────────────────────────────────────

    @Test
    fun `starts on the main target with an empty switcher row`() {
        assertThat(TerminalStreamController.MAIN_TARGET_ID).isEqualTo("main")
        assertThat(controller.selectedTargetId.value).isEqualTo("main")
        assertThat(controller.targets.value).isEmpty()
        assertThat(controller.state.value).isEqualTo(TerminalState.Idle)
    }

    // ── AC#13 — control-frame parse (client mirror) ──────────────────────────

    @Test
    fun `targets frame populates the switcher with metadata and applies selectedId`() {
        onControlFrame(
            """
            {"type":"targets","targets":[
              {"id":"main","kind":"main","title":"main"},
              {"id":"swarm:claude-swarm-1:0.0","kind":"swarm","title":"agent ping",
               "agentName":"ping","agentType":"general-purpose","agentColor":"blue","teamName":"pingpong"}
            ],"selectedId":"swarm:claude-swarm-1:0.0"}
            """.trimIndent(),
        )

        val targets = controller.targets.value
        assertThat(targets).hasSize(2)
        assertThat(targets[0].id).isEqualTo("main")
        val ping = targets[1]
        assertThat(ping.kind).isEqualTo("swarm")
        assertThat(ping.title).isEqualTo("agent ping")
        assertThat(ping.agentName).isEqualTo("ping")
        assertThat(ping.agentType).isEqualTo("general-purpose")
        assertThat(ping.agentColor).isEqualTo("blue")
        assertThat(ping.teamName).isEqualTo("pingpong")
        // AC#13 — the server's current selection is mirrored.
        assertThat(controller.selectedTargetId.value).isEqualTo("swarm:claude-swarm-1:0.0")
    }

    @Test
    fun `target title falls back to agentName then id when absent`() {
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:s:0.1","kind":"swarm","agentName":"pong"},
                 {"id":"swarm:s:0.2","kind":"swarm"}
               ],"selectedId":"main"}""",
        )
        val targets = controller.targets.value
        assertThat(targets).hasSize(3)
        assertThat(targets[1].title).isEqualTo("pong") // title absent -> agentName
        assertThat(targets[2].title).isEqualTo("swarm:s:0.2") // title + agentName absent -> id
    }

    @Test
    fun `target-selected frame updates the selection`() {
        onControlFrame("""{"type":"target-selected","targetId":"swarm:claude-swarm-1:0.1"}""")
        assertThat(controller.selectedTargetId.value).isEqualTo("swarm:claude-swarm-1:0.1")
    }

    @Test
    fun `malformed, unknown and error frames are ignored without disturbing state`() {
        // Seed a known-good target list first.
        onControlFrame("""{"type":"targets","targets":[{"id":"main","kind":"main","title":"main"}],"selectedId":"main"}""")
        val before = controller.targets.value

        onControlFrame("not-json-at-all")
        onControlFrame("""{"type":"bogus-frame"}""")
        onControlFrame("""{"type":"error","code":"rebridge_failed","title":"x","detail":"y"}""")

        assertThat(controller.targets.value).isEqualTo(before)
        assertThat(controller.selectedTargetId.value).isEqualTo("main")
    }

    // ── UC-24 AC#2/#12 — periodic re-enumeration tracks spawn/despawn ─────────
    //
    // The controller fires `sendEnumerate()` every ENUMERATE_INTERVAL_MS while
    // Open (verified by review + the live emulator smoke). Each resulting
    // `targets` frame is applied here by REPLACING `_targets` wholesale, so a
    // teammate pane that appeared since the last enumerate shows up and one that
    // despawned drops its tile — no stale-tile accumulation, no merge bookkeeping.
    // These pin that replacement contract deterministically (the substance of
    // "tiles appear/disappear as panes/agents spawn/despawn") without driving the
    // reconnect-loop coroutines (no kotlinx-coroutines-test on the unit lane).

    @Test
    fun `a spawned pane appears in the switcher on the next enumerate frame`() {
        // First enumerate — only main + one teammate.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )
        assertThat(controller.targets.value.map { it.id }).containsExactly("main", "swarm:main:0.1")

        // A second teammate pane spawned — the next enumerate frame surfaces it.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:main:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"main"}""",
        )
        assertThat(controller.targets.value.map { it.id }).containsExactly("main", "swarm:main:0.1", "swarm:main:0.2")
    }

    @Test
    fun `a despawned pane drops its tile on the next enumerate frame`() {
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:main:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"main"}""",
        )
        assertThat(controller.targets.value).hasSize(3)

        // bob's pane despawned — the next enumerate frame no longer lists it, and
        // the wholesale replacement drops the stale tile (no accumulation).
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )
        assertThat(controller.targets.value.map { it.id }).containsExactly("main", "swarm:main:0.1")
        assertThat(controller.targets.value).noneMatch { it.id == "swarm:main:0.2" }
    }

    @Test
    fun `default-socket main-pane tiles parse with their disambiguated titles`() {
        // Mirrors the UC-24 server tile shape: ids are swarm:main:<w>.<p> and two
        // same-type unnamed teammates carry distinct ·<w>.<p> titles.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","title":"general-purpose ·0.1","agentType":"general-purpose"},
                 {"id":"swarm:main:0.2","kind":"swarm","title":"general-purpose ·0.2","agentType":"general-purpose"}
               ],"selectedId":"main"}""",
        )
        val targets = controller.targets.value
        assertThat(targets.map { it.title })
            .containsExactly("main", "general-purpose ·0.1", "general-purpose ·0.2")
        assertThat(targets[1].title).isNotEqualTo(targets[2].title)
        assertThat(targets[1].agentName).isNull()
    }

    @Test
    fun `the re-enumeration cadence is the UC-24 3s interval`() {
        // Pins the AC#12 mid-stream re-enumeration cadence so a future change to
        // the loop's tempo is a conscious, reviewed edit.
        assertThat(TerminalStreamController.ENUMERATE_INTERVAL_MS).isEqualTo(3_000L)
    }

    // ── AC#11 — selectTarget: optimistic + routes + no reconnect ──────────────

    @Test
    fun `selectTarget optimistically records the selection even when not connected`() {
        controller.selectTarget("swarm:claude-swarm-1:0.1")
        assertThat(controller.selectedTargetId.value).isEqualTo("swarm:claude-swarm-1:0.1")
        // No connect attempt was triggered.
        assertThat(factoryCount.get()).isZero()
    }

    @Test
    fun `selectTarget routes to the live client via sendSelectTarget plus resize without reconnecting`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        controller.selectTarget("swarm:claude-swarm-1:0.1")

        assertThat(controller.selectedTargetId.value).isEqualTo("swarm:claude-swarm-1:0.1")
        verify(client).sendSelectTarget("swarm:claude-swarm-1:0.1")
        // Re-asserts the current geometry on the switched PTY (AC#4 interplay);
        // defaults are 80x24 until a resize arrives.
        verify(client).sendResize(80, 24)
        // AC#11 — switching is mid-stream; the client is NOT rebuilt.
        assertThat(factoryCount.get()).isZero()
    }

    // ── AC#4 — resize mirroring ──────────────────────────────────────────────

    @Test
    fun `sendResize mirrors geometry and ignores non-positive dimensions`() {
        controller.sendResize(120, 40)
        assertThat(controller.size.value).isEqualTo(TermSize(120, 40))

        controller.sendResize(0, 24)
        controller.sendResize(80, -1)
        assertThat(controller.size.value).isEqualTo(TermSize(120, 40))
    }

    // ── AC#7 — explicit teardown ──────────────────────────────────────────────

    @Test
    fun `close tears down the client and reports closed`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        controller.close("user-disconnect")

        verify(client).close("user-disconnect")
        assertThat(controller.state.value).isEqualTo(TerminalState.Idle)
        assertThat(closedSessions).containsExactly(7)
    }

    // ── UC-33 — client re-assert backstop on mid-stream target-set change ─────
    //
    // When `enumerate` reports the target/pane id-SET changed mid-stream (a
    // subagent spawned / despawned while attached), the controller re-asserts the
    // CURRENTLY-selected target on the existing client (sendSelectTarget(current)
    // + sendResize) so the server re-bridges → re-zooms and the transient split
    // collapses without waiting for the operator to tap a tile. The very first
    // `targets` frame (empty prior) is suppressed; an UNCHANGED set is suppressed;
    // it fires even when the selection is `main` (the mid-stream-split main case).

    @Test
    fun `set grows mid-stream re-asserts the selected target (main) on the live client`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        // First frame — empty prior, no re-assert (the initial bridge already zoomed).
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )
        verify(client, never()).sendSelectTarget(anyString())

        // A teammate pane spawned mid-stream → the id-set grew → re-assert `main`.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:main:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"main"}""",
        )
        // Re-asserts the still-selected main target exactly once, plus geometry
        // (defaults 80x24 until a resize arrives) — fires even on `main`.
        verify(client, times(1)).sendSelectTarget("main")
        verify(client).sendResize(80, 24)
        assertThat(controller.selectedTargetId.value).isEqualTo("main")
    }

    @Test
    fun `set shrinks mid-stream re-asserts the selected target`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:main:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"main"}""",
        )
        verify(client, never()).sendSelectTarget(anyString())

        // bob despawned → the id-set shrank → still a mid-stream change → re-assert.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )
        verify(client, times(1)).sendSelectTarget("main")
        verify(client).sendResize(80, 24)
    }

    @Test
    fun `an unchanged target set does not re-assert (no redundant select per enumerate)`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        val frame =
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}"""
        onControlFrame(frame) // initial
        onControlFrame(frame) // same id-set on the next enumerate

        // No re-assert on either frame — avoids re-zooming (and risking a toggle)
        // every 3s when nothing changed (the pitfall the diff guards against).
        verify(client, never()).sendSelectTarget(anyString())
        verify(client, never()).sendResize(anyInt(), anyInt())
    }

    @Test
    fun `the first targets frame after connect never re-asserts`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )

        // Empty prior set → suppressed; the initial bridge already zoomed server-side.
        verify(client, never()).sendSelectTarget(anyString())
        verify(client, never()).sendResize(anyInt(), anyInt())
    }

    @Test
    fun `a mid-stream change with no live client does not crash`() {
        // streamClient stays null (never connected / dropped). The re-assert must
        // be a safe no-op rather than NPE.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"main"}""",
        )
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:main:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:main:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"main"}""",
        )
        // No exception thrown; the switcher row still tracked the change.
        assertThat(controller.targets.value.map { it.id })
            .containsExactly("main", "swarm:main:0.1", "swarm:main:0.2")
    }

    @Test
    fun `a non-main selection is re-asserted with its own id on a mid-stream change`() {
        val client = mock(StreamClient::class.java)
        setStreamClient(client)

        // Operator is parked on a teammate; first frame is the initial (no re-assert).
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:s:0.1","kind":"swarm","agentName":"alice"}
               ],"selectedId":"swarm:s:0.1"}""",
        )
        assertThat(controller.selectedTargetId.value).isEqualTo("swarm:s:0.1")
        verify(client, never()).sendSelectTarget(anyString())

        // Another teammate spawns → re-assert the CURRENT (non-main) selection.
        onControlFrame(
            """{"type":"targets","targets":[
                 {"id":"main","kind":"main","title":"main"},
                 {"id":"swarm:s:0.1","kind":"swarm","agentName":"alice"},
                 {"id":"swarm:s:0.2","kind":"swarm","agentName":"bob"}
               ],"selectedId":"swarm:s:0.1"}""",
        )
        verify(client, times(1)).sendSelectTarget("swarm:s:0.1")
        verify(client, never()).sendSelectTarget("main")
    }

    // ── reflection seams ─────────────────────────────────────────────────────

    private fun onControlFrame(text: String) {
        val m = TerminalStreamController::class.java.getDeclaredMethod("onControlFrame", String::class.java)
        m.isAccessible = true
        m.invoke(controller, text)
    }

    private fun setStreamClient(client: StreamClient?) {
        val f = TerminalStreamController::class.java.getDeclaredField("streamClient")
        f.isAccessible = true
        f.set(controller, client)
    }
}
