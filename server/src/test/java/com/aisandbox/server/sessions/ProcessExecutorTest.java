package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * AC24 — argv-array exec only; no shell interpolation; bounded capture;
 * timeout-driven kill. Tests use {@code /bin/sh} / {@code /bin/echo}
 * which are part of every UNIX dev image (and the Alpine sandbox).
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessExecutorTest {

    private final ProcessExecutor exec = new ProcessExecutor();

    @Test
    void captures_stdout_and_exit_zero() throws Exception {
        ProcessExecutor.Result r = exec.run(List.of("/bin/echo", "hello"), null, Duration.ofSeconds(5));
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("hello");
        assertThat(r.stderr()).isEmpty();
    }

    @Test
    void argv_is_passed_as_an_array_not_a_shell_command() throws Exception {
        // If we were going through a shell, ';true' would split into two commands.
        // Going through argv-only, the entire string is one argument to echo.
        ProcessExecutor.Result r =
                exec.run(List.of("/bin/echo", "foo; rm -rf /"), null, Duration.ofSeconds(5));
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("foo; rm -rf /");
    }

    @Test
    void captures_stderr_and_non_zero_exit() throws Exception {
        ProcessExecutor.Result r = exec.run(
                List.of("/bin/sh", "-c", "printf err 1>&2; exit 17"), null, Duration.ofSeconds(5));
        assertThat(r.exitCode()).isEqualTo(17);
        assertThat(r.stderr()).contains("err");
    }

    @Test
    void honors_timeout_and_destroys_process() {
        // sleep 30 with timeout 500ms must trip and surface as IOException
        // wrapping a TimeoutException.
        assertThatThrownBy(() -> exec.run(List.of("/bin/sh", "-c", "sleep 30"), null, Duration.ofMillis(500)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void truncates_large_output_with_sentinel() throws Exception {
        // Spit > 64 KiB at stdout.
        ProcessExecutor.Result r = exec.run(
                List.of("/bin/sh", "-c", "yes A | head -c 200000"), null, Duration.ofSeconds(5));
        assertThat(r.exitCode()).isZero();
        // Captured buffer is capped at 64 KiB + the truncation sentinel.
        assertThat(r.stdout().length()).isLessThan(70_000);
        assertThat(r.stdout()).contains("[truncated]");
    }
}
