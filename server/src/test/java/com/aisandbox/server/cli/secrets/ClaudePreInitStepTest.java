package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC06 § AC6 + AC7 (template capture path). Pinned behaviours:
 *
 * <ul>
 *   <li>Flag-driven {@code --claude-config-source PATH} recursively
 *       copies the source tree into
 *       {@code <templates-dir>/claude-config/} preserving file
 *       modes (per the brief: "file modes preserved (typically
 *       0640/0750)").</li>
 *   <li>{@code templateDir} ends with mode 0750 regardless of how it
 *       was populated (orchestrator-side expectation).</li>
 *   <li>Interactive path spawns the documented {@code docker run}
 *       with {@code --entrypoint sh -c "claude
 *       --dangerously-skip-permissions"} and a scratch bind-mount
 *       at {@code /home/claude/.claude}.</li>
 *   <li>Success heuristic (AC6 spike-not-run guidance): scratch
 *       contains ≥1 regular file other than {@code .gitkeep} AND the
 *       non-{@code .gitkeep} byte total is non-zero. Anything less
 *       fails fast with the documented remediation.</li>
 * </ul>
 *
 * <p>The {@code entrypoint.sh} side of AC7 (the per-session
 * {@code cp -a /etc/claude-template/. ~/.claude/} seeding block) is
 * verified by the {@code release-install-smoke} CI job — exercising
 * it from a JUnit harness would require booting docker, which the
 * developer's note explicitly rules out (no docker in this dev
 * sandbox; 2026-05-18).
 */
class ClaudePreInitStepTest {

