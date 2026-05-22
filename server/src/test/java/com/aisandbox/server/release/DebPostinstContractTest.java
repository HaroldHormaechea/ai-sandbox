package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * UC-15 AC3 / AC7 — packaging contract test for the Debian
 * {@code postinst} hook.
 *
 * <p>The systemd unit (asserted by
 * {@link com.aisandbox.server.systemd.UnitFileContractTest#environment_includes_docker_config_redirect()})
 * sets {@code DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config} so
 * docker invocations don't reach into {@code $HOME/.docker} under
 * {@code ProtectHome=true}. For that redirect to actually work on a
 * fresh install, the postinst MUST pre-create the directory with the
 * correct mode + ownership BEFORE the first
 * {@code systemctl start ai-sandbox-server} touches docker.
 *
 * <p>This test parses {@code server/debian/postinst} as a string and
 * asserts the literal {@code install -d ...} invocation that creates
 * the directory. The regex pins:
 *
 * <ul>
 *   <li>mode {@code 0700} — docker writes credentials into
 *       {@code config.json}; world-readability would leak them.</li>
 *   <li>owner {@code ai-sandbox-server} and group
 *       {@code ai-sandbox-server} — the systemd unit's {@code User=}
 *       and {@code Group=} (well, the unit's group is {@code docker};
 *       the postinst-time group on the dir is {@code
 *       ai-sandbox-server} because the systemd ProtectHome path only
 *       requires read+write for the user — group ownership doesn't
 *       gate the access).</li>
 *   <li>path {@code /var/lib/ai-sandbox-server/docker-config} — the
 *       exact value the unit file's {@code Environment=DOCKER_CONFIG=...}
 *       references.</li>
 *   <li>line is anchored to start of line (with optional leading
 *       whitespace) and end of line — catches a regression where the
 *       line gets split or a stray suffix is appended.</li>
 * </ul>
 *
 * <p>Path discovery follows the same {@code System.getProperty("user.dir")}
 * pattern as {@link DebPackageTest} and {@link ReleaseBundleTest} — the
 * test JVM cwd is {@code server/}, so the postinst lives at
 * {@code server/debian/postinst}.
 */
class DebPostinstContractTest {

    /** Test JVM cwd is {@code server/}; the postinst lives under {@code debian/}. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path POSTINST_FILE = PROJECT_DIR.resolve("debian").resolve("postinst");

    /**
     * Exact-shape regex per UC-15 AC3 / AC7. {@code (?m)} multi-line mode
     * so {@code ^} / {@code $} match start / end of a line, not of the
     * entire input. Whitespace between tokens is {@code \s+} so a
     * future formatting tweak (extra spaces, alignment) does not break
     * the test; the option flags + value pairs are byte-exact.
     */
    private static final Pattern POSTINST_DOCKER_CONFIG_PATTERN =
            Pattern.compile("(?m)^\\s*install\\s+-d\\s+-m\\s+0700\\s+-o\\s+ai-sandbox-server\\s+-g\\s+ai-sandbox-server"
                    + "\\s+/var/lib/ai-sandbox-server/docker-config\\s*$");

    @Test
    void postinst_creates_docker_config_state_directory_with_correct_mode_and_ownership() throws IOException {
        assumeTrue(
                Files.isRegularFile(POSTINST_FILE),
                "postinst not found at " + POSTINST_FILE + " — test must run with cwd=server/");

        String text = Files.readString(POSTINST_FILE);
        assertThat(POSTINST_DOCKER_CONFIG_PATTERN.matcher(text).find())
                .as(
                        "UC-15 AC3 / AC7 — postinst MUST contain an `install -d -m 0700 -o ai-sandbox-server "
                                + "-g ai-sandbox-server /var/lib/ai-sandbox-server/docker-config` invocation so "
                                + "the systemd unit's `Environment=DOCKER_CONFIG=...` redirect has a writable "
                                + "directory to point at on a fresh install. Postinst text was:\n%s",
                        text)
                .isTrue();
    }

    /**
     * UC-17 AC1/AC2/AC9 — packaging contract for the debconf-driven
     * onboarding the postinst now performs. The wizard is invoked
     * non-interactively from {@code configure}; this guard pins the
     * shape of that integration so a future edit can't silently drop a
     * piece (and reintroduce a hang or a leaked token):
     *
     * <ul>
     *   <li>sources the debconf confmodule and reads the captured
     *       answers via {@code db_get};</li>
     *   <li>invokes {@code /usr/bin/aisandboxctl onboard} with stdin
     *       redirected from {@code /dev/null} and the headless flags
     *       {@code --no-claude-preinit --no-image-build} (so a dpkg run
     *       can never block on a TTY or a slow Docker build — AC1);</li>
     *   <li>clears the captured gh token from the debconf database
     *       (never persisted);</li>
     *   <li>retains the deferred "Next steps" fallback block pointing at
     *       {@code aisandboxctl onboard} (the no-onboard path — AC1/AC2);</li>
     *   <li>still ends with {@code exit 0} (the install never fails
     *       because of onboarding).</li>
     * </ul>
     */
    @Test
    void postinst_drives_debconf_onboarding_non_interactively_and_defers_cleanly() throws IOException {
        assumeTrue(
                Files.isRegularFile(POSTINST_FILE),
                "postinst not found at " + POSTINST_FILE + " — test must run with cwd=server/");

        String text = Files.readString(POSTINST_FILE);

        // Sources the confmodule + reads the captured answers.
        assertThat(text)
                .as("UC-17 — postinst MUST source the debconf confmodule")
                .contains(". /usr/share/debconf/confmodule");
        assertThat(text)
                .as("UC-17 — postinst MUST db_get the onboarding answers")
                .contains("db_get ai-sandbox-server/onboard")
                .contains("db_get ai-sandbox-server/git-name")
                .contains("db_get ai-sandbox-server/git-email");

        // Invokes the wizard non-interactively, stdin from /dev/null, with the
        // headless flags — AC1: a dpkg run can never hang on a TTY / image build.
        assertThat(text)
                .as("UC-17 — postinst MUST invoke `aisandboxctl onboard`")
                .contains("/usr/bin/aisandboxctl onboard");
        assertThat(text)
                .as("UC-17 AC1 — the onboard invocation MUST redirect stdin from /dev/null (no hang)")
                .containsPattern("/usr/bin/aisandboxctl onboard[^\\n]*</dev/null");
        assertThat(text)
                .as("UC-17 AC1 — onboarding during install MUST run with --no-claude-preinit --no-image-build")
                .contains("--no-claude-preinit")
                .contains("--no-image-build");

        // The captured gh token is wiped from the debconf db (never persisted).
        assertThat(text)
                .as("UC-17 — postinst MUST clear the gh token from the debconf database")
                .containsPattern("db_set\\s+ai-sandbox-server/gh-token\\s+\"\"");

        // The deferred Next-steps fallback survives (no-onboard / defer path).
        assertThat(text)
                .as("UC-17 AC1/AC2 — the deferred Next-steps block pointing at `aisandboxctl onboard` MUST remain")
                .contains("Onboarding was deferred")
                .contains("sudo aisandboxctl onboard");

        // Install never fails because of onboarding.
        assertThat(text).as("UC-17 — postinst MUST still end with exit 0").containsPattern("(?m)^exit 0\\s*$");
    }
}
