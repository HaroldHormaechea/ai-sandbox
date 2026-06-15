package com.aisandbox.server.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.mcp.McpRegistrationException;
import com.aisandbox.server.mcp.dto.McpAddSpec;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * UC-82 AC4 — THE security-critical proof. {@link McpRegistrationService} assembles the
 * {@code claude mcp add/remove} argv that reaches process execution; this test proves
 * that NO user-supplied field (name, command, args, {@code -e} env values, url,
 * {@code --header} values) can ever break out of its argv slot and run an extra
 * command.
 *
 * <p>Two complementary proofs:
 *
 * <ol>
 *   <li><b>Argv-shape proof</b> — with a mocked {@link ProcessExecutor} + an
 *       {@link ArgumentCaptor}, a hostile value placed in every field lands as exactly
 *       one inert argv token (after the {@code --} guard for stdio positionals);
 *       argv[0] is {@code docker}; {@code claude mcp add} stays adjacent; no token is a
 *       shell ({@code sh}/{@code bash}/{@code -c}) or a privilege flag
 *       ({@code -u}/{@code --user}/{@code --privileged}).</li>
 *   <li><b>Real-effect proof</b> — the captured argv is actually EXECUTED through a real
 *       {@link ProcessExecutor} (a shell-free {@code ProcessBuilder}) with the
 *       {@code docker} binary swapped for {@code /bin/echo}. The injected
 *       {@code touch /tmp/uc82_pwned} / {@code ls} are demonstrated to be inert data:
 *       the file is NEVER created and no embedded command runs.</li>
 * </ol>
 */
class McpRegistrationServiceTest {

    /** The exact hostile values the use case + team lead enumerated. */
    private static final List<String> HOSTILE = List.of(
            "ls",
            "touch /tmp/uc82_pwned",
            "foo; touch /tmp/uc82_pwned",
            "$(touch /tmp/uc82_pwned)",
            "`ls /`",
            "x && touch /tmp/uc82_pwned",
            "x\ntouch /tmp/uc82_pwned");

    private static final Set<String> SHELL = Set.of("sh", "bash", "-c", "/bin/sh", "/bin/bash", "zsh", "-lc");
    private static final Set<String> PRIVILEGE = Set.of("-u", "--user", "--privileged");

    private static final Path PWNED = Path.of("/tmp/uc82_pwned");

    private ProcessExecutor exec;
    private McpRegistrationService service;

    @BeforeEach
    void setUp() throws IOException {
        exec = mock(ProcessExecutor.class);
        service = new McpRegistrationService(exec);
        Files.deleteIfExists(PWNED);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(PWNED);
    }

    private static ProcessExecutor.Result ok() {
        return new ProcessExecutor.Result(0, "", "");
    }

