package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.dto.ClaudeConfigMode;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.dto.WorkspaceMode;
import com.aisandbox.server.sessions.service.HostScriptLocator;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AC24 — every invocation of {@code spawn.sh} / {@code clean.sh} goes
 * through an argv-array and includes only validated, mode-flagged
 * arguments. {@code --non-interactive} is hard-wired (AC51).
 *
 * <p>UC05 § AC25,AC26,AC27 — every host-script invocation also carries
 * three environment variables so the bundled scripts route writes off
 * the read-only install dir. The tests below assert both the argv and
 * the env map captured at the {@link ProcessExecutor#run(List, Path,
 * Map, Duration)} call site; the env values are pulled from
 * {@link ServerProperties}.
 */
class ScriptExecutorServiceTest {

    // Fixed install-layout paths exercised by the tests. Mirror the
    // production defaults (server/src/main/resources/application.yaml)
    // so the assertions read as "scripts run with the deployed env".
    private static final Path REPO_ROOT = Path.of("/fake");
    private static final Path HOST_STATE_ROOT = Path.of("/var/lib/ai-sandbox-server/sessions");
    private static final Path SECRETS_DIR = Path.of("/etc/ai-sandbox-server/secrets");

    private HostScriptLocator locator() {
        HostScriptLocator l = mock(HostScriptLocator.class);
        when(l.spawnSh()).thenReturn(REPO_ROOT.resolve("spawn.sh"));
        when(l.cleanSh()).thenReturn(REPO_ROOT.resolve("clean.sh"));
        when(l.repoRoot()).thenReturn(REPO_ROOT);
        return l;
    }

    /**
     * Build a minimal {@link ServerProperties} fixture with the UC05
     * fields populated. The other records get throwaway defaults — the
     * tests below only read {@code hostscripts.repoRoot},
     * {@code sessions.hostStateRoot}, and {@code secrets.dir}.
     */
    private static ServerProperties props() {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(REPO_ROOT),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(Path.of("/etc/ai-sandbox-server/enrollment"), 10, 1, 60),
                new ServerProperties.Sessions(HOST_STATE_ROOT),
                new ServerProperties.Secrets(SECRETS_DIR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_assembles_argv_with_non_interactive_mode_flags_and_optional_label() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-3 ready", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec, props());

        SpawnCommand cmd = new SpawnCommand("my-label", WorkspaceMode.ISOLATED, ClaudeConfigMode.SHARED);
        ProcessExecutor.Result r = svc.spawn(cmd, Duration.ofSeconds(5));

        assertThat(r.exitCode()).isZero();
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(exec).run(argv.capture(), eq(REPO_ROOT), env.capture(), eq(Duration.ofSeconds(5)));
        assertThat(argv.getValue())
                .containsExactly(
                        "/fake/spawn.sh",
                        "--non-interactive",
                        "--isolated-workspace",
                        "--shared-claude-config",
                        "--label",
                        "my-label");

        // UC05 § AC25,AC26,AC27 — env carries the three compose-routing
        // variables. AI_SANDBOX_COMPOSE_FILE points at the install-mode
        // docker-compose.yml under hostscripts.repoRoot; HOST_STATE_ROOT
        // drives `--project-directory` in ai_sandbox_compose; SECRETS
        // resolves the secrets bind-mount source.
        assertThat(env.getValue())
                .containsEntry("AI_SANDBOX_COMPOSE_FILE", "/fake/docker-compose.yml")
                .containsEntry("AI_SANDBOX_HOST_STATE_ROOT", HOST_STATE_ROOT.toString())
                .containsEntry("AI_SANDBOX_SECRETS_HOST_PATH", SECRETS_DIR.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_omits_label_when_null() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-1", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec, props());

        svc.spawn(new SpawnCommand(null, WorkspaceMode.SHARED, ClaudeConfigMode.SHARED), Duration.ofSeconds(5));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), any(), any(), any());
        assertThat(argv.getValue())
                .containsExactly("/fake/spawn.sh", "--non-interactive", "--shared-workspace", "--shared-claude-config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void clean_passes_session_number_as_separate_argv_entry_with_compose_env() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec, props());

        svc.clean(7, Duration.ofSeconds(5));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(exec).run(argv.capture(), eq(REPO_ROOT), env.capture(), eq(Duration.ofSeconds(5)));
        assertThat(argv.getValue()).containsExactly("/fake/clean.sh", "--non-interactive", "--session", "7");
        // Clean carries the same compose env as spawn — same compose
        // file, same project-directory, same secrets path. Important
        // because `clean.sh` calls `ai_sandbox_compose down -v` and the
        // wrapper's `--project-directory` resolution is what tells
        // Compose where the per-session host-state lives.
        assertThat(env.getValue())
                .containsEntry("AI_SANDBOX_COMPOSE_FILE", "/fake/docker-compose.yml")
                .containsEntry("AI_SANDBOX_HOST_STATE_ROOT", HOST_STATE_ROOT.toString())
                .containsEntry("AI_SANDBOX_SECRETS_HOST_PATH", SECRETS_DIR.toString());
    }

    /**
     * Back-compat smoke — the 2-arg ctor with {@code null} props still
     * works for any pre-UC05 fixture that hasn't migrated yet. The env
     * map is empty, matching the historical bare-environment behaviour.
     */
    @Test
    @SuppressWarnings("unchecked")
    void two_arg_ctor_passes_empty_env_for_back_compat() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec);

        svc.clean(1, Duration.ofSeconds(5));

        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(exec).run(any(), any(), env.capture(), any());
        assertThat(env.getValue()).isEmpty();
    }
}
