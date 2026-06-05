package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * UC-24 — pins the exact tmux argv sequence emitted by
 * {@link TmuxBridgeService#prepareClientSession} (the pre-PTY per-client
 * session setup) for the committed fix (8ffd02d). The fix has two parts:
 *
 * <ul>
 *   <li><b>Per-client {@code status off}</b> — for EVERY target (main and
 *       swarm), the per-client session's status line is disabled so the tmux
 *       window-list / status chrome (the visible "all windows shown" the user
 *       reported) is never rendered to the Android client. Scoped to the
 *       per-client {@code -t client-<id>} session, so the orchestrator's own
 *       status setting is untouched (UC-21 AC#1/#12, UC-24 AC#1/#2).</li>
 *   <li><b>Idempotent zoom</b> — the old unconditional {@code resize-pane -Z}
 *       was a TOGGLE: a re-bridge / reconnect / second client toggled zoom OFF
 *       and exposed the unzoomed split (UC-24 root cause, hyp.2). The fix
 *       queries {@code #{window_panes} #{window_zoomed_flag}} once and runs
 *       {@code resize-pane -Z} ONLY when the window has &gt;1 pane and is not
 *       already zoomed; single-pane windows are skipped; on a query failure it
 *       falls back to attempting the zoom (preserving pre-fix behaviour rather
 *       than risk leaving a split view).</li>
 * </ul>
 *
 * <p>This is a pure-Java, argv-only test that mocks {@link ProcessExecutor}
 * (mirrors {@link SwarmEnumerationService}) — no Docker, no tmux. It proves the
 * argv-level contract; the live tmux unzoom-on-{@code select-pane} semantics
 * (which a mock cannot exercise) are covered separately by the host-tmux
 * harness / the DinD-gated {@code SwarmBridgeIT} and the operator runbook.
 *
 * <p>The service reaches {@link ProcessExecutor} via the 3-arg
 * {@code run(argv, workingDir, timeout)} overload (workingDir {@code null}); the
 * recording mock below matches that overload.
 */
class TmuxBridgeSessionSetupTest {

    private static final String PROJECT = "ai-sandbox-1";
    private static final String SOCKET = "/tmp/tmux-997/claude-swarm-15713";
    private static final String SESSION = "client-stream-7-g1";

    /** A swarm pane target: window 0, pane 1 on the claude-swarm socket. */
    private static BridgeTarget swarmPane() {
        return new BridgeTarget(SOCKET, "claude-swarm", "0", "1");
    }

    /** Records every {@code exec.run} argv and replies per the display-message stub. */
    private static final class Recorder {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final ProcessExecutor exec = mock(ProcessExecutor.class);
    }

    /**
     * Build a recording executor. Every call is recorded; {@code display-message}
     * replies with {@code (displayExit, displayStdout)} and every other call
     * succeeds with empty stdout.
     */
    private static Recorder recorder(String displayStdout, int displayExit) throws IOException {
        Recorder rec = new Recorder();
        when(rec.exec.run(any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            rec.calls.add(argv);
            if (argv.contains("display-message")) {
                return new ProcessExecutor.Result(displayExit, displayStdout, displayExit == 0 ? "" : "no pane");
            }
            return new ProcessExecutor.Result(0, "", "");
        });
        return rec;
    }

    // ── argv helpers ────────────────────────────────────────────────────────

    /** The tmux args (everything after {@code tmux}), with an optional {@code -S <socket>} pair stripped. */
    private static List<String> tmuxArgs(List<String> argv) {
        int t = argv.indexOf("tmux");
        List<String> a = new ArrayList<>(argv.subList(t + 1, argv.size()));
        if (a.size() >= 2 && "-S".equals(a.get(0))) {
            a.remove(0);
            a.remove(0);
        }
        return a;
    }

    /**
     * A compact operation signature per recorded call so order can be pinned.
     * {@code set-option} is disambiguated by its option name ({@code mouse} /
     * {@code status}); every other op is its tmux verb.
     */
    private static List<String> opSequence(List<List<String>> calls) {
        List<String> ops = new ArrayList<>();
        for (List<String> argv : calls) {
            List<String> a = tmuxArgs(argv);
            String verb = a.get(0);
            if ("set-option".equals(verb)) {
                verb = "set-option:" + a.get(3); // [set-option, -t, <session>, <option>, <value>]
            }
            ops.add(verb);
        }
        return ops;
    }

    /** The first recorded call whose tmux verb is {@code verb}. */
    private static List<String> firstCall(List<List<String>> calls, String verb) {
        return calls.stream()
                .filter(c -> verb.equals(tmuxArgs(c).get(0)))
                .findFirst()
                .orElse(null);
    }

    // ── (v) main target — UC-24 generalized zoom (multi-pane main IS zoomed) ──

    @Test
    void mainTarget_multiPaneWindow_isZoomed_withoutAnyPaneSelect() throws Exception {
        // UC-24 root cause: when the default-socket main window has >1 pane, the
        // pre-fix code skipped the zoom (no pane named) so the per-client attach
        // painted the unzoomed split — the "all windows shown" the user reported.
        // The fix generalizes the needed-only zoom to EVERY target, main included.
        // window_panes=2, window_zoomed_flag=0 → zoom needed.
        Recorder rec = recorder("2 0\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, null, SESSION, BridgeTarget.main());

        // create → mouse on → status off → zoom query → zoom. No pane select:
        // main carries no window/pane, so the generalized zoom targets the
        // per-client session's active window directly.
        assertThat(opSequence(rec.calls))
                .containsExactly(
                        "new-session", "set-option:mouse", "set-option:status", "display-message", "resize-pane");
        assertThat(opSequence(rec.calls)).doesNotContain("select-window", "select-pane");

        // No socket flag for the default-socket main session.
        assertThat(firstCall(rec.calls, "new-session")).doesNotContain("-S");

        // The zoom query + the zoom both target the PER-CLIENT session itself
        // (zoomSpec == session for an un-paned main target) — never a base session,
        // so the orchestrator's own view of the main window is not force-zoomed.
        assertThat(firstCall(rec.calls, "display-message"))
                .containsSequence("-p", "-t", SESSION, "#{window_panes} #{window_zoomed_flag}");
        assertThat(firstCall(rec.calls, "resize-pane")).containsSequence("-Z", "-t", SESSION);

        // status off is still scoped to the per-client session (UC-24 AC#1: chrome hidden).
        assertStatusOffScopedToSession(rec.calls);
    }

    @Test
    void mainTarget_singlePaneWindow_isNotZoomed() throws Exception {
        // A genuine single-pane main window (the no-team case) must stay a strict
        // no-op — the >1-pane guard prevents a needless zoom toggle (UC-21 AC#1).
        // window_panes=1 → nothing to zoom.
        Recorder rec = recorder("1 0\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, null, SESSION, BridgeTarget.main());

        assertThat(opSequence(rec.calls))
                .containsExactly("new-session", "set-option:mouse", "set-option:status", "display-message");
        assertThat(opSequence(rec.calls)).doesNotContain("resize-pane", "select-window", "select-pane");
        assertStatusOffScopedToSession(rec.calls);
    }

    // ── (i)+(ii) swarm pane, unzoomed multi-pane → full ordered sequence ─────

    @Test
    void swarmPane_unzoomedMultiPane_zoomsOnce_inStrictOrder() throws Exception {
        // window_panes=2, window_zoomed_flag=0 → zoom needed.
        Recorder rec = recorder("2 0\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());

        // The exact argv order the fix promises.
        assertThat(opSequence(rec.calls))
                .containsExactly(
                        "new-session",
                        "set-option:mouse",
                        "set-option:status",
                        "select-window",
                        "select-pane",
                        "display-message",
                        "resize-pane");

        // (i) status off issued for the swarm target too.
        assertStatusOffScopedToSession(rec.calls);

        // Socket flag IS present for the swarm socket.
        assertThat(firstCall(rec.calls, "new-session")).containsSequence("-S", SOCKET);

        // Pitfall #3 — every pane op targets the PER-CLIENT session, never the
        // shared base "claude-swarm" session, so the orchestrator's view of the
        // grouped window is not force-zoomed / resized by an Android attach.
        assertThat(firstCall(rec.calls, "select-window")).containsSequence("-t", SESSION + ":0");
        assertThat(firstCall(rec.calls, "select-pane")).containsSequence("-t", SESSION + ":0.1");
        assertThat(firstCall(rec.calls, "resize-pane")).containsSequence("-Z", "-t", SESSION + ":0.1");
        // The zoom query reads the right format off the per-client pane.
        assertThat(firstCall(rec.calls, "display-message"))
                .containsSequence("-p", "-t", SESSION + ":0.1", "#{window_panes} #{window_zoomed_flag}");
    }

    // ── (iii) already zoomed → NO resize-pane (idempotent, fixes the toggle) ─

    @Test
    void swarmPane_alreadyZoomed_doesNotToggleZoomOff() throws Exception {
        // window_panes=2, window_zoomed_flag=1 → already zoomed, must NOT re-toggle.
        Recorder rec = recorder("2 1\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());

        assertThat(opSequence(rec.calls))
                .containsExactly(
                        "new-session",
                        "set-option:mouse",
                        "set-option:status",
                        "select-window",
                        "select-pane",
                        "display-message");
        assertThat(opSequence(rec.calls)).doesNotContain("resize-pane");
    }

    // ── (iv) single-pane window → NO resize-pane ─────────────────────────────

    @Test
    void swarmPane_singlePaneWindow_isNotZoomed() throws Exception {
        // window_panes=1 → nothing to zoom (the one-pane-per-window churn case).
        Recorder rec = recorder("1 0\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());

        assertThat(opSequence(rec.calls)).contains("display-message").doesNotContain("resize-pane");
    }

    // ── (vi) query failure → fall back to attempting the zoom ────────────────

    @Test
    void swarmPane_zoomQueryFails_fallsBackToAttemptingZoom() throws Exception {
        // display-message exits non-zero → displayMessage() returns null →
        // zoomNeeded() falls back to true → resize-pane -Z is still attempted.
        Recorder rec = recorder("", 1);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());

        assertThat(opSequence(rec.calls)).containsSubsequence("display-message", "resize-pane");
        assertThat(firstCall(rec.calls, "resize-pane")).containsSequence("-Z", "-t", SESSION + ":0.1");
    }

    @Test
    void swarmPane_zoomQueryMalformedOutput_fallsBackToAttemptingZoom() throws Exception {
        // Output without two whitespace-separated fields → parse fails → fallback zoom.
        Recorder rec = recorder("garbage\n", 0);

        new TmuxBridgeService(rec.exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());

        assertThat(opSequence(rec.calls)).containsSubsequence("display-message", "resize-pane");
    }

    // ── failure semantics — new-session is the only hard failure ─────────────

    @Test
    void newSessionFailure_throwsIOException() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("new-session")) {
                return new ProcessExecutor.Result(1, "", "duplicate session: client-stream-7-g1");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        assertThatThrownBy(
                        () -> new TmuxBridgeService(exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("tmux new-session failed");
    }

    @Test
    void mouseAndStatusFailures_areBestEffort_andDoNotAbortTheBridge() throws Exception {
        // mouse on AND status off fail, but the swarm setup proceeds to zoom.
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (argv.contains("set-option")) {
                return new ProcessExecutor.Result(1, "", "unknown option");
            }
            if (argv.contains("display-message")) {
                return new ProcessExecutor.Result(0, "2 0\n", "");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        // Must not throw — best-effort steps swallow failures.
        new TmuxBridgeService(exec).prepareClientSession(PROJECT, SOCKET, SESSION, swarmPane());
    }

    // ── shared assertion ─────────────────────────────────────────────────────

    /** Assert a {@code set-option -t <session> status off} was issued, scoped to the per-client session. */
    private static void assertStatusOffScopedToSession(List<List<String>> calls) {
        List<List<String>> statusCalls = calls.stream()
                .filter(c -> {
                    List<String> a = tmuxArgs(c);
                    return "set-option".equals(a.get(0)) && a.contains("status");
                })
                .toList();
        assertThat(statusCalls).as("exactly one status set-option").hasSize(1);
        assertThat(tmuxArgs(statusCalls.get(0)))
                .as("status off scoped to the per-client session")
                .containsExactly("set-option", "-t", SESSION, "status", "off");
    }
}
