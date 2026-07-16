package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-98 — {@link SessionReadinessService} probes a session's readiness marker
 * ({@code /tmp/aisandbox-ready}) via {@code docker compose … exec test -f} so
 * the post-spawn setup prompt is injected only after the container is up (AC6).
 * These unit tests mock the {@link ProcessExecutor} so the probe's decode /
 * poll / timeout behaviour is exercised without a real container:
 *
 * <ul>
 *   <li>exit 0 → ready; any non-zero exit → not ready;</li>
 *   <li>an exec {@link IOException} is swallowed as not-ready (conservative —
 *       never optimistically "ready");</li>
 *   <li>{@code awaitReady} returns true as soon as the marker appears, and false
 *       when it never appears within the timeout.</li>
 * </ul>
 */
class SessionReadinessServiceTest {

    private static ProcessExecutor.Result exit(int code) {
        return new ProcessExecutor.Result(code, "", "");
    }

    @Test
    void isReady_true_on_exit_zero() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(exit(0));

        assertThat(new SessionReadinessService(exec).isReady(3)).isTrue();
    }

    @Test
    void isReady_false_on_non_zero_exit() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(exit(1));

        assertThat(new SessionReadinessService(exec).isReady(3)).isFalse();
    }

    @Test
    void isReady_false_and_swallows_an_exec_ioexception() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(Duration.class))).thenThrow(new IOException("container not exec-able yet"));

        assertThat(new SessionReadinessService(exec).isReady(3)).isFalse();
    }

    @Test
    void isReady_targets_the_correct_compose_project_and_marker() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(exit(0));

        new SessionReadinessService(exec).isReady(7);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> argv = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), any(), any(Duration.class));
        assertThat(argv.getValue())
                .containsSubsequence("docker", "compose", "-p", "ai-sandbox-7")
                .contains("claude-sandbox", "test", "-f", "/tmp/aisandbox-ready");
    }

    @Test
    void awaitReady_returns_true_as_soon_as_the_marker_appears() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        // not ready, not ready, then ready.
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(exit(1), exit(1), exit(0));

        boolean ready = new SessionReadinessService(exec).awaitReady(3, Duration.ofSeconds(5), Duration.ofMillis(1));

        assertThat(ready).isTrue();
        verify(exec, atLeast(3)).run(any(), any(), any(Duration.class));
    }

    @Test
    void awaitReady_returns_false_when_the_marker_never_appears_within_the_timeout() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(exit(1));

        boolean ready = new SessionReadinessService(exec).awaitReady(3, Duration.ofMillis(20), Duration.ofMillis(1));

        assertThat(ready).isFalse();
    }
}
