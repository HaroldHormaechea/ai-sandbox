package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.Ownership;
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
 * UC06 § AC6 — step (d) of {@code aisandboxctl secrets seed}: capture
 * a {@code ~/.claude/}-shaped template at
 * {@code <templates-dir>/claude-config/} that
 * {@code docker-compose.yml}'s new RO bind-mount seeds into every
 * spawned session's {@code ~/.claude/} via {@code entrypoint.sh}.
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@code --no-claude-preinit} — orchestrator skips this step;
 *       the bind-mount becomes a no-op (target dir empty), and
 *       sessions behave as they did before UC06.</li>
 *   <li>{@code --claude-config-source PATH} — recursive copy of an
 *       operator-supplied {@code ~/.claude/}-shaped directory
 *       preserving file modes. Lets workstation-built templates ship
 *       to outcome-B servers (where the CLI doesn't support headless
 *       OAuth) without operator-side fuss.</li>
 *   <li>interactive — spawns an ephemeral {@code ai-context:latest}
 *       container with a scratch tempdir bound at
 *       {@code /home/claude/.claude}, runs
 *       {@code claude --dangerously-skip-permissions} with inheritIO
 *       so the device-flow URL / code surface to the operator. When
 *       the operator finishes (or types {@code /exit}), the heuristic
 *       below decides if the scratch is usable.</li>
 * </ul>
 *
 * <p><b>AC6 success-detection heuristic.</b> Spike not run in dev
 * sandbox (docker unavailable on 2026-05-18); heuristic mirrors
 * {@code setup.sh:claude_config_set_up} lines 24-32 — success ↔
 * scratch dir contains ≥1 regular file other than {@code .gitkeep}
 * AND the non-{@code .gitkeep} byte total is non-zero. Correct under
 * both AC6-A (CLI supports headless OAuth → operator's auth lands as
 * files we detect) and AC6-B (CLI doesn't support headless → empty
 * scratch → fall through to AC6 remediation). When a future Claude
 * CLI version exposes a stable single-file marker, replace the
 * heuristic with the pin.
 */
public final class ClaudePreInitStep {

    private static final String SANDBOX_IMAGE = EnsureSandboxImage.IMAGE_TAG;

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
            copyTreePreservingModes(sourceDirFlag, templateDir);
        } else {
            interactivePreInit(templateDir);
        }

        Files.setPosixFilePermissions(templateDir, PosixFilePermissions.fromString("rwxr-x---"));
        if (ownership != null) {
            ownership.chownTree(templateDir);
        }
    }

    // ── interactive path ────────────────────────────────────────────

    private void interactivePreInit(Path templateDir) throws IOException, InterruptedException {
        Path scratch = Files.createTempDirectory("aisandbox-claude-preinit-");
        // Mode 0777 so the container user (default `claude`, uid 1000)
        // can write into the bind-mount even when the wizard runs as
        // root. We use --user 0 on the container side too, but giving
        // the host dir maximum freedom keeps the bind-mount permissive
        // regardless of which user Claude CLI runs as internally.
        Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwxrwxrwx"));

        try {
            io.println("");
            io.println("  step 4/4 — Claude pre-init");
            io.println("  Launching " + SANDBOX_IMAGE + " with `claude --dangerously-skip-permissions`.");
            io.println("  Complete the device-flow login when prompted, then type /exit to return.");
            io.println("");

            List<String> argv = List.of(
                    "docker",
                    "run",
                    "--rm",
                    "-it",
                    "--user",
                    "0",
                    "-v",
                    scratch.toAbsolutePath() + ":/home/claude/.claude",
                    "--entrypoint",
                    "sh",
                    SANDBOX_IMAGE,
                    "-c",
                    "claude --dangerously-skip-permissions");
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
     * AC6 success-detection: scratch contains ≥1 regular file other
     * than {@code .gitkeep} AND those files contribute >0 bytes total.
     */
    private static boolean scratchIsUsable(Path scratch) throws IOException {
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
