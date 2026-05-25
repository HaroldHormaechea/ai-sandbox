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

    /**
     * v0.0.19 crashloop fix — the postinst's operator-facing "Next steps"
     * blocks MUST NOT claim the server refuses to <em>start</em> on an empty
     * allowlist. That stale wording described the pre-fix behavior; the fix
     * makes the server boot on an empty allowlist and reject every request
     * (401) until a client is authorized. This guard pins the corrected
     * wording and forbids any regression to the "refuses to start" framing.
     *
     * <p>The check is deliberately narrow: it forbids the phrase
     * {@code refuse[s] to start} (the stale claim) while leaving the correct
     * {@code refuses every request (401)} wording untouched.
     */
    @Test
    void postinst_does_not_claim_the_server_refuses_to_start_on_an_empty_allowlist() throws IOException {
        assumeTrue(
                Files.isRegularFile(POSTINST_FILE),
                "postinst not found at " + POSTINST_FILE + " — test must run with cwd=server/");

        String text = Files.readString(POSTINST_FILE);

        // NEGATIVE — the stale pre-fix framing is gone.
        assertThat(text)
                .as("v0.0.19 fix — postinst MUST NOT claim the server `refuses to start` on an empty "
                        + "allowlist; the fix makes it boot and 401 until a client is authorized")
                .doesNotContainPattern("(?i)refuses?\\s+to\\s+start");

        // POSITIVE — the corrected wording is present (tolerant of the
        // heredoc line-wrap between "empty" and "allowlist").
        assertThat(text)
                .as("v0.0.19 fix — postinst MUST tell the operator the server `starts fine on an empty "
                        + "allowlist` but rejects requests until a client is authorized")
                .containsPattern("(?is)starts fine on an empty\\s+allowlist");
    }

    // ── UC-19 — new packaging contract ────────────────────────────────

    /** Convenience — read the postinst, skipping when it isn't on disk (cwd≠server/). */
    private static String postinst() throws IOException {
        assumeTrue(
                Files.isRegularFile(POSTINST_FILE),
                "postinst not found at " + POSTINST_FILE + " — test must run with cwd=server/");
        return Files.readString(POSTINST_FILE);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * Drop whole-line shell comments so a negative assertion targets actual
     * CODE, not the postinst's (deliberate) explanatory comments — several of
     * which name the dash-unsafe probe form and the headless flags precisely to
     * document why they are NOT used on a given path.
     */
    private static String stripCommentLines(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.strip().startsWith("#")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * UC-19 C1 — the controlling-terminal probe MUST be the dash-safe
     * subshell form {@code ( true <>/dev/tty ) 2>/dev/null}, NOT a
     * {@code { exec 3<>/dev/tty; } 2>/dev/null} brace-group guard. On dash a
     * redirection failure on the {@code exec} special builtin aborts the
     * (non-interactive) shell even inside an {@code if}/group/redirect guard,
     * which would fail the install (violating AC4 "exit 0 always") on no-tty
     * hosts. A subshell + a regular builtin contains the failure to the
     * subshell, so the parent only sees a non-zero status.
     */
    @Test
    void postinst_probes_tty_with_dash_safe_subshell_not_exec_guard() throws IOException {
        String text = postinst();

        // POSITIVE — the subshell probe that sets HAVE_TTY.
        assertThat(text)
                .as("UC-19 C1 — postinst MUST probe /dev/tty via the dash-safe `( true <>/dev/tty )` subshell")
                .contains("( true <>/dev/tty )")
                .containsPattern("(?s)\\(\\s*true\\s*<>/dev/tty\\s*\\)\\s*2>/dev/null;\\s*then\\s*HAVE_TTY=1");

        // NEGATIVE — the unsafe brace-group `exec` probe form must NOT be used in
        // CODE. (A comment deliberately names it to document why; strip comments.)
        assertThat(stripCommentLines(text))
                .as("UC-19 C1 — postinst MUST NOT probe with the dash-unsafe `{ exec 3<>/dev/tty; }` form")
                .doesNotContainPattern("\\{\\s*exec\\s+3<>/dev/tty");
    }

    /**
     * UC-19 AC3 — when a controlling terminal is present (true under BOTH
     * {@code dpkg -i} and {@code apt install}), the postinst offers a [Y/n]
     * invite and, on accept, runs the FULL interactive wizard on /dev/tty
     * (fd 3) WITHOUT the headless {@code --no-claude-preinit} /
     * {@code --no-image-build} flags — so the Claude device-flow login + the
     * image build actually run. The headless flags belong only to the
     * no-terminal preseeded branch (asserted elsewhere).
     */
    @Test
    void postinst_interactive_branch_invites_and_runs_onboard_on_tty_without_headless_flags() throws IOException {
        String text = postinst();

        assertThat(text)
                .as("UC-19 AC3 — interactive branch MUST present a [Y/n] onboarding invite")
                .contains("[Y/n]");
        assertThat(text)
                .as("UC-19 AC3 — interactive onboard MUST be wired to the terminal (fd 3), not /dev/null")
                .contains("/usr/bin/aisandboxctl onboard \"$@\" <&3 >&3 2>&3");

        // Isolate the interactive branch CODE (guard line → fd-3 close, comments
        // stripped) and prove it carries NO headless flags — those would suppress
        // exactly the Claude pre-init capture this branch exists to perform.
        String code = stripCommentLines(text);
        int start = code.indexOf(
                "[ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"0\" ] && [ \"$HAVE_TTY\" = \"1\" ] && [ \"$ONBOARD\" != \"false\" ]");
        int end = code.indexOf("exec 3>&-", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String interactiveBranch = code.substring(start, end);
        assertThat(interactiveBranch)
                .as("UC-19 AC3 — the interactive onboard MUST NOT pass the headless flags")
                .doesNotContain("--no-claude-preinit")
                .doesNotContain("--no-image-build");
    }

    /**
     * UC-19 AC3 — the interactive invite fires on the presence of a terminal
     * (so a raw {@code dpkg -i}, which never runs the debconf {@code config}
     * script, still triggers it) and is NOT gated on {@code onboard=true};
     * it is suppressed only when a snapshot already exists OR the operator
     * explicitly opted out via debconf ({@code onboard=false}).
     */
    @Test
    void postinst_interactive_invite_is_tty_gated_not_answer_gated_and_respects_optout() throws IOException {
        String text = postinst();
        assertThat(text)
                .as("UC-19 AC3 — interactive invite gated on (no snapshot) AND (have tty) AND (not opted out)")
                .contains(
                        "[ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"0\" ] && [ \"$HAVE_TTY\" = \"1\" ] && [ \"$ONBOARD\" != \"false\" ]");
    }

    /**
     * UC-19 AC9 — the captured gh token never reaches the process table: it is
     * staged into a mode-0600 temp file and handed to {@code onboard} via
     * {@code --gh-token-file}, never as a {@code --gh-token <value>} argv (which
     * would be world-visible in {@code ps}). The temp file is shredded/removed
     * after use, and (asserted by the existing UC-17 test) cleared from the
     * debconf database.
     */
    @Test
    void postinst_gh_token_staged_in_0600_tempfile_never_on_command_line() throws IOException {
        String text = postinst();

        assertThat(text)
                .as("UC-19 AC9 — gh token staged into a temp file at mode 0600")
                .contains("GH_TOKEN_FILE=\"$(mktemp)\"")
                .contains("chmod 600 \"$GH_TOKEN_FILE\"")
                .contains("printf '%s' \"$GH_TOKEN\" > \"$GH_TOKEN_FILE\"");
        assertThat(text)
                .as("UC-19 AC9 — onboard receives the token by file reference, not by value")
                .contains("--gh-token-file \"$GH_TOKEN_FILE\"");

        // NEGATIVE — the raw token value is never passed as a `--gh-token <value>`
        // argv (matches `--gh-token` followed by whitespace; `--gh-token-file` is
        // followed by `-`, so it is correctly excluded).
        assertThat(text)
                .as("UC-19 AC9 — the raw gh token MUST NOT appear on a command line (process table leak)")
                .doesNotContainPattern("--gh-token\\s");

        assertThat(text)
                .as("UC-19 AC9 — the gh-token temp file is wiped after onboarding")
                .containsPattern("(shred\\s+-u|rm\\s+-f)\\s+\"\\$GH_TOKEN_FILE\"");
    }

    /**
     * UC-19 — re-install / re-run idempotency (AC7): when a non-empty Claude
     * pre-init snapshot is already present in the installed template, the
     * postinst neither re-prompts nor defers — it prints the "already
     * onboarded" next-step block. The snapshot gate keys on a non-empty
     * {@code .claude.json} (the post-UC-19 capture marker).
     */
    @Test
    void postinst_already_onboarded_snapshot_present_emits_nothing_to_do_next_step() throws IOException {
        String text = postinst();

        // The snapshot gate.
        assertThat(text)
                .contains("CLAUDE_TEMPLATE_JSON=/etc/ai-sandbox-server/templates/claude-config/.claude.json")
                .contains("if [ -s \"$CLAUDE_TEMPLATE_JSON\" ]; then")
                .contains("CLAUDE_SNAPSHOT_PRESENT=1");

        // The dedicated next-step branch + its distinctive marker.
        assertThat(text).contains("elif [ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"1\" ]; then");
        assertThat(text)
                .as("UC-19 AC7 — a present snapshot yields the `already onboarded` next-step (no re-prompt)")
                .containsPattern("already onboarded \\(a Claude pre-init snapshot is\\s+present\\)");
    }

    /**
     * UC-19 — the upgrade-with-stale-template next step: on an upgrade
     * ({@code $2} non-empty) where no snapshot is present, the operator is told
     * the capture changed and to refresh with {@code aisandboxctl onboard --force}.
     */
    @Test
    void postinst_upgrade_with_stale_template_points_at_onboard_force() throws IOException {
        String text = postinst();

        // Upgrade indicator captured from $2 before $@ is reused.
        assertThat(text).contains("IS_UPGRADE=0").containsPattern("if \\[ -n \"\\$2\" \\]; then\\s*IS_UPGRADE=1");

        // The upgrade-stale branch + its actionable next step.
        assertThat(text).contains("elif [ \"$IS_UPGRADE\" = \"1\" ] && [ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"0\" ]; then");
        assertThat(text)
                .as("UC-19 — the upgrade-stale next step MUST point at `onboard --force`")
                .containsPattern("sudo aisandboxctl onboard --force");
    }

    /**
     * UC-19 AC4 — the "Next steps" output is a SINGLE {@code if/elif…/else}
     * chain, so exactly one block prints for any install state (one
     * unambiguous next step). Pins both the chained structure (each guard is
     * an {@code elif}/{@code else}, never a fresh {@code if}) and that each of
     * the five distinct block markers occurs exactly once.
     */
    @Test
    void postinst_next_step_blocks_form_one_mutually_exclusive_chain() throws IOException {
        String text = postinst();

        // Chained guards — proves runtime mutual exclusivity (only one branch runs).
        assertThat(text).contains("if [ \"$ONBOARD_DONE\" = \"1\" ] && [ \"$ONBOARD_INTERACTIVE\" = \"1\" ]; then");
        assertThat(text).contains("elif [ \"$ONBOARD_DONE\" = \"1\" ]; then");
        assertThat(text).contains("elif [ \"$IS_UPGRADE\" = \"1\" ] && [ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"0\" ]; then");
        assertThat(text).contains("elif [ \"$CLAUDE_SNAPSHOT_PRESENT\" = \"1\" ]; then");

        // Each distinct next-step shape's marker appears exactly once.
        assertThat(count(text, "onboarded interactively"))
                .as("interactive-onboarded block marker occurs exactly once")
                .isEqualTo(1);
        assertThat(count(text, "Claude pre-init was NOT captured"))
                .as("non-interactive-onboarded block marker occurs exactly once")
                .isEqualTo(1);
        assertThat(count(text, "already onboarded"))
                .as("already-onboarded block marker occurs exactly once")
                .isEqualTo(1);
        assertThat(count(text, "Onboarding was deferred"))
                .as("deferred block marker occurs exactly once")
                .isEqualTo(1);
    }

    // ── server-ci 6h-hang fix — bounded interactive read + noninteractive guard ──
    //
    // Root cause (challenger-approved proposal): the postinst Branch-1 [Y/n]
    // onboarding invite did an UNBOUNDED `read REPLY <&3` which, reached via the
    // un-timeout-wrapped `apt-get install -f` configure step, blocked the
    // `release-install-smoke` job until GitHub Actions' 360-min ceiling (~6h hang).
    // The three guards below pin the fix so a future edit can't silently re-open it.

    /**
     * Hang fix (i) — the interactive {@code [Y/n]} invite's read on the
     * controlling terminal (fd 3) MUST be BOUNDED.
     *
     * <ul>
     *   <li>POSITIVE — the read runs inside a {@code timeout <N> sh -c '… read … '}
     *       child shell fed from fd 3 ({@code <&3}), so a stuck or EOF read can
     *       never wedge {@code dpkg}.</li>
     *   <li>NEGATIVE — there is NO bare, un-{@code timeout}-wrapped
     *       {@code read … <&3} statement (the regressed form). Asserted against
     *       comment-stripped CODE so the postinst's explanatory comments — which
     *       deliberately name the old unbounded form to document why it was
     *       removed — do not trip the check.</li>
     * </ul>
     */
    @Test
    void postinst_interactive_read_is_timeout_bounded_with_no_unbounded_fd3_read() throws IOException {
        String text = postinst();

        // POSITIVE — the fd-3 read is wrapped in a `timeout <N> sh -c '… read … '`.
        assertThat(text)
                .as("hang fix (i) — the interactive fd-3 read MUST be wrapped in a `timeout <N> sh -c` child")
                .containsPattern("timeout\\s+\\d+\\s+sh\\s+-c\\b[^\\n]*\\bread\\b[^\\n]*<&3");

        // NEGATIVE — no bare `read <var> <&3` line (the unbounded regressed form).
        assertThat(stripCommentLines(text))
                .as("hang fix (i) — postinst MUST NOT contain a bare, un-timeout-wrapped `read … <&3` "
                        + "(the unbounded form that produced the ~6h CI hang)")
                .doesNotContainPattern("(?m)^\\s*read\\b[^\\n]*<&3");
    }

    /**
     * Hang fix (ii) — when the bounded read times out (no input within the
     * window) or hits EOF (fd 3 closed), the postinst MUST DEFER: it falls into
     * the read-{@code if}'s {@code else} arm and NEVER reaches the
     * {@code aisandboxctl onboard} invocation. A regression that dropped the
     * {@code else} — or wired onboard onto the failure path — would re-open the
     * door to a stuck install.
     *
     * <p>Strategy: isolate the read conditional's success arm (up to its
     * {@code esac}) and its defer arm (the {@code else} after {@code esac}, up to
     * the {@code exec 3>&-} that closes Branch 1). The interactive onboard
     * invocation MUST live only in the success arm.
     */
    @Test
    void postinst_bounded_read_timeout_or_eof_defers_without_invoking_onboard() throws IOException {
        String code = stripCommentLines(postinst());

        // The actual wizard exec on the interactive path (fd 3 wired in).
        final String onboardInvocation = "/usr/bin/aisandboxctl onboard \"$@\" <&3";

        int readIf = code.indexOf("REPLY=\"$(timeout");
        assertThat(readIf)
                .as("hang fix (ii) — the bounded fd-3 read conditional must be present")
                .isGreaterThanOrEqualTo(0);

        int esac = code.indexOf("esac", readIf);
        assertThat(esac)
                .as("hang fix (ii) — the read conditional must wrap a `case \"$REPLY\"` … `esac`")
                .isGreaterThan(readIf);

        int deferElse = code.indexOf("else", esac);
        assertThat(deferElse)
                .as("hang fix (ii) — the bounded read MUST have an `else` (defer) arm for the timeout/EOF case")
                .isGreaterThan(esac);

        int branchEnd = code.indexOf("exec 3>&-", deferElse);
        assertThat(branchEnd)
                .as("hang fix (ii) — Branch 1 must close with `exec 3>&-` after the defer arm")
                .isGreaterThan(deferElse);

        String successArm = code.substring(readIf, esac);
        String deferArm = code.substring(deferElse, branchEnd);

        assertThat(successArm)
                .as("hang fix (ii) — the interactive onboard invocation MUST live in the read-SUCCESS arm")
                .contains(onboardInvocation);
        assertThat(deferArm)
                .as("hang fix (ii) — the timeout/EOF DEFER arm MUST NOT invoke onboard")
                .doesNotContain(onboardInvocation);
    }

    /**
     * Hang fix (iii) — defence-in-depth on top of the timeout: when
     * {@code DEBIAN_FRONTEND=noninteractive} (every CI / unattended install),
     * the postinst forces {@code HAVE_TTY=0} so the {@code [Y/n]} invite — and
     * the bounded read beneath it — is skipped entirely (no 30s wait). Debian
     * Policy 3.9.1 also forbids maintainer-script prompting under the
     * noninteractive frontend.
     */
    @Test
    void postinst_forces_have_tty_zero_under_noninteractive_frontend() throws IOException {
        String text = postinst();

        assertThat(text)
                .as("hang fix (iii) — postinst MUST test DEBIAN_FRONTEND for `noninteractive`")
                .containsPattern("\\$\\{DEBIAN_FRONTEND:-\\}\"\\s*=\\s*\"noninteractive\"");
        assertThat(text)
                .as("hang fix (iii) — under the noninteractive frontend the postinst MUST force HAVE_TTY=0 "
                        + "(skip the [Y/n] invite + the bounded read)")
                .containsPattern("\\$\\{DEBIAN_FRONTEND:-\\}\"\\s*=\\s*\"noninteractive\"[^\\n]*\\n\\s*HAVE_TTY=0");
    }
}
