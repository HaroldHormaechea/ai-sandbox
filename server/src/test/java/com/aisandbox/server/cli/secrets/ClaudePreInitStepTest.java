package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-19 — value-checked Claude pre-init capture + agent-teams settings merge.
 *
 * <p>Supersedes the UC06 "≥1 non-empty file" heuristic. Pinned behaviours:
 *
 * <ul>
 *   <li><b>AC2 capture</b> — both paths capture the state Claude reads to skip
 *       its first-run wizard: a {@code .claude.json} carrying
 *       {@code hasCompletedOnboarding=true} + a non-empty {@code oauthAccount}
 *       AND a non-empty {@code .credentials.json}. The interactive path relies
 *       on the {@code ln -sf …/.claude/.claude.json …/.claude.json} symlink in
 *       the container payload to redirect Claude's <i>sibling</i> file write
 *       into the mounted scratch. Coverage asserts the captured
 *       {@code .claude.json} CONTENT, not merely a non-empty file.</li>
 *   <li><b>AC5 / AC6 value check ({@code templateLooksOnboarded})</b> — shared
 *       by the interactive scratch and the {@code --claude-config-source} tree.
 *       hasCompletedOnboarding≠true, an empty/absent {@code oauthAccount}, a
 *       missing {@code .claude.json}, or a missing/empty {@code .credentials.json}
 *       all fail loud BEFORE seeding, so a malformed source can never produce a
 *       still-prompting session.</li>
 *   <li><b>AC5b zero-touch</b> — {@code --claude-config-source} with a pre-built,
 *       onboarded tree yields an AC2-satisfying template (recursive copy,
 *       modes preserved, contents land directly under the template dir).</li>
 *   <li><b>part E</b> — the captured {@code settings.json} ends with top-level
 *       {@code teammateMode:"tmux"} and {@code env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS:"1"}
 *       for BOTH paths, while preserving any keys already present (theme, hooks).</li>
 *   <li>{@code templateDir} ends at mode 0750 regardless of how it was populated.</li>
 * </ul>
 *
 * <p>All tests pass {@code ownership=null} so no real chown is attempted —
 * the {@code ai-sandbox-server} chown is exercised end-to-end by the
 * orchestrator tests / the operator install matrix, not here.
 *
 * <p>The {@code entrypoint.sh} per-session seeding block (cp the template into
 * {@code ~/.claude/}) is verified by the operator install matrix, not from a
 * JUnit harness (it needs a built image + a running container).
 */
class ClaudePreInitStepTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A {@code .claude.json} that satisfies {@code templateLooksOnboarded}. */
    private static final String ONBOARDED_CLAUDE_JSON =
            "{\"hasCompletedOnboarding\":true,\"oauthAccount\":{\"emailAddress\":\"dev@example.com\"},\"theme\":\"dark\"}";

    /** A non-empty login-token stand-in. */
    private static final String CREDENTIALS_JSON = "{\"claudeAiOauth\":{\"accessToken\":\"tok-abc\"}}";

    /**
     * Write the minimum the value check requires into {@code dir}: a valid
     * onboarded {@code .claude.json} + a non-empty {@code .credentials.json}.
     */
    private static void writeOnboardedState(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
        Files.writeString(dir.resolve(".credentials.json"), CREDENTIALS_JSON);
    }

    // ── flag-driven (--claude-config-source) — AC5b happy path ──────────

    @Test
    void flag_driven_copies_onboarded_source_and_captures_claude_json(@TempDir Path tmp) throws Exception {
        // A ~/.claude/-shaped tree captured from a completed login: the
        // onboarding state at the root + a couple of sibling files / a nested dir.
        Path src = tmp.resolve("src-claude");
        writeOnboardedState(src);
        Files.createDirectories(src.resolve("projects"));
        Files.writeString(src.resolve("settings.json"), "{\"theme\":\"dark\"}");
        Files.writeString(src.resolve("CLAUDE.md"), "# user prefs\n");
        Files.writeString(src.resolve("projects").resolve("notes.md"), "scratch\n");
        // Explicit modes so we can verify COPY_ATTRIBUTES preserves them on
        // files the part-E merge does NOT rewrite.
        Files.setPosixFilePermissions(src.resolve("CLAUDE.md"), PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(src.resolve("projects"), PosixFilePermissions.fromString("rwx------"));

        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        FakeConsoleIO io = new FakeConsoleIO();

        new ClaudePreInitStep(runner, io).run(src, templateDir, /* ownership */ null);

        // AC2 — the suppressing state is captured, with its CONTENT intact
        // (not merely "some non-empty file").
        assertThat(templateDir.resolve(".claude.json")).exists();
        JsonNode claudeJson = MAPPER.readTree(templateDir.resolve(".claude.json").toFile());
        assertThat(claudeJson.get("hasCompletedOnboarding").booleanValue()).isTrue();
        assertThat(claudeJson.get("oauthAccount").get("emailAddress").asText()).isEqualTo("dev@example.com");
        assertThat(templateDir.resolve(".credentials.json")).exists();
        assertThat(Files.size(templateDir.resolve(".credentials.json"))).isGreaterThan(0L);

        // Contents land directly under templateDir — NOT under a nested
        // templateDir/src-claude/ layer (the recursive copy skips the root).
        assertThat(templateDir.resolve("CLAUDE.md")).exists();
        assertThat(templateDir.resolve("projects").resolve("notes.md")).exists();

        // Modes preserved by COPY_ATTRIBUTES on the untouched file + dir.
        assertThat(Files.getPosixFilePermissions(templateDir.resolve("CLAUDE.md")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(templateDir.resolve("projects")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));

        // templateDir root itself normalises to 0750.
        assertThat(Files.getPosixFilePermissions(templateDir)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));

        // part E — agent-teams keys present on the flag path, theme preserved.
        JsonNode settings = MAPPER.readTree(templateDir.resolve("settings.json").toFile());
        assertThat(settings.get("teammateMode").asText()).isEqualTo("tmux");
        assertThat(settings.path("env").path("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS").asText())
                .isEqualTo("1");
        assertThat(settings.get("theme").asText()).isEqualTo("dark");

        // No docker shell-out on the flag-driven path.
        assertThat(runner.inheritCalls).isEmpty();
        assertThat(runner.captureCalls).isEmpty();
    }

    // ── flag-driven value-check negatives (AC5/AC6 fail-loud) ──────────

    @Test
    void flag_driven_source_without_claude_json_fails_loud(@TempDir Path tmp) throws Exception {
        // settings.json present (non-empty), but NO .claude.json — the old
        // ≥1-non-empty-file floor would have passed; the value check must not.
        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src);
        Files.writeString(src.resolve("settings.json"), "{\"theme\":\"dark\"}");
        Path templateDir = tmp.resolve("templates/claude-config");

        // Behaviour, not wording: throws IOException AND fails BEFORE seeding,
        // so the bad source is never copied into the template (exact remediation
        // text is intentionally not pinned — it's evolving with the dev's doc fix).
        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve("settings.json")).doesNotExist();
    }

    @Test
    void flag_driven_source_with_onboarding_not_completed_fails_loud(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src);
        Files.writeString(
                src.resolve(".claude.json"),
                "{\"hasCompletedOnboarding\":false,\"oauthAccount\":{\"emailAddress\":\"x@y.z\"}}");
        Files.writeString(src.resolve(".credentials.json"), CREDENTIALS_JSON);
        Path templateDir = tmp.resolve("templates/claude-config");

        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    @Test
    void flag_driven_source_with_empty_oauth_account_fails_loud(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src);
        Files.writeString(src.resolve(".claude.json"), "{\"hasCompletedOnboarding\":true,\"oauthAccount\":{}}");
        Files.writeString(src.resolve(".credentials.json"), CREDENTIALS_JSON);
        Path templateDir = tmp.resolve("templates/claude-config");

        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    @Test
    void flag_driven_source_missing_credentials_fails_loud(@TempDir Path tmp) throws Exception {
        // A valid .claude.json (account metadata) but NO login token — Claude
        // would still prompt to sign in, so this MUST fail loud + not seed.
        Path src = tmp.resolve("src-claude");
        Files.createDirectories(src);
        Files.writeString(src.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
        Path templateDir = tmp.resolve("templates/claude-config");

        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    @Test
    void flag_driven_non_directory_source_fails_loud(@TempDir Path tmp) throws Exception {
        // A regular-file source must fail loud (the value check fires before any
        // directory-shape check), not NPE or silently seed.
        Path src = tmp.resolve("not-a-dir");
        Files.writeString(src, "regular file");
        Path templateDir = tmp.resolve("templates/claude-config");

        assertThatThrownBy(() ->
                        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    // ── interactive path — symlink capture + AC2 + part E ──────────────

    @Test
    void interactive_runs_symlink_payload_and_captures_onboarded_scratch(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");

        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                // Claude writes the SIBLING ~/.claude.json (redirected by the
                // payload's symlink INTO the mounted scratch) plus the login
                // token at ~/.claude/.credentials.json and the theme.
                Files.writeString(scratch.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
                Files.writeString(scratch.resolve(".credentials.json"), CREDENTIALS_JSON);
                Files.writeString(scratch.resolve("settings.json"), "{\"theme\":\"dark\"}");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        new ClaudePreInitStep(runner, io).run(/* flag */ null, templateDir, null);

        // AC2 — onboarding state mirrored into the template, content intact.
        JsonNode claudeJson = MAPPER.readTree(templateDir.resolve(".claude.json").toFile());
        assertThat(claudeJson.get("hasCompletedOnboarding").booleanValue()).isTrue();
        assertThat(claudeJson.get("oauthAccount").get("emailAddress").asText()).isEqualTo("dev@example.com");
        assertThat(templateDir.resolve(".credentials.json")).exists();
        assertThat(Files.size(templateDir.resolve(".credentials.json"))).isGreaterThan(0L);

        // Argv shape: docker run --rm -it --user 1000:1000
        //   -v <scratch>:/home/claude/.claude --entrypoint sh ai-context:latest -c "<payload>".
        assertThat(runner.inheritCalls).hasSize(1);
        List<String> argv = runner.inheritCalls.get(0);
        assertThat(argv).startsWith("docker", "run", "--rm", "-it");
        assertThat(argv).containsSequence("--user", "1000:1000");
        assertThat(argv).doesNotContain("0"); // never a stray uid-0 override
        assertThat(argv).containsSequence("--entrypoint", "sh");
        assertThat(argv).contains("ai-context:latest");

        int vIdx = argv.indexOf("-v");
        assertThat(vIdx).isGreaterThan(0);
        assertThat(argv.get(vIdx + 1)).endsWith(":/home/claude/.claude");

        int cIdx = argv.indexOf("-c");
        assertThat(cIdx).isGreaterThan(0);
        String payload = argv.get(cIdx + 1);
        // UC-19 capture fix: the sibling-file symlink runs BEFORE claude so the
        // ~/.claude.json writes land inside the mounted scratch.
        assertThat(payload)
                .contains("ln -sf /home/claude/.claude/.claude.json /home/claude/.claude.json")
                .contains("claude --dangerously-skip-permissions");
        assertThat(payload.indexOf("ln -sf"))
                .as("symlink must be installed before claude runs")
                .isLessThan(payload.indexOf("claude --dangerously"));

        // part E — agent-teams keys present on the interactive path, theme preserved.
        JsonNode settings = MAPPER.readTree(templateDir.resolve("settings.json").toFile());
        assertThat(settings.get("teammateMode").asText()).isEqualTo("tmux");
        assertThat(settings.path("env").path("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS").asText())
                .isEqualTo("1");
        assertThat(settings.get("theme").asText()).isEqualTo("dark");

        // templateDir root is 0750.
        assertThat(Files.getPosixFilePermissions(templateDir)).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
    }

    @Test
    void interactive_treats_non_zero_container_exit_as_warning_when_scratch_is_usable(@TempDir Path tmp)
            throws Exception {
        // Operators routinely /exit Claude, which can surface as a non-zero
        // container exit. The value check is authoritative — a non-zero rc must
        // NOT abort the run when the scratch holds a usable, onboarded config.
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve(".claude.json"), ONBOARDED_CLAUDE_JSON);
                Files.writeString(scratch.resolve(".credentials.json"), CREDENTIALS_JSON);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 130; // SIGINT-ish, common when an operator types /exit.
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        new ClaudePreInitStep(runner, io).run(null, templateDir, null);

        assertThat(templateDir.resolve(".claude.json")).exists();
        // The step printed a "container exited with 130" note but did NOT abort.
        assertThat(io.allOutput()).contains("container exited with 130");
    }

    // ── interactive value-check negatives (AC6 fail-loud) ──────────────

    @Test
    void interactive_empty_scratch_fails_fast_with_remediation(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        // Container "succeeds" but produced nothing useful.
        runner.inheritResponse = argv -> 0;
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        // Behaviour, not wording: throws IOException and nothing is seeded.
        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    @Test
    void interactive_only_gitkeep_in_scratch_is_not_usable(@TempDir Path tmp) throws Exception {
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve(".gitkeep"), ""); // ignored by the floor
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class);
        assertThat(templateDir.resolve(".claude.json")).doesNotExist();
    }

    @Test
    void interactive_settings_only_scratch_without_claude_json_is_not_usable(@TempDir Path tmp) throws Exception {
        // Non-empty file present (clears the old floor) but no .claude.json /
        // .credentials.json — the value check must still fail loud.
        Path templateDir = tmp.resolve("templates/claude-config");
        FakeProcessRunner runner = new FakeProcessRunner();
        runner.inheritResponse = argv -> {
            Path scratch = scratchFromArgv(argv);
            try {
                Files.writeString(scratch.resolve("settings.json"), "{\"theme\":\"dark\"}");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            return 0;
        };
        FakeConsoleIO io = new FakeConsoleIO();
        io.tty = true;

        assertThatThrownBy(() -> new ClaudePreInitStep(runner, io).run(null, templateDir, null))
                .isInstanceOf(IOException.class);
        // The settings-only scratch was NOT copied into the template.
        assertThat(templateDir.resolve("settings.json")).doesNotExist();
    }

    // ── part E preservation + template-dir mode normalisation ──────────

    @Test
    void part_e_preserves_existing_settings_theme_and_hooks(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src-claude");
        writeOnboardedState(src);
        Files.writeString(
                src.resolve("settings.json"),
                "{\"theme\":\"dark\",\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"command\":\"echo hi\"}]}}");

        Path templateDir = tmp.resolve("templates/claude-config");
        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null);

        JsonNode settings = MAPPER.readTree(templateDir.resolve("settings.json").toFile());
        // Pre-existing keys preserved through the read-modify-write merge.
        assertThat(settings.get("theme").asText()).isEqualTo("dark");
        assertThat(settings.path("hooks").path("PreToolUse").isArray()).isTrue();
        assertThat(settings.get("hooks").get("PreToolUse").get(0).get("matcher").asText())
                .isEqualTo("Bash");
        // Agent-teams keys added.
        assertThat(settings.get("teammateMode").asText()).isEqualTo("tmux");
        assertThat(settings.path("env").path("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS").asText())
                .isEqualTo("1");
    }

    @Test
    void flag_driven_normalises_template_dir_to_0750_even_for_pre_existing_target(@TempDir Path tmp) throws Exception {
        // Re-seed scenario: templateDir already exists at a wrong mode (0700
        // from a stale prior install). The step MUST normalise to 0750.
        Path templateDir = tmp.resolve("templates/claude-config");
        Files.createDirectories(templateDir);
        Files.setPosixFilePermissions(templateDir, PosixFilePermissions.fromString("rwx------"));

        Path src = tmp.resolve("src-claude");
        writeOnboardedState(src);

        new ClaudePreInitStep(new FakeProcessRunner(), new FakeConsoleIO()).run(src, templateDir, null);

        assertThat(Files.getPosixFilePermissions(templateDir))
                .as("templateDir mode normalises to 0750 regardless of pre-existing perms")
                .isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
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
