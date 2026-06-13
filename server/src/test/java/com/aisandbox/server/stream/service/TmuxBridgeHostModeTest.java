package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.HostShellSessionService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.TmuxBridgeService.BridgeTarget;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * UC-62 — host-mode argv for {@link TmuxBridgeService#prepareClientSession}. When
 * the bridge targets the reserved host-shell session ({@code onHost == true})
 * every tmux invocation is a BARE {@code tmux [-S <socket>] …} on the
 * management-server host — NO {@code docker compose exec} container prefix. This
 * is the AC5 mechanism: the terminal attaches to the host tmux, not a sandbox
 * container.
 *
 * <p>Argv-only (mocks {@link ProcessExecutor}); mirrors
 * {@link TmuxBridgeSessionSetupTest}. The live host-tmux semantics are covered
 * by {@code HostShellSessionLiveTmuxTest} / {@code TmuxZoomHostTmuxTest}. The
 * service reaches {@link ProcessExecutor} via the 3-arg
 * {@code run(argv, workingDir, timeout)} overload for the bridge setup.
 */
class TmuxBridgeHostModeTest {

    private static final String HOST_SOCKET = "/var/lib/ai-sandbox/server-ssh.sock";
    private static final String HOST_BASE = "ai-sandbox-server-ssh";
    private static final String SESSION = "client-stream-0-host";

    /** The host effective target the bridge builds internally for the reserved id. */
    private static BridgeTarget hostTarget() {
        return new BridgeTarget(HOST_SOCKET, HOST_BASE, null, null);
    }

    /**
     * A recording executor mock. A host/main target (no window/pane) issues TWO
     * display-message reads — {@code #{pane_id}} then the zoom query — so each is
     * stubbed by its format; every other call succeeds with empty stdout.
     */
    private static ProcessExecutor recordingExec(List<List<String>> calls) throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            calls.add(argv);
            if (argv.contains("display-message")) {
                String format = argv.get(argv.size() - 1);
                return new ProcessExecutor.Result(0, "#{pane_id}".equals(format) ? "%0\n" : "2 0\n", "");
            }
            return new ProcessExecutor.Result(0, "", "");
        });
        return exec;
    }

    @Test
    void hostMode_issues_only_bare_host_tmux_with_no_docker_prefix() throws Exception {
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        TmuxBridgeService svc = new TmuxBridgeService(recordingExec(calls));

        svc.prepareClientSession("ai-sandbox-0", HOST_SOCKET, SESSION, hostTarget(), true);

        // AC5 — NOT ONE recorded tmux invocation goes through a container.
        assertThat(calls)
                .as("host-mode bridge issues only bare host tmux — never a docker/compose exec")
                .isNotEmpty()
                .allSatisfy(argv -> assertThat(argv).doesNotContain("docker", "compose", "exec"));

        // The per-client new-session is a bare
        // `tmux -S <hostSocket> new-session -d -s <session> -t <hostBase>`.
        List<String> newSession = calls.stream()
                .filter(c -> c.contains("new-session"))
                .findFirst()
                .orElseThrow();
        assertThat(newSession.get(0)).isEqualTo("tmux");
        assertThat(newSession).startsWith("tmux", "-S", HOST_SOCKET);
        assertThat(newSession).containsSequence("new-session", "-d", "-s", SESSION, "-t", HOST_BASE);
    }

    @Test
    void containerMode_keeps_the_docker_compose_exec_prefix() throws Exception {
        // Control: the same setup with onHost=false is the unchanged container
        // path — proving the host branch is the only thing that drops the prefix.
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        TmuxBridgeService svc = new TmuxBridgeService(recordingExec(calls));

        svc.prepareClientSession("ai-sandbox-1", null, "client-stream-1-x", BridgeTarget.main(), false);

        List<String> newSession = calls.stream()
                .filter(c -> c.contains("new-session"))
                .findFirst()
                .orElseThrow();
        assertThat(newSession)
                .startsWith("docker", "compose", "-p", "ai-sandbox-1", "exec", "-T", "claude-sandbox", "tmux");
    }

    private static final String HOST_HOME = "/var/lib/ai-sandbox-server/sessions/server-ssh-home";

    /**
     * UC-64 AC9b — the host-mode PTY-attach env overlays {@code HOME} with the
     * accessible, writable redirected home reported by the bound
     * {@link HostShellSessionService}, so the attaching client and the login
     * shell inside the pane resolve their (absent) config away from the
     * {@code ProtectHome}-hidden {@code /home} — matching the {@code HOME} the
     * tmux server itself was created with. Also retains the inherited
     * {@code PATH}/{@code TERM} (the pty4j replace-env gotcha).
     */
    @Test
    void buildPtyEnv_onHost_overlays_redirected_home() {
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.homePathString()).thenReturn(HOST_HOME);
        TmuxBridgeService svc = new TmuxBridgeService(mock(ProcessExecutor.class));
        svc.setHostShell(hostShell);

        Map<String, String> env = svc.buildPtyEnv(true);

        assertThat(env).containsKey("HOME");
        assertThat(env.get("HOME"))
                .as("AC9b — host-mode attach HOME is the accessible redirected home")
                .isEqualTo(HOST_HOME);
        assertThat(env.get("HOME"))
                .as("AC9b/AC8 — HOME is NOT the ProtectHome-hidden real home")
                .doesNotStartWith("/home/");
        // The pty4j env-replace gotcha guard stays intact: PATH/TERM are present.
        assertThat(env).containsKey("PATH");
        assertThat(env).containsEntry("TERM", "xterm-256color");
    }

    /**
     * UC-64 — control: a NON-host (container) attach must NOT have its
     * {@code HOME} rewritten to the host-shell's redirected home even when the
     * host-shell service is bound. Container sessions carry their own in-image
     * {@code $HOME}; only the host shell needs the ProtectHome redirect.
     */
    @Test
    void buildPtyEnv_offHost_does_not_override_home_to_hostShell() {
        HostShellSessionService hostShell = mock(HostShellSessionService.class);
        when(hostShell.homePathString()).thenReturn(HOST_HOME);
        TmuxBridgeService svc = new TmuxBridgeService(mock(ProcessExecutor.class));
        svc.setHostShell(hostShell);

        Map<String, String> env = svc.buildPtyEnv(false);

        assertThat(env.get("HOME"))
                .as("AC9b — container (off-host) attach keeps the inherited HOME, never the host-shell redirect")
                .isNotEqualTo(HOST_HOME);
        // Whatever HOME the JVM inherited is passed through unchanged.
        assertThat(env.get("HOME")).isEqualTo(System.getenv("HOME"));
    }

    /**
     * UC-64 — graceful degradation: when no host-shell service is bound (the
     * field doc's fallback), even an {@code onHost} attach leaves {@code HOME}
     * inherited rather than NPEing on a null service.
     */
    @Test
    void buildPtyEnv_onHost_withUnboundHostShell_leaves_home_inherited() {
        TmuxBridgeService svc = new TmuxBridgeService(mock(ProcessExecutor.class)); // no setHostShell

        Map<String, String> env = svc.buildPtyEnv(true);

        assertThat(env.get("HOME"))
                .as("UC-64 — unbound host-shell ⇒ HOME left inherited (no NPE, no host redirect)")
                .isEqualTo(System.getenv("HOME"));
    }
}