    @SuppressWarnings("unchecked")
    private List<String> captureAdd(McpAddSpec spec) throws IOException {
        when(exec.run(any(), any(), any())).thenReturn(ok());
        service.add(7, spec);
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), eq(null), any(Duration.class));
        return argv.getValue();
    }

    /** Invariants that must hold for EVERY add argv, regardless of input. */
    private static void assertInjectionSafeEnvelope(List<String> argv) {
        assertThat(argv.get(0)).isEqualTo("docker");
        assertThat(argv).containsSubsequence("claude", "mcp", "add");
        int claudeIdx = argv.indexOf("claude");
        // claude is ALWAYS immediately followed by `mcp add` — a bare conversation /
        // `claude -p`(print) / non-mcp invocation is structurally impossible. (The leading
        // `-p` belongs to docker-compose's project selector, not claude.)
        assertThat(argv.get(claudeIdx + 1)).isEqualTo("mcp");
        assertThat(argv.get(claudeIdx + 2)).isEqualTo("add");
        assertThat(argv).doesNotContainAnyElementsOf(SHELL); // no shell
        assertThat(argv).doesNotContainAnyElementsOf(PRIVILEGE); // no privilege escalation
    }

    private static McpAddSpec stdio(String name, String command, List<String> args, Map<String, String> env) {
        return new McpAddSpec(name, "stdio", command, args, null, env, null);
    }

    private static McpAddSpec http(String name, String url, List<String> headers) {
        return new McpAddSpec(name, "http", null, null, url, null, headers);
    }

    // ──────────────────────── argv-shape proof, per field ────────────────────

    @ParameterizedTest(name = "hostile stdio command [{index}]")
    @ValueSource(
            strings = {
                "ls",
                "touch /tmp/uc82_pwned",
                "foo; touch /tmp/uc82_pwned",
                "$(touch /tmp/uc82_pwned)",
                "`ls /`",
                "x && touch /tmp/uc82_pwned",
                "x\ntouch /tmp/uc82_pwned"
            })
    void a_hostile_stdio_command_is_one_inert_positional_after_the_dashdash(String hostile) throws Exception {
        List<String> argv = captureAdd(stdio("srv", hostile, null, null));

        assertInjectionSafeEnvelope(argv);
        // Exactly one token equals the hostile command, and it sits AFTER the `--` guard
        // so a flag-looking value can't be reparsed as an option (AC4).
        assertThat(argv.stream().filter(hostile::equals).count()).isEqualTo(1L);
        int dashDash = argv.indexOf("--");
        assertThat(dashDash).isGreaterThanOrEqualTo(0);
        assertThat(argv.indexOf(hostile)).isGreaterThan(dashDash);
    }

    @Test
    void every_hostile_stdio_arg_is_its_own_inert_positional() throws Exception {
        List<String> argv = captureAdd(stdio("srv", "npx", HOSTILE, null));

        assertInjectionSafeEnvelope(argv);
        int dashDash = argv.indexOf("--");
        for (String hostile : HOSTILE) {
            assertThat(argv.stream().filter(hostile::equals).count())
                    .as("hostile arg present exactly once: %s", hostile)
                    .isEqualTo(1L);
            assertThat(argv.indexOf(hostile)).isGreaterThan(dashDash);
        }
    }

    @Test
    void a_hostile_env_value_stays_inside_one_KV_token() throws Exception {
        // env entries land as `-e KEY=VALUE`; the hostile VALUE is a substring of exactly
        // one token, never a standalone command, and the `-e` flag is intact.
        String hostile = "v; touch /tmp/uc82_pwned $(ls)";
        List<String> argv = captureAdd(stdio("srv", "npx", null, Map.of("TOKEN", hostile)));

        assertInjectionSafeEnvelope(argv);
        List<String> carrying = argv.stream().filter(a -> a.contains(hostile)).toList();
        assertThat(carrying).containsExactly("TOKEN=" + hostile);
        // The env flag precedes the value, and the value is a single argv element.
        int kvIdx = argv.indexOf("TOKEN=" + hostile);
        assertThat(argv.get(kvIdx - 1)).isEqualTo("-e");
    }

    @ParameterizedTest(name = "hostile http url [{index}]")
    @ValueSource(
            strings = {
                "http://h/$(touch /tmp/uc82_pwned)",
                "https://h/`ls /`",
                "http://h/x; touch /tmp/uc82_pwned",
                "http://h/x && touch /tmp/uc82_pwned"
            })
    void a_hostile_http_url_is_one_inert_token(String hostile) throws Exception {
        List<String> argv = captureAdd(http("srv", hostile, null));

        assertInjectionSafeEnvelope(argv);
        assertThat(argv.stream().filter(hostile::equals).count()).isEqualTo(1L);
    }

    @Test
    void a_hostile_header_value_is_one_inert_token() throws Exception {
        String header = "X-Evil: v; touch /tmp/uc82_pwned $(ls)";
        List<String> argv = captureAdd(http("srv", "https://h/sse", List.of(header)));

        assertInjectionSafeEnvelope(argv);
        assertThat(argv.stream().filter(header::equals).count()).isEqualTo(1L);
        int hIdx = argv.indexOf(header);
        assertThat(argv.get(hIdx - 1)).isEqualTo("--header");
    }

    @Test
    void a_hostile_name_is_one_inert_token() throws Exception {
        // (The facade rejects such a name with a 400 before reaching here; this proves
        // that EVEN IF it arrived, the registration service still can't be made to run it.)
        String name = "n; touch /tmp/uc82_pwned";
        List<String> argv = captureAdd(stdio(name, "npx", null, null));

        assertInjectionSafeEnvelope(argv);
        assertThat(argv.stream().filter(name::equals).count()).isEqualTo(1L);
    }

    // ──────────────────────── REAL-EFFECT proof (shell-free exec) ────────────

    @Test
    void executing_the_built_argv_shell_free_does_NOT_run_the_injected_touch() throws Exception {
        // 1) Build the REAL argv for a stdio add whose command + args are pure injection
        //    attempts (a `touch` that would create /tmp/uc82_pwned if a shell ran it).
        List<String> built = captureAdd(stdio(
                "evil",
                "touch /tmp/uc82_pwned",
                List.of("foo; touch /tmp/uc82_pwned", "$(touch /tmp/uc82_pwned)", "`ls /`", "x\ntouch /tmp/uc82_pwned"),
                Map.of("E", "v; touch /tmp/uc82_pwned")));

        // 2) Swap the leading `docker` for a harmless stand-in so we don't need docker
        //    (and don't actually drive claude) — everything else stays byte-identical.
        //    /bin/echo just prints its argv; it interprets nothing.
        List<String> substitute = new ArrayList<>(built);
        substitute.set(0, "/bin/echo");

        assertThat(Files.exists(PWNED)).isFalse(); // precondition

        // 3) Run it through the REAL executor — a no-shell ProcessBuilder, exactly the
        //    mechanism production uses.
        ProcessExecutor real = new ProcessExecutor();
        ProcessExecutor.Result r = real.run(substitute, null, Duration.ofSeconds(15));

        // 4) The injected command NEVER executed: no file, and the malicious strings were
        //    merely echoed back as inert data (proving they were passed as literal args).
        assertThat(Files.exists(PWNED))
                .as("a shell-free argv exec must NOT have run the injected `touch`")
                .isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).contains("touch /tmp/uc82_pwned"); // echoed, not executed
    }

    @Test
    void a_completely_separate_shell_free_processbuilder_over_the_argv_is_also_inert() throws Exception {
        // The team-lead's alternative phrasing: run a tiny shell-free ProcessBuilder over
        // the captured argv directly (no executor wrapper) and confirm inertness.
        List<String> built = captureAdd(stdio("evil", "x && touch /tmp/uc82_pwned", List.of("`ls /`"), null));
        List<String> substitute = new ArrayList<>(built);
        substitute.set(0, "/bin/echo");

        Files.deleteIfExists(PWNED);
        Process p = new ProcessBuilder(substitute).redirectErrorStream(true).start();
        boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
        }

        assertThat(done).isTrue();
        assertThat(p.exitValue()).isZero();
        assertThat(Files.exists(PWNED)).isFalse();
    }

    // ──────────────────────── remove argv + failure degrade ──────────────────

    @Test
    @SuppressWarnings("unchecked")
    void remove_builds_a_scoped_argv_and_the_name_is_one_inert_token() throws Exception {
        when(exec.run(any(), any(), any())).thenReturn(ok());

        service.remove(9, "srv");

        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(exec).run(argv.capture(), eq(null), any(Duration.class));
        List<String> a = argv.getValue();
        assertThat(a.get(0)).isEqualTo("docker");
        assertThat(a).containsSubsequence("claude", "mcp", "remove", "--scope", "user", "srv");
        assertThat(a).doesNotContainAnyElementsOf(SHELL);
        assertThat(a).doesNotContainAnyElementsOf(PRIVILEGE);
    }

    @Test
    void add_wraps_a_nonzero_exit_in_a_typed_exception_without_leaking_secrets() throws Exception {
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(1, "", "claude: connection refused"));

        assertThatThrownBy(() -> service.add(7, stdio("srv", "npx", null, Map.of("TOKEN", "s3cr3t"))))
                .isInstanceOf(McpRegistrationException.class)
                .hasMessageContaining("srv")
                .hasMessageNotContaining("s3cr3t"); // secret VALUE never in the error body
    }

    @Test
    void add_wraps_an_io_failure_in_a_typed_exception() throws Exception {
        when(exec.run(any(), any(), any())).thenThrow(new IOException("no such container"));

        assertThatThrownBy(() -> service.add(7, stdio("srv", "npx", null, null)))
                .isInstanceOf(McpRegistrationException.class);
    }

    @Test
    void remove_wraps_a_nonzero_exit_in_a_typed_exception() throws Exception {
        when(exec.run(any(), any(), any())).thenReturn(new ProcessExecutor.Result(2, "", "not found"));

        assertThatThrownBy(() -> service.remove(7, "srv")).isInstanceOf(McpRegistrationException.class);
    }
}
