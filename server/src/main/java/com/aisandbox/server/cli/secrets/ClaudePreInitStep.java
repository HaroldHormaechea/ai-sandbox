package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * UC06 § AC6 / UC-19 — step (d) of {@code aisandboxctl secrets seed}
 * and the Claude component of {@code aisandboxctl onboard}: capture a
 * {@code ~/.claude/}-shaped template at
 * {@code <templates-dir>/claude-config/} that
 * {@code docker-compose.yml}'s RO bind-mount seeds into every spawned
 * session's {@code ~/.claude/} via {@code entrypoint.sh}.
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@code --no-claude-preinit} — orchestrator skips this step;
 *       the bind-mount becomes a no-op (target dir empty), and
 *       sessions behave as they did before UC06.</li>
 *   <li>{@code --claude-config-source PATH} — recursive copy of an
 *       operator-supplied {@code ~/.claude/}-shaped directory
 *       preserving file modes. The documented zero-touch / headless
 *       path (UC-19 AC5b): a workstation-built template ships to a
 *       server that can't do interactive OAuth without operator fuss.</li>
 *   <li>interactive — spawns an ephemeral {@code ai-context:latest}
 *       container with a scratch tempdir bound at
 *       {@code /home/claude/.claude}, runs
 *       {@code claude --dangerously-skip-permissions} with inheritIO
 *       so the device-flow URL / code surface to the operator. When
 *       the operator finishes (or types {@code /exit}), the value
 *       check below decides if the scratch is usable.</li>
 * </ul>
 *
 * <p><b>UC-19 capture fix.</b> Claude writes its first-run state
 * (completed-onboarding flag + signed-in {@code oauthAccount}) to the
 * <i>sibling</i> {@code ~/.claude.json}, which lives <i>outside</i> the
 * {@code ~/.claude/} dir we bind-mount — so the old interactive payload
 * lost exactly the state that suppresses the in-container first-run
 * wizard. The container payload now prepends the same symlink
 * {@code entrypoint.sh} uses for live sessions
 * ({@code ln -sf /home/claude/.claude/.claude.json /home/claude/.claude.json})
 * so Claude's {@code ~/.claude.json} writes land inside the mounted
 * scratch and mirror into the template. The login token still lands at
 * {@code ~/.claude/.credentials.json} (already inside the mount) and the
 * theme at {@code ~/.claude/settings.json}.
 *
 * <p><b>UC-19 AC6 value check.</b> The old heuristic ("≥1 non-empty
 * file") passed even when the suppressing state was absent. The check
 * now parses the captured {@code .claude.json} and requires
 * {@code hasCompletedOnboarding == true} (boolean) AND a present,
 * non-empty {@code oauthAccount} object, AND a present, non-empty
 * {@code .credentials.json} (the real login token — {@code oauthAccount}
 * is only metadata, so metadata-without-credentials would still
 * re-prompt). The original ≥1-non-empty-file floor is kept as a
 * secondary guard. On any failure the interactive path fails loud with
 * actionable remediation rather than reporting success on a template
 * that would still leave spawned Claude prompting.
 *
 * <p><b>UC-19 part E — agent-teams + tmux backend.</b> At the end of
 * {@link #run} (covering both the interactive and
 * {@code --claude-config-source} paths) the captured
 * {@code settings.json} is read-modify-written to ensure top-level
 * {@code teammateMode: "tmux"} and {@code env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS: "1"},
 * preserving any keys already present (theme, hooks, …). The enable flag
 * is required (agent teams are off by default); {@code teammateMode} is
 * explicit belt-and-suspenders.
 */
public final class ClaudePreInitStep {

    private static final String SANDBOX_IMAGE = EnsureSandboxImage.IMAGE_TAG;

    /** Shared, thread-safe — used for the AC6 value check and the part-E settings.json merge. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessRunner runner;
    private final ConsoleIO io;

    public ClaudePreInitStep(ProcessRunner runner, ConsoleIO io) {
        this.runner = runner;
        this.io = io;
    }

    /**
     * Run the Claude pre-init step.
     *
     * @param sourceDirFlag value of {@code --claude-config-source} or
     *     {@code null} for interactive.
     * @param templateDir destination
     *     ({@code <templates-dir>/claude-config/}). Created if missing.
     * @param ownership pre-resolved owner/group or {@code null}.
     */
    public void run(Path sourceDirFlag, Path templateDir, Ownership ownership)
            throws IOException, InterruptedException {

        // Always make the parent + the templateDir itself exist with the
        // documented 0750 / ai-sandbox-server attributes; even if the
        // interactive scratch winds up empty, the bind-mount target
        // needs to exist for the compose RO mount to attach.
        Files.createDirectories(templateDir);
        Files.setPosixFilePermissions(templateDir, PosixFilePermissions.fromString("rwxr-x---"));

        if (sourceDirFlag != null) {
            // UC-19 AC5 — --claude-config-source is the UNATTENDED capture path
            // (Ansible / CI), so a silently-bad seed is the worst case: no human
            // is watching to catch the resulting first-run prompt. Value-check
            // the supplied tree with the SAME requirements as the interactive
            // capture and fail loud BEFORE seeding, so a malformed source never
            // produces a prompting session.
            if (!templateLooksOnboarded(sourceDirFlag)) {
                throw new IOException("--claude-config-source " + sourceDirFlag
                        + " is not a usable, onboarded Claude config (see the check(s) above):"
                        + " spawned sessions would still hit Claude's first-run wizard. Supply a"
                        + " ~/.claude/-shaped tree captured from a completed `claude` login"
                        + " (.claude.json with hasCompletedOnboarding=true + oauthAccount, plus a"
                        + " non-empty .credentials.json), or pass --no-claude-preinit to"
                        + " intentionally seed no Claude state.");
            }
            copyTreePreservingModes(sourceDirFlag, templateDir);
        } else {
            interactivePreInit(templateDir);
        }

        // UC-19 part E — ensure the agent-teams enable flag + tmux backend in
        // the template's settings.json. Runs for BOTH the interactive and the
        // --claude-config-source paths so every captured template enables teams.
        ensureAgentTeamSettings(templateDir);

        Files.setPosixFilePermissions(templateDir, PosixFilePermissions.fromString("rwxr-x---"));
        if (ownership != null) {
            ownership.chownTree(templateDir);
        }
    }

    // ── interactive path ────────────────────────────────────────────

    /**
     * Runtime uid:gid the {@code ai-context:latest} image runs as, set
     * by {@code adduser -D -h /home/claude claude} in
     * {@code SandboxDockerfile}. Alpine's {@code adduser} assigns the
     * first free uid starting at 1000, which lands here as 1000:1000
     * on the stock {@code alpine:latest} base. If the Dockerfile ever
     * pins these to other values (or switches base image), update
     * this constant pair to match.
     */
    static final int CONTAINER_UID = 1000;

    static final int CONTAINER_GID = 1000;

    private void interactivePreInit(Path templateDir) throws IOException, InterruptedException {
        Path scratch = Files.createTempDirectory("aisandbox-claude-preinit-");
        // Why this is NOT `--user 0`: Claude Code refuses to run with
        // `--dangerously-skip-permissions` when euid is 0, by design
        // (running an autonomous agent as root inside a privileged
        // container is a foot-gun the upstream tool explicitly
        // rejects). The wizard install flow is always under sudo per
        // the README, so the previous design (chmod 777 + --user 0)
        // collided head-on with that check and the bind-mount came
        // back empty.
        //
        // The fix is to run the helper container as its image-default
        // `claude` user — the same uid that `entrypoint.sh` and
        // `setup.sh`'s outcome-A flow already use successfully.
        //
        // To make the bind-mount writable by that uid we chown the
        // scratch to it. createTempDirectory leaves the dir at 0700
        // (owner-only) on Linux; we keep that mode after chown, which
        // is the security improvement over the old 0777: only the
        // container's claude user (and root, which reads anything)
        // can see the captured OAuth state during the brief window
        // the scratch exists.
        //
        // Fallback: when the wizard is NOT running as root (rare —
        // local dev only, since the documented install flow uses
        // sudo) the chown is refused with EPERM. We then widen perms
        // to 0777 so the container's claude user can still write.
        // This preserves the pre-fix security posture rather than
        // improving it; the install-time threat model is unchanged.
        Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwx------"));
        if (!tryChownToContainerUser(scratch)) {
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwxrwxrwx"));
        }

        try {
            io.println("");
            io.println("  step 4/4 — Claude pre-init");
            io.println("  Launching " + SANDBOX_IMAGE + " with `claude --dangerously-skip-permissions`.");
            io.println("  Complete the device-flow login when prompted, then type /exit to return.");
            io.println("");

            // UC-19 capture fix: prepend the same symlink entrypoint.sh:28
            // installs for live sessions. Claude writes completed-onboarding
            // + signed-in account to the SIBLING ~/.claude.json, which lives
            // OUTSIDE the bind-mounted ~/.claude/ — so without this symlink
            // those writes land in the container's ephemeral fs and are lost.
            // With it, ~/.claude.json -> ~/.claude/.claude.json redirects the
            // writes INTO the mounted scratch, so the suppressing state is
            // captured into the template. Hardcoded /home/claude (the image's
            // claude-user HOME, uid CONTAINER_UID) rather than $HOME, because
            // `docker run --user 1000:1000` does not reliably export HOME.
            List<String> argv = List.of(
                    "docker",
                    "run",
                    "--rm",
                    "-it",
                    "--user",
                    CONTAINER_UID + ":" + CONTAINER_GID,
                    "-v",
                    scratch.toAbsolutePath() + ":/home/claude/.claude",
                    "--entrypoint",
                    "sh",
                    SANDBOX_IMAGE,
                    "-c",
                    "ln -sf /home/claude/.claude/.claude.json /home/claude/.claude.json"
                            + " && claude --dangerously-skip-permissions");
            int rc = runner.runInheritIO(argv);
            // We intentionally do NOT exit non-zero on a non-zero
            // container exit here: operators often /exit Claude which
            // can surface as a non-zero rc. The heuristic below is the
            // authoritative success check.
            if (rc != 0) {
                io.println("  (container exited with " + rc + "; checking captured state...)");
            }

            if (!scratchIsUsable(scratch)) {
                throw new IOException("Claude pre-init did not produce a usable config at " + scratch
                        + "; pass --claude-config-source <path> with a workstation-built ~/.claude/ tree,"
                        + " or --no-claude-preinit to skip Claude seeding entirely.");
            }

            // Mirror the scratch into the template dir, preserving modes.
            copyTreePreservingModes(scratch, templateDir);
        } finally {
            // Best-effort cleanup. The scratch held real OAuth state
            // briefly; deleting it shrinks the at-rest window. Even if
            // delete fails (uncommon, but a stale lock file could hold
            // a descriptor open), the data also lives at templateDir
            // now, so the operator's audit trail is intact.
            deleteTreeQuietly(scratch);
        }
    }

    /**
     * Hand ownership of {@code scratch} to the container's runtime
     * uid:gid so the bind-mount is writable at 0700 (only the
     * container's claude user and root can read the OAuth state).
     * Uses the numeric {@code unix:uid} / {@code unix:gid} attribute
     * view rather than name-based lookup because the container's
     * {@code claude} user does not exist on the host. Returns
     * {@code true} on success; {@code false} when the JVM lacks the
     * privilege to chown (typical non-root wizard run), in which case
     * the caller widens perms instead.
     */
    private static boolean tryChownToContainerUser(Path scratch) {
        try {
            Files.setAttribute(scratch, "unix:uid", CONTAINER_UID);
            Files.setAttribute(scratch, "unix:gid", CONTAINER_GID);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * UC-19 AC6 success check for the INTERACTIVE capture: the original
     * UC06 floor (≥1 non-empty regular file other than {@code .gitkeep},
     * a guard against a wholly empty scratch) AND
     * {@link #templateLooksOnboarded(Path)}. Each failure is reported on
     * the operator-visible stream so the caller's {@code IOException} is
     * actionable (AC6 "fail loud").
     */
    private boolean scratchIsUsable(Path scratch) throws IOException {
        if (!hasNonEmptyRegularFile(scratch)) {
            io.println("  Claude pre-init check failed: captured config holds no non-empty files.");
            return false;
        }
        return templateLooksOnboarded(scratch);
    }

    /**
     * UC-19 — the shared value check used by BOTH the interactive capture
     * (against the scratch) AND the {@code --claude-config-source} path
     * (against the operator-supplied tree, AC5). Returns {@code true} only
     * when {@code dir} holds the state Claude reads to skip its first-run
     * wizard, not merely "some non-empty file":
     *
     * <ul>
     *   <li>{@code .claude.json} is present, non-empty, valid JSON with
     *       {@code hasCompletedOnboarding == true} (boolean) AND a present,
     *       non-empty {@code oauthAccount} object;</li>
     *   <li>{@code .credentials.json} is present and non-empty — the real
     *       login token. {@code oauthAccount} is only display metadata, so
     *       a template with the account but no credentials would still
     *       re-prompt for login.</li>
     * </ul>
     *
     * <p>Each failure is reported on the operator-visible stream so the
     * caller can throw an actionable {@code IOException} (AC6/AC5 "fail
     * loud"). For the interactive path the UC-19 symlink redirects Claude's
     * sibling-file writes into the mounted scratch, so {@code .claude.json}
     * lands at {@code scratch/.claude.json}; for the source path the
     * operator's tree is {@code ~/.claude/}-shaped, so the same relative
     * paths apply.
     */
    private boolean templateLooksOnboarded(Path dir) throws IOException {
        Path claudeJson = dir.resolve(".claude.json");
        if (!Files.isRegularFile(claudeJson) || Files.size(claudeJson) == 0) {
            io.println("  Claude pre-init check failed: ~/.claude.json missing or empty"
                    + " (completed-onboarding / signed-in account state not captured).");
            return false;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(claudeJson.toFile());
        } catch (IOException parse) {
            io.println("  Claude pre-init check failed: ~/.claude.json is not valid JSON: " + parse.getMessage());
            return false;
        }
        JsonNode completed = root.get("hasCompletedOnboarding");
        if (completed == null || !completed.isBoolean() || !completed.booleanValue()) {
            io.println("  Claude pre-init check failed: hasCompletedOnboarding is not true in ~/.claude.json"
                    + " (Claude would still show its first-run wizard).");
            return false;
        }
        JsonNode oauth = root.get("oauthAccount");
        if (oauth == null || !oauth.isObject() || oauth.isEmpty()) {
            io.println("  Claude pre-init check failed: oauthAccount missing or empty in ~/.claude.json"
                    + " (no signed-in account captured).");
            return false;
        }
        Path credentials = dir.resolve(".credentials.json");
        if (!Files.isRegularFile(credentials) || Files.size(credentials) == 0) {
            io.println("  Claude pre-init check failed: ~/.claude/.credentials.json missing or empty"
                    + " (login token not captured; spawned Claude would still prompt to sign in).");
            return false;
        }
        return true;
    }

    /** Original UC06 floor: ≥1 regular file other than {@code .gitkeep} with &gt;0 bytes total. */
    private static boolean hasNonEmptyRegularFile(Path scratch) throws IOException {
        final long[] bytes = {0L};
        final int[] files = {0};
        Files.walkFileTree(scratch, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (".gitkeep".equals(f.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isRegularFile()) {
                    files[0]++;
                    bytes[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files[0] >= 1 && bytes[0] > 0;
    }

    // ── UC-19 part E — agent-teams + tmux backend ───────────────────

    /**
     * Read-modify-write {@code <templateDir>/settings.json} so the seeded
     * {@code ~/.claude/settings.json} carries the two keys that turn on
     * Claude Code's tmux teammate backend, preserving every key already
     * present (theme, hooks, {@code skipDangerousModePermissionPrompt}, …):
     *
     * <ul>
     *   <li>top-level {@code teammateMode: "tmux"} — explicit backend pick;</li>
     *   <li>{@code env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS: "1"} — REQUIRED,
     *       since agent teams are off by default.</li>
     * </ul>
     *
     * <p>Creates the file when absent. A non-object or unparseable existing
     * file is replaced with a fresh object (it could not have held usable
     * Claude settings anyway).
     *
     * <p><b>S2 (rtk merge) — flagged for QA.</b> {@code entrypoint.sh:61}
     * runs {@code rtk init -g --auto-patch} against this same
     * {@code settings.json} after the seed. The enable flag's survival
     * depends on rtk doing a key-preserving read-modify-write (the existing
     * theme / hooks persistence implies it does). If a smoke test shows rtk
     * clobbers the nested {@code env} object, the fallback is a container
     * {@code export CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1} just before the
     * {@code tmux new-session … claude} line in {@code entrypoint.sh};
     * {@code teammateMode} stays here regardless.
     */
    private void ensureAgentTeamSettings(Path templateDir) throws IOException {
        Path settings = templateDir.resolve("settings.json");
        ObjectNode root;
        if (Files.isRegularFile(settings) && Files.size(settings) > 0) {
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(settings.toFile());
            } catch (IOException parse) {
                io.println("  WARNING: existing settings.json is not valid JSON (" + parse.getMessage()
                        + "); replacing with a fresh object carrying the agent-teams keys.");
                parsed = null;
            }
            root = (parsed != null && parsed.isObject()) ? (ObjectNode) parsed : MAPPER.createObjectNode();
        } else {
            root = MAPPER.createObjectNode();
        }

        root.put("teammateMode", "tmux");

        JsonNode envNode = root.get("env");
        ObjectNode env = (envNode != null && envNode.isObject()) ? (ObjectNode) envNode : MAPPER.createObjectNode();
        env.put("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "1");
        root.set("env", env);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(settings.toFile(), root);
    }

    // ── recursive copy preserving modes ─────────────────────────────

    /**
     * Walk {@code source} and recreate every entry under {@code dest},
     * preserving file modes via {@link StandardCopyOption#COPY_ATTRIBUTES}.
     * Existing files in {@code dest} are replaced. The walk skips the
     * source root itself; only its children are copied (so a
     * {@code source=~/.claude} run produces {@code dest/CLAUDE.md},
     * {@code dest/settings.json}, etc. — not {@code dest/.claude/…}).
     */
    static void copyTreePreservingModes(Path source, Path dest) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("source is not a directory: " + source);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(dir));
                if (!Files.exists(target)) {
                    Files.createDirectories(target);
                    // Mirror the source dir's permissions on POSIX.
                    var perms = Files.getPosixFilePermissions(dir);
                    Files.setPosixFilePermissions(target, perms);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort; the at-rest data has already been copied to
            // the template dir, and the temp dir was created with
            // mkdtemp under /tmp so OS reaping eventually clears it.
        }
    }
}