    @Test
    void flag_driven_recursively_copies_source_into_template_dir(@TempDir Path tmp) throws Exception {
        // Source tree mirrors a ~/.claude/ shape: settings.json, a
        // CLAUDE.md, and a nested projects/ subdir.
        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src.resolve("projects"));
        Files.writeString(src.resolve("settings.json"), "{\"theme\":\"dark\"}");
        Files.writeString(src.resolve("CLAUDE.md"), "# user prefs\n");
        Files.writeString(src.resolve("projects").resolve("notes.md"), "scratch\n");
        // Explicit modes so we can verify COPY_ATTRIBUTES preserves them.
        Files.setPosixFilePermissions(src.resolve("settings.json"), PosixFilePermissions.fromString("rw-r-----"));
        Files.setPosixFilePermissions(src.resolve("CLAUDE.md"), PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(src.resolve("projects"), PosixFilePermissions.fromString("rwx------"));

        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new ClaudePreInitStep(runner, io).run(src, templateDir, /* ownership */ null);

        // The contents land directly under templateDir — NOT under a
        // nested templateDir/src-claude/ layer (the recursive copy
        // skips the source root).
        assertThat(templateDir.resolve("settings.json")).exists();
        assertThat(templateDir.resolve("CLAUDE.md")).exists();
        assertThat(templateDir.resolve("projects").resolve("notes.md")).exists();
        assertThat(Files.readString(templateDir.resolve("settings.json"))).isEqualTo("{\"theme\":\"dark\"}");

        // File modes preserved per AC6.
        assertThat(Files.getPosixFilePermissions(templateDir.resolve("settings.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
        assertThat(Files.getPosixFilePermissions(templateDir.resolve("CLAUDE.md")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(templateDir.resolve("projects")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));

        // templateDir root itself is 0750 (AC6 § "mode 0750 owned ai-sandbox-server").
        assertThat(Files.getPosixFilePermissions(templateDir)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));

        // No docker shell-out for the flag-driven path.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(runner.captureCalls).isEmpty();
    }

    @Test
    void flag_driven_source_must_be_directory(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("not-a-dir");
        Files.writeString(src, "regular file"); // not a directory
        Path templateDir = tmp.resolve("templates/claude-config");

        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("source is not a directory");
    }

    @Test
    void interactive_runs_documented_docker_argv_and_captures_scratch(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            // Pull the scratch bind-mount target out of `-v <scratch>:/home/claude/.claude`.
            Path scratch = scratchFromArgv(argv);
            try {
                // Simulate Claude CLI writing a real-looking config into
                // the bind-mounted scratch.
                Files.writeString(scratch.resolve("settings.json"), "{\"oauth\":\"completed\"}");
                Files.createDirectories(scratch.resolve("statsig"));
                Files.writeString(scratch.resolve("statsig").resolve("stableID"), "uuid\n");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        new ClaudePreInitStep(runner, io).run(/* flag */ null, templateDir, null);

        // Scratch contents landed in templateDir.
        assertThat(templateDir.resolve("settings.json")).exists();
        assertThat(Files.readString(templateDir.resolve("settings.json"))).isEqualTo("{\"oauth\":\"completed\"}");
        assertThat(templateDir.resolve("statsig").resolve("stableID")).exists();

        // Final mode on templateDir is 0750.
        assertThat(Files.getPosixFilePermissions(templateDir)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));

        // Argv shape: docker run --rm -it --user 1000:1000 -v <scratch>:/home/claude/.claude
        // --entrypoint sh ai-context:latest -c "claude --dangerously-skip-permissions".
        // The --user override is the image's runtime claude user
        // (uid 1000:1000 per SandboxDockerfile), NOT root — Claude
        // Code refuses to run with --dangerously-skip-permissions as
        // uid 0, so the previous --user 0 design crashed step 4/4.
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> argv = runner.inheritCalls.get(0);
        assertThat(argv).startsWith("docker", "run", "--rm", "-it");
        assertThat(argv).containsSequence("--user", "1000:1000");
        assertThat(argv).doesNotContain("0"); // belt-and-suspenders: no stray uid-0 override
        assertThat(argv).containsSequence("--entrypoint", "sh");
        assertThat(argv).contains("ai-context:latest");
        int cIdx = argv.indexOf("-c");
        assertThat(cIdx).isGreaterThan(0);
        assertThat(argv.get(cIdx + 1)).contains("claude").contains("--dangerously-skip-permissions");
    }

    @Test
    void interactive_empty_scratch_fails_fast_with_remediation(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        // Container "succeeds" but produced nothing useful — AC6's
        // outcome-B branch (Claude CLI doesn't support headless OAuth).
        runner.inheritResponse = argv -> 0;
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Claude pre-init did not produce a usable config")
                .hasMessageContaining("--claude-config-source")
                .hasMessageContaining("--no-claude-preinit");
    }

    @Test
    void interactive_only_gitkeep_in_scratch_is_not_usable(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve(".gitkeep"), ""); // ignored by heuristic
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Claude pre-init did not produce a usable config");
    }

    @Test
    void interactive_only_zero_byte_file_in_scratch_is_not_usable(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve("settings.json"), ""); // 0 bytes
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Claude pre-init did not produce a usable config");
    }

    @Test
    void interactive_treats_non_zero_container_exit_as_warning_when_scratch_is_usable(@TempDir Path tmp)
            throws Exception {
        // Operators routinely /exit Claude which can surface as a
        // non-zero container exit. The step's contract is "the
        // heuristic is authoritative" — non-zero rc must NOT abort the
        // run when the scratch has good content.
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve("settings.json"), "{\"oauth\":\"ok\"}");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 130; // SIGINT-ish, common when an operator types /exit.
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        new ClaudePreInitStep(runner, io).run(null, templateDir, null);

        assertThat(templateDir.resolve("settings.json")).exists();
        // The step printed a "container exited with 130" note but did
        // NOT abort.
        assertThat(io.allOutput()).contains("container exited with 130");
    }

    @Test
    void flag_driven_mode_0750_on_template_dir_root_even_for_pre_existing_target(@TempDir Path tmp) throws Exception {
        // Re-seed scenario: templateDir already exists with a wrong
        // mode (e.g. 0700 from a stale prior install). The step MUST
        // normalise to 0750.
        Path templateDir = tmp.resolve("templates/claude-config");
        Files.createDirectories(templateDir);
        Files.setPosixFilePermissions(templateDir, PosixFilePermissions.fromString("rwx------"));

        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src);
        Files.writeString(src.resolve("settings.json"), "{}");

        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null);

        assertThat(Files.getPosixFilePermissions(templateDir))
                .as("templateDir mode normalises to 0750 regardless of pre-existing perms")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE);
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Extract the host-side path from a {@code -v <host>:<container>} pair in argv. */
    private static Path scratchFromArgv(List<String> argv) {
        for (int i = 0; i < argv.size() - 1; i++) {
            if ("-v".equals(argv.get(i))) {
                String spec = argv.get(i + 1);
                int colon = spec.indexOf(':');
                if (colon > 0) {
                    return Path.of(spec.substring(0, colon));
                }
            }
        }
        throw new IllegalStateException("no -v <host>:<container> arg in " + argv);
    }
}
