package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UC-24 — LIVE tmux invariant check against the host's real {@code tmux} (no
 * Docker, no pty4j). The mocked {@code prepareClientSession} argv test
 * ({@code TmuxBridgeSessionSetupTest}) pins WHICH commands the fix emits; this
 * harness proves the tmux SEMANTICS those commands rely on actually hold —
 * something a mocked {@link ProcessExecutor} can never demonstrate.
 *
 * <p>It reproduces the bridge's grouped-session topology exactly: a source
 * {@code swarm} session whose single window holds &gt;1 pane (mirroring a
 * {@code claude-swarm} teammate window), plus a per-client session created with
 * {@code new-session -t swarm} (mirroring {@code prepareClientSession} step 1,
 * sans the {@code docker compose exec} transport wrapper, which is a pure
 * pass-through for tmux argv — see /tmp/uc24-live-evidence.md, analyst, tmux
 * 3.3a, the version Debian bookworm ships).
 *
 * <p>The invariants asserted (UC-24 AC#2/#3, pitfall #3; UC-21 AC#1/#12):
 *
 * <ul>
 *   <li><b>Idempotent zoom</b> — the fix's needed-only zoom (zoom iff &gt;1 pane
 *       AND not already zoomed) keeps the window zoomed across repeated bridges,
 *       whereas the pre-fix <i>unconditional</i> {@code resize-pane -Z} TOGGLES
 *       zoom OFF on the second call (the root-cause regression). Both behaviours
 *       are exercised so the toggle bug stays locked out.</li>
 *   <li><b>Cross-pane switch</b> — {@code select-pane} to a different pane
 *       auto-unzooms the window (tmux semantics), and re-running the needed-only
 *       zoom restores the single zoomed pane. This is why the bridge re-applies
 *       the zoom on every (re-)bridge.</li>
 *   <li><b>{@code status off} is per-session</b> — disabling the status line on
 *       the per-client session does NOT change the source session's status
 *       option, so the orchestrator's chrome is untouched (the clean
 *       no-leak guarantee).</li>
 *   <li><b>Single-pane window is never zoomed</b> — the {@code >1 pane} guard
 *       holds against real tmux (the one-pane-per-window churn case).</li>
 * </ul>
 *
 * <p><b>Honest scope.</b> The zoom flag IS shared window state across grouped
 * sessions (analyst-confirmed pitfall #3: a per-client zoom is visible on the
 * source). The fix does NOT isolate the zoom flag — it mitigates by never
 * zooming unnecessarily ({@code >1}-pane-and-not-already-zoomed) so a re-bridge
 * cannot toggle a teammate's view, and by keeping {@code status off}
 * per-session. {@link #zoomLeakIsSharedWindowState_documentedNotAsserted_butStatusDoesNot()}
 * records the shared-flag reality rather than pretending isolation exists. The
 * full end-to-end proof against a real Claude Code swarm in a live container
 * remains operator/DinD-gated ({@code SwarmBridgeIT}); this harness covers the
 * tmux mechanics it depends on.
 *
 * <p>Guarded by a JUnit assumption: where {@code tmux} is unavailable the test
 * skips; where present (this ai-sandbox session, Debian bookworm CI) it runs
 * with real assertions as part of {@code :server:test}.
 */
@DisplayName("UC-24 host-tmux zoom / status invariants")
class TmuxZoomHostTmuxTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ZOOM_QUERY = "#{window_panes} #{window_zoomed_flag}";

    private final ProcessExecutor exec = new ProcessExecutor();
    private String label;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping live tmux invariant check");
        // Unique server label per JVM so parallel/forked runs never collide.
        label = "uc24-host-" + ProcessHandle.current().pid();
        killServer();
        // Source "swarm" session: one window, split into TWO panes (the
        // claude-swarm teammate-window topology UC-21 verified live).
        tmuxOk("new-session", "-d", "-s", "swarm", "-x", "200", "-y", "50");
        tmuxOk("split-window", "-t", "swarm");
        assertThat(flag("swarm", "#{window_panes}"))
                .as("source window has 2 panes")
                .isEqualTo("2");
    }

    @AfterEach
    void tearDown() {
        if (label != null) {
            killServer();
        }
    }

    // ── idempotent zoom vs. the unconditional toggle ────────────────────────

    @Test
    @DisplayName("needed-only zoom stays zoomed across re-bridges; unconditional -Z toggles it off")
    void idempotentZoom_staysZoomed_whereUnconditionalToggleWouldNot() throws Exception {
        tmuxOk("new-session", "-d", "-s", "client-A", "-t", "swarm");
        tmuxOk("select-window", "-t", "client-A:0");
        tmuxOk("select-pane", "-t", "client-A:0.0");

        // The fix: needed-only zoom, applied as many times as a client re-bridges.
        zoomIfNeeded("client-A", "client-A:0.0");
        assertThat(flag("client-A", "#{window_zoomed_flag}"))
                .as("zoomed after first bridge")
                .isEqualTo("1");
        zoomIfNeeded("client-A", "client-A:0.0");
        zoomIfNeeded("client-A", "client-A:0.0");
        assertThat(flag("client-A", "#{window_zoomed_flag}"))
                .as("STILL zoomed after repeated re-bridges (idempotent)")
                .isEqualTo("1");

        // The pre-fix bug: a single UNCONDITIONAL toggle from the zoomed state
        // flips zoom OFF — exactly the "all panes shown" regression.
        tmuxOk("resize-pane", "-Z", "-t", "client-A:0.0");
        assertThat(flag("client-A", "#{window_zoomed_flag}"))
                .as("unconditional resize-pane -Z toggles zoom OFF — the regression the fix removes")
                .isEqualTo("0");
    }

    // ── cross-pane switch ───────────────────────────────────────────────────

    @Test
    @DisplayName("cross-pane select unzooms; a re-bridge restores the single zoomed pane")
    void crossPaneSwitch_unzooms_andRebridgeRestoresZoom() throws Exception {
        tmuxOk("new-session", "-d", "-s", "client-B", "-t", "swarm");
        tmuxOk("select-window", "-t", "client-B:0");
        tmuxOk("select-pane", "-t", "client-B:0.0");
        zoomIfNeeded("client-B", "client-B:0.0");
        assertThat(flag("client-B", "#{window_zoomed_flag}")).isEqualTo("1");

        // A cross-pane switch (select a DIFFERENT pane) auto-unzooms in tmux.
        tmuxOk("select-pane", "-t", "client-B:0.1");
        assertThat(flag("client-B", "#{window_zoomed_flag}"))
                .as("tmux auto-unzooms the window on a cross-pane select")
                .isEqualTo("0");

        // The bridge re-applies the needed-only zoom on the (re-)bridge after the
        // switch → single zoomed pane again, now on the newly selected pane.
        zoomIfNeeded("client-B", "client-B:0.1");
        assertThat(flag("client-B", "#{window_zoomed_flag}"))
                .as("re-bridge restores the zoom after a cross-pane switch")
                .isEqualTo("1");
        assertThat(flag("client-B", "#{pane_active}"))
                .as("the newly selected pane is the active (visible) one")
                .isEqualTo("1");
    }

    // ── status off is per-session ───────────────────────────────────────────

    @Test
    @DisplayName("status off on the per-client session does not leak to the source/orchestrator")
    void statusOff_isPerSession_doesNotLeakToSource() throws Exception {
        tmuxOk("new-session", "-d", "-s", "client-C", "-t", "swarm");
        tmuxOk("set-option", "-t", "client-C", "status", "off");

        assertThat(sessionStatus("client-C")).as("per-client status disabled").isEqualTo("off");
        assertThat(sessionStatus("swarm"))
                .as("source/orchestrator status is NOT forced off by the per-client set-option")
                .isNotEqualTo("off");
    }

    // ── single-pane window is never zoomed ──────────────────────────────────

    @Test
    @DisplayName(">1-pane guard: a single-pane window is not zoomed")
    void singlePaneWindow_isNotZoomed() throws Exception {
        // A separate single-pane source (the one-pane-per-window churn case).
        tmuxOk("new-session", "-d", "-s", "solo", "-x", "200", "-y", "50");
        assertThat(flag("solo", "#{window_panes}")).isEqualTo("1");
        tmuxOk("new-session", "-d", "-s", "client-D", "-t", "solo");
        tmuxOk("select-window", "-t", "client-D:0");
        tmuxOk("select-pane", "-t", "client-D:0.0");

        zoomIfNeeded("client-D", "client-D:0.0");
        assertThat(flag("client-D", "#{window_zoomed_flag}"))
                .as("single-pane window is never zoomed (nothing to collapse)")
                .isEqualTo("0");
    }

    // ── honest documentation of the shared-flag reality ─────────────────────

    @Test
    @DisplayName("pitfall #3: the zoom flag is shared across grouped sessions (documented, not isolated)")
    void zoomLeakIsSharedWindowState_documentedNotAsserted_butStatusDoesNot() throws Exception {
        tmuxOk("new-session", "-d", "-s", "client-E", "-t", "swarm");
        tmuxOk("select-pane", "-t", "client-E:0.0");
        zoomIfNeeded("client-E", "client-E:0.0");

        // This is NOT a guarantee the fix provides — it records the analyst's
        // confirmed reality so a future reader does not mistake the fix for zoom
        // isolation. The mitigation is "never zoom unnecessarily", not isolation.
        assertThat(flag("swarm", "#{window_zoomed_flag}"))
                .as("zoom IS shared window state across the grouped source session (pitfall #3 is real)")
                .isEqualTo("1");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Mirrors {@link com.aisandbox.server.stream.service.TmuxBridgeService}'s needed-only zoom decision. */
    private void zoomIfNeeded(String session, String paneSpec) throws IOException {
        String out = flag(paneSpec, ZOOM_QUERY);
        boolean zoom;
        String[] parts = out == null ? new String[0] : out.trim().split("\\s+");
        if (parts.length < 2) {
            zoom = true; // query failure → fall back to attempting the zoom (matches the fix)
        } else {
            boolean singlePane = "1".equals(parts[0]);
            boolean alreadyZoomed = "1".equals(parts[1]);
            zoom = !singlePane && !alreadyZoomed;
        }
        if (zoom) {
            tmuxOk("resize-pane", "-Z", "-t", paneSpec);
        }
    }

    private String flag(String target, String format) throws IOException {
        ProcessExecutor.Result r = tmux("display-message", "-p", "-t", target, format);
        return r.exitCode() == 0 ? r.stdout().trim() : null;
    }

    /** Session-level {@code status} option value ("" when unset/inherited, never "off"). */
    private String sessionStatus(String session) throws IOException {
        ProcessExecutor.Result r = tmux("show-options", "-t", session, "-v", "status");
        return r.stdout().trim();
    }

    private ProcessExecutor.Result tmux(String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of("tmux", "-L", label));
        argv.addAll(List.of(args));
        return exec.run(argv, null, TIMEOUT);
    }

    private void tmuxOk(String... args) throws IOException {
        ProcessExecutor.Result r = tmux(args);
        assertThat(r.exitCode())
                .as("tmux %s → stderr=%s", String.join(" ", args), r.stderr())
                .isZero();
    }

    private void killServer() {
        try {
            exec.run(List.of("tmux", "-L", label, "kill-server"), null, TIMEOUT);
        } catch (IOException ignored) {
            // best-effort teardown
        }
    }

    private static boolean tmuxAvailable() {
        try {
            return new ProcessExecutor()
                            .run(List.of("tmux", "-V"), null, Duration.ofSeconds(5))
                            .exitCode()
                    == 0;
        } catch (IOException e) {
            return false;
        }
    }
}
