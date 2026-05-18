package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC06 § AC4 — git identity step coverage. The step writes
 * {@code <secrets-dir>/gitconfig} with mode 0600, the exact
 * {@code [user] name/email} layout consumed by {@code entrypoint.sh}'s
 * {@code git config --global include.path} hook, and validates the
 * email shape via the {@code setup.sh:validate_email} regex.
 */
class GitIdentityStepTest {

    @Test
    void writes_gitconfig_with_documented_shape_and_mode_0600(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new GitIdentityStep(runner, io).run("Alice Example", "alice@example.com", out, /* ownership */ null);

        // The exact body shape — entrypoint.sh's `git config --global
        // include.path` hook depends on this layout. Pin it.
        String body = Files.readString(out);
        assertThat(body).isEqualTo("[user]\n\tname = Alice Example\n\temail = alice@example.com\n");

        // Mode 0600 — the file holds the operator's git identity in
        // plaintext but is bind-mounted RO into the container; outside
        // the container it stays owner-only.
        assertThat(Files.getPosixFilePermissions(out))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        // Flag-driven run does not need git config defaults; no
        // subprocess should fire.
        assertThat(runner.captureCalls)
                .as("git config --global --get must not run when both flags are supplied")
                .isEmpty();
        assertThat(io.printed)
                .as("no interactive prompting when both flags are supplied")
                .isEmpty();
    }

    @Test
    void rejects_email_missing_at_sign(@TempDir Path tmp) {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        // setup.sh:validate_email accepts ^[^@\s]+@[^@\s]+\.[^@\s]+$
        assertThatThrownBy(() -> new GitIdentityStep(runner, io).run("Alice", "not-an-email", out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not RFC-5322-ish");
        assertThat(Files.exists(out)).isFalse();
    }

    @Test
    void rejects_email_missing_tld(@TempDir Path tmp) {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        assertThatThrownBy(() -> new GitIdentityStep(runner, io).run("Alice", "alice@example", out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not RFC-5322-ish");
    }

    @Test
    void rejects_blank_name(@TempDir Path tmp) {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        assertThatThrownBy(() -> new GitIdentityStep(runner, io).run("   ", "alice@example.com", out, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("name is empty");
    }

    @Test
    void trims_flag_values_before_writing(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new GitIdentityStep(runner, io).run("  Alice Example  ", "  alice@example.com  ", out, null);

        // Leading/trailing whitespace in flag values is stripped so the
        // emitted gitconfig matches the setup.sh contract.
        assertThat(Files.readString(out)).isEqualTo("[user]\n\tname = Alice Example\n\temail = alice@example.com\n");
    }

    @Test
    void interactive_uses_git_global_defaults_when_operator_just_hits_enter(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> {
            // mirror `git config --global --get user.name|user.email`
            if (argv.contains("user.name")) {
                return new ProcessRunner.Result(0, "Default Alice\n");
            }
            if (argv.contains("user.email")) {
                return new ProcessRunner.Result(0, "default-alice@example.com\n");
            }
            return new ProcessRunner.Result(1, "");
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        // operator hits enter twice → defaults accepted.
        io.inputLines.add("");
        io.inputLines.add("");

        new GitIdentityStep(runner, io).run(/* name */ null, /* email */ null, out, null);

        assertThat(Files.readString(out))
                .isEqualTo("[user]\n\tname = Default Alice\n\temail = default-alice@example.com\n");

        // The two `git config --global --get …` probes fired.
        assertThat(runner.captureCalls).hasSize(2);
        assertThat(runner.captureCalls.get(0)).containsSubsequence("git", "config", "--global", "--get", "user.name");
        assertThat(runner.captureCalls.get(1)).containsSubsequence("git", "config", "--global", "--get", "user.email");
        // Prompts displayed the default in [ ] brackets.
        assertThat(io.allOutput()).contains("[Default Alice]").contains("[default-alice@example.com]");
    }

    @Test
    void interactive_reprompts_on_invalid_email_until_valid(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("gitconfig");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.captureResponse = argv -> new ProcessRunner.Result(1, ""); // no defaults
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;
        io.inputLines.add("Alice"); // name
        io.inputLines.add("not-an-email"); // bad email → reprompt
        io.inputLines.add("alice@example.com"); // good

        new GitIdentityStep(runner, io).run(null, null, out, null);

        assertThat(Files.readString(out)).isEqualTo("[user]\n\tname = Alice\n\temail = alice@example.com\n");
        assertThat(io.allOutput()).contains("Email must look like name@host.tld.");
    }

    /**
     * Sanity check on the regex itself — the {@code setup.sh} contract
     * is "looks like name@host.tld", not "RFC-5322-compliant". Tighten
     * gradually if the install flow ever rejects valid addresses.
     */
    @Test
    void email_regex_mirrors_setup_sh_validate_email() {
        assertThat(GitIdentityStep.EMAIL_PATTERN.matcher("alice@example.com").matches())
                .isTrue();
        assertThat(GitIdentityStep.EMAIL_PATTERN.matcher("a.b+c@sub.example.co").matches())
                .isTrue();
        assertThat(GitIdentityStep.EMAIL_PATTERN.matcher("no-at-sign").matches())
                .isFalse();
        assertThat(GitIdentityStep.EMAIL_PATTERN.matcher("two@@example.com").matches())
                .isFalse();
        assertThat(GitIdentityStep.EMAIL_PATTERN.matcher("no-tld@example").matches())
                .isFalse();
        assertThat(GitIdentityStep.EMAIL_PATTERN
                        .matcher("white space@example.com")
                        .matches())
                .isFalse();
    }
}
