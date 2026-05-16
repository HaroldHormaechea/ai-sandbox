package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.dto.ClaudeConfigMode;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.dto.WorkspaceMode;
import com.aisandbox.server.sessions.service.HostScriptLocator;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.ScriptExecutorService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AC24 — every invocation of {@code spawn.sh} / {@code clean.sh} goes
 * through an argv-array and includes only validated, mode-flagged
 * arguments. {@code --non-interactive} is hard-wired (AC51).
 */
class ScriptExecutorServiceTest {

    private HostScriptLocator locator() {
        HostScriptLocator l = mock(HostScriptLocator.class);
        when(l.spawnSh()).thenReturn(Path.of("/fake/spawn.sh"));
        when(l.cleanSh()).thenReturn(Path.of("/fake/clean.sh"));
        when(l.repoRoot()).thenReturn(Path.of("/fake"));
        return l;
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_assembles_argv_with_non_interactive_mode_flags_and_optional_label() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-3 ready", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec);

        SpawnCommand cmd = new SpawnCommand("my-label", WorkspaceMode.ISOLATED, ClaudeConfigMode.SHARED);
        ProcessExecutor.Result r = svc.spawn(cmd, Duration.ofSeconds(5));

        assertThat(r.exitCode()).isZero();
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), eq(Path.of("/fake")), eq(Duration.ofSeconds(5)));
        assertThat(argv.getValue())
                .containsExactly(
                        "/fake/spawn.sh",
                        "--non-interactive",
                        "--isolated-workspace",
                        "--shared-claude-config",
                        "--label",
                        "my-label");
    }

    @Test
    @SuppressWarnings("unchecked")
    void spawn_omits_label_when_null() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-1", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec);

        svc.spawn(new SpawnCommand(null, WorkspaceMode.SHARED, ClaudeConfigMode.SHARED), Duration.ofSeconds(5));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), any(), any());
        assertThat(argv.getValue())
                .containsExactly("/fake/spawn.sh", "--non-interactive", "--shared-workspace", "--shared-claude-config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void clean_passes_session_number_as_separate_argv_entry() throws Exception {
        HostScriptLocator loc = locator();
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "", ""));
        ScriptExecutorService svc = new ScriptExecutorService(loc, exec);

        svc.clean(7, Duration.ofSeconds(5));

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), eq(Path.of("/fake")), eq(Duration.ofSeconds(5)));
        assertThat(argv.getValue()).containsExactly("/fake/clean.sh", "--non-interactive", "--session", "7");
    }
}
