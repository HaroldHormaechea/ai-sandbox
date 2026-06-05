package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UC-33 Part B — LIVE host-{@code tmux} invariants for the
 * {@code window-layout-changed} hook that re-asserts the per-client zoom the
 * instant a mid-stream split changes the shared window's layout.
 *
 * <p>These tests drive the PRODUCTION
 * {@link TmuxBridgeService#buildLayoutChangedHookCommand(String, String, String)}
 * string (never a hand-written one) and install it on a real {@code tmux}
 * server, then exercise the exact runtime semantics a mocked
 * {@link ProcessExecutor} can never demonstrate:
 *
 * <ul>
 *   <li><b>Race-freedom storm (the sign-off gate)</b> — consecutive foreign
 *       {@code split-window}s with no inter-split settle, and ≥10 sequential
 *       mid-stream spawns, must EVERY time converge at idle to
 *       {@code active == mainPaneId && window_zoomed_flag == 1}, including from a
 *       <i>zoomed-on-the-wrong-pane</i> start state — and must stay bounded (no
 *       continued hook action during a post-storm idle window).</li>
 *   <li><b>{@code ##{…}} escaping reads SETTLED state</b> — the hook's inner
 *       {@code display-message} must read the post-split, post-debounce state,
 *       not the hook-fire-time state. This is the developer's flagged
 *       assumption; it is live-verified here against this tmux build.</li>
 *   <li><b>Pin (MAIN) vs. active-zoom (agent/swarm)</b> — the MAIN hook yanks
 *       focus back to the pinned pane (option-C); the agent hook re-zooms the
 *       active pane WITHOUT moving it (no focus-war — AC#5).</li>
 *   <li><b>Toggle-safety</b> — the hook's own {@code resize-pane -Z} re-fires
 *       {@code window-layout-changed}; the {@code >1 pane && !zoomed} guard must
 *       prevent that re-fire from un-zooming a correct view (AC#4, pitfall).</li>
 * </ul>
 *
 * <p>The production hook body uses a bare in-container {@code tmux} (the
 * default-socket / {@code socket == null} form). When tmux runs the hook's
 * {@code run-shell}, the child shell inherits {@code $TMUX}, so the bare
 * {@code tmux} re-attaches to THIS very test server — which is why these tests
 * can drive the shipped default-socket string verbatim against a private
 * {@code -L <label>} server. (Empirically confirmed on tmux 3.3a, the version
 * Debian bookworm / this ai-sandbox session ships.)
 *
 * <p>Guarded by a JUnit assumption: where {@code tmux} is unavailable the test
 * skips; where present it runs as part of {@code :server:test}.
 */
@DisplayName("UC-33 host-tmux window-layout-changed hook invariants")
class TmuxLayoutHookHostTmuxTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Convergence budget per assertion — generous so a real flap (not slowness) trips the gate. */
    private static final long CONVERGE_MS = 3_000L;

    /** Monotonic per-JVM counter so every test method gets its OWN tmux server + session. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final ProcessExecutor exec = new ProcessExecutor();
    private String label;
    private String session;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping UC-33 live hook check");
        // A UNIQUE label + session per test method. The production hook runs its
        // body via `run-shell -b` (backgrounded), so a hook from one test may still
        // be inside its `sleep 0.2` debounce when the next test starts. A shared
        // label/session would let that lingering hook (esp. the MAIN variant's
        // `select-pane`) fire against the next test's same-named session and
        // corrupt its active pane. Unique names per method isolate them; the
        // production lock path is /tmp/pin-<session>.lock, also unique here.
        long pid = ProcessHandle.current().pid();
        int seq = SEQ.incrementAndGet();
        label = "uc33-hook-" + pid + "-" + seq;
        session = "c" + pid + "x" + seq;
        killServer();
        deleteArtifacts();
    }

    @AfterEach
    void tearDown() {
        if (label != null) {
            killServer();
        }
        deleteArtifacts();
    }

    // ── (1) the gate: race-freedom storm — no inter-split settle ─────────────

    @Test
    @DisplayName(
            "storm of foreign splits (no inter-split settle) converges to pinned+zoomed main, even from a wrong-pane zoom")
    void rapidForeignSplitStorm_convergesToPinnedZoomedMain_andStaysBounded() throws Exception {
        newSession(300, 80);
        String main = activePane();
        installHook(main); // MAIN target — pinned

        // Wrong-pane start state: split, then zoom the NEW (non-main) pane so the
        // window starts zoomed on the wrong pane (active != main, zoomed == 1).
        tmuxOk("split-window", "-t", session);
        tmuxOk("resize-pane", "-Z");
        assertThat(activePane()).as("storm starts zoomed on a non-main pane").isNotEqualTo(main);

        // STORM — many consecutive foreign splits with ZERO inter-split settle.
        // Splits that can't fit (window too small) simply no-op; ≥2 panes remain.
        for (int i = 0; i < 14; i++) {
            tmux("split-window", "-d", "-t", session); // best-effort, no settle, no assert
        }

        assertThat(awaitConverged(main, CONVERGE_MS))
                .as("after a no-settle storm the hook converges: active==mainPaneId && zoomed==1 (flock race-free)")
                .isTrue();

        // Bounded — during a post-storm idle window (no layout changes) the hook
        // must take NO further action: the state stays pinned+zoomed and never
        // flaps. (Any continued/looping hook action would surface as a toggle.)
        for (int i = 0; i < 6; i++) {
            Thread.sleep(140);
            assertThat(flag(session, "#{window_zoomed_flag}"))
                    .as("idle sample %d — still zoomed (no continued hook action)", i)
                    .isEqualTo("1");
            assertThat(activePane())
                    .as("idle sample %d — still pinned to main", i)
                    .isEqualTo(main);
        }
    }

    @Test
    @DisplayName("≥10 sequential mid-stream spawns each re-converge to pinned+zoomed main")
    void everyForeignSplitReconverges_acrossTenPlusIterations() throws Exception {
        newSession(240, 60);
        String main = activePane();
        installHook(main);

        for (int i = 0; i < 11; i++) {
            // A mid-stream subagent spawn: a foreign split that does NOT steal
            // focus (-d). The hook must re-zoom the pinned main pane.
            String spawned = splitCapture();
            assertThat(awaitConverged(main, CONVERGE_MS))
                    .as("iteration %d — converged to pinned+zoomed main", i)
                    .isTrue();
            // Reset to a single pane to keep room for the next iteration; the
            // kill's own layout-change hook (a no-op on a 1-pane window) is given
            // a moment to release the per-session flock before the next split.
            tmuxOk("kill-pane", "-t", spawned);
            Thread.sleep(260);
        }
    }

    // ── (2) ##{…} escaping reads SETTLED state ───────────────────────────────

    @Test
    @DisplayName("the production hook reads SETTLED post-split state (## escaping correct on this tmux build)")
    void productionHook_readsSettledState_zoomsAfterDebounce() throws Exception {
        newSession(240, 60);
        String main = activePane();
        installHook(main);
        assertThat(flag(session, "#{window_panes}"))
                .as("starts single-pane, unzoomed")
                .isEqualTo("1");

        // Mid-stream foreign split (origin keeps focus via -d). If the ##{…}
        // escaping were wrong for this build, the hook's inner
        // `display-message -p '#{window_panes} #{window_zoomed_flag}'` would NOT
        // yield two numeric, SETTLED fields → the `[ $1 -gt 1 ] && [ $2 = 0 ]`
        // guard would never pass → the window would stay unzoomed-at-idle and this
        // assertion fails. A green assertion proves the inner reads ran against the
        // settled (post-split) state, not the fire-time literal.
        tmuxOk("split-window", "-d", "-t", session);
        assertThat(awaitConverged(main, CONVERGE_MS))
                .as("hook zoomed the pinned pane from a SETTLED 2-pane read (escaping is correct)")
                .isTrue();
    }

    @Test
    @DisplayName(
            "tmux 3.3a: ##{...} collapses to #{...} and the INNER display-message expands it post-debounce (settled)")
    void hashHashEscaping_collapsesAndDefersInnerExpansion() throws Exception {
        // Documents — live — the precise tmux semantic the production hook relies
        // on (the developer's flagged assumption). A run-shell argument written
        // ##{...} is delivered to the shell as literal #{...} (## -> #), so the
        // INNER `tmux display-message -p '#{...}'` expands it at RUN time, AFTER
        // the debounce sleep, against the settled layout. If `##` were instead a
        // single `#`, run-shell would expand it at fire time; if `##` did not
        // collapse on this build, the shell would echo the literal token.
        newSession(200, 50);
        Path out = artifact("escaping");
        Files.deleteIfExists(out);

        // Hand-built probe hook (NOT the production string — this test asserts the
        // tmux BUILD semantic; the production string is exercised by the sibling
        // tests). bare tmux -> $TMUX -> this -L server.
        String probeBody = "( sleep 0.2; echo settled=$(tmux display-message -p -t " + session
                + " '##{window_panes}') > " + out + " )";
        String probeHook = "run-shell -b \"" + probeBody + "\"";
        tmuxOk("set-hook", "-t", session, "window-layout-changed", probeHook);

        // Split AFTER installing the hook: settled #{window_panes} == 2.
        tmuxOk("split-window", "-d", "-t", session);

        assertThat(awaitFileContains(out, "settled=2", CONVERGE_MS))
                .as("inner display-message expanded the collapsed #{window_panes} to the SETTLED value (2)")
                .isTrue();
    }

    // ── (3) pin (MAIN) vs. active-zoom (agent/swarm) ─────────────────────────

    @Test
    @DisplayName("MAIN hook yanks focus back to the pinned pane after a foreign split steals it (option-C)")
    void mainHook_reSelectsPinnedPane_whenForeignSplitStealsActive() throws Exception {
        newSession(240, 60);
        String main = activePane();
        installHook(main); // pinned

        // A foreign split WITHOUT -d → the new pane becomes active (a teammate
        // pane grabbing focus). The MAIN hook must re-select the pinned pane.
        tmuxOk("split-window", "-t", session);
        assertThat(activePane()).as("foreign split stole focus from main").isNotEqualTo(main);

        assertThat(awaitConverged(main, CONVERGE_MS))
                .as("MAIN hook re-pins focus to mainPaneId and zooms it")
                .isTrue();
    }

    @Test
    @DisplayName("agent/swarm hook re-zooms the active pane WITHOUT moving it (no focus-war — AC#5)")
    void agentHook_reZoomsActivePane_withoutMovingFocus() throws Exception {
        newSession(240, 60);
        // Operator sits on a teammate pane: split, select pane .1, zoom it.
        tmuxOk("split-window", "-t", session);
        tmuxOk("select-pane", "-t", session + ":.1");
        String teammate = activePane();
        tmuxOk("resize-pane", "-Z", "-t", teammate);
        assertThat(flag(session, "#{window_zoomed_flag}")).isEqualTo("1");

        installHook(null); // agent/swarm target — NOT pinned, active-zoom only

        // A mid-stream foreign split that does NOT steal focus (-d) — the operator
        // is still on `teammate`. The agent hook re-zooms the active pane but must
        // never re-select a different (e.g. pinned) pane.
        String before = activePane();
        tmuxOk("split-window", "-d", "-t", session);
        assertThat(awaitZoomed(CONVERGE_MS))
                .as("agent hook re-asserts the zoom on the active pane")
                .isTrue();
        assertThat(activePane())
                .as("agent hook did NOT move the source swarm active pane (no focus-war)")
                .isEqualTo(before)
                .isEqualTo(teammate);
    }

    // ── (4) toggle-safety: the hook's own re-fire must not un-zoom ───────────

    @Test
    @DisplayName("a converged zoom is stable across idle — the hook's own re-fire never toggles it off")
    void convergedZoom_isStableAcrossIdle_noSelfRefireToggle() throws Exception {
        newSession(240, 60);
        String main = activePane();
        installHook(main);

        tmuxOk("split-window", "-d", "-t", session);
        assertThat(awaitConverged(main, CONVERGE_MS)).as("initial convergence").isTrue();

        // The hook's resize-pane -Z is itself a window-layout-changed event, so the
        // hook re-fires. The >1-pane-&&-!zoomed guard ($2 == 0 is now false) must
        // make that re-fire a strict no-op — no intermittent un-zoom (the single
        // biggest UC-33 trap). Sample across an idle window covering several
        // debounce intervals.
        for (int i = 0; i < 8; i++) {
            Thread.sleep(130);
            assertThat(flag(session, "#{window_zoomed_flag}"))
                    .as("idle sample %d — guard prevents a self re-fire toggle", i)
                    .isEqualTo("1");
            assertThat(activePane()).as("idle sample %d — still pinned", i).isEqualTo(main);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void newSession(int width, int height) throws IOException {
        tmuxOk("new-session", "-d", "-s", session, "-x", Integer.toString(width), "-y", Integer.toString(height));
    }

    /** Install the SHIPPED production hook (default-socket / bare-tmux form) for this session. */
    private void installHook(String mainPaneId) throws IOException {
        String hook = TmuxBridgeService.buildLayoutChangedHookCommand(null, session, mainPaneId);
        tmuxOk("set-hook", "-t", session, "window-layout-changed", hook);
    }

    /** Split without stealing focus; return the new pane's stable {@code #{pane_id}}. */
    private String splitCapture() throws IOException {
        ProcessExecutor.Result r = tmux("split-window", "-d", "-P", "-F", "#{pane_id}", "-t", session);
        assertThat(r.exitCode()).as("split-window -P → stderr=%s", r.stderr()).isZero();
        return r.stdout().trim();
    }

    /** The active pane id of the session's current window. */
    private String activePane() throws IOException {
        return flag(session, "#{pane_id}");
    }

    private boolean awaitConverged(String mainPaneId, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if ("1".equals(flag(session, "#{window_zoomed_flag}")) && mainPaneId.equals(activePane())) {
                return true;
            }
            Thread.sleep(40);
        }
        return false;
    }

    private boolean awaitZoomed(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if ("1".equals(flag(session, "#{window_zoomed_flag}"))) {
                return true;
            }
            Thread.sleep(40);
        }
        return false;
    }

    private boolean awaitFileContains(Path path, String needle, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (Files.exists(path) && Files.readString(path).contains(needle)) {
                return true;
            }
            Thread.sleep(40);
        }
        return false;
    }

    private String flag(String target, String format) throws IOException {
        ProcessExecutor.Result r = tmux("display-message", "-p", "-t", target, format);
        return r.exitCode() == 0 ? r.stdout().trim() : null;
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

    private Path artifact(String kind) {
        return Path.of("/tmp", "uc33-" + kind + "-" + label + ".out");
    }

    private void deleteArtifacts() {
        try {
            Files.deleteIfExists(Path.of("/tmp", "pin-" + session + ".lock"));
            Files.deleteIfExists(artifact("escaping"));
        } catch (IOException ignored) {
            // best-effort
        }
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
