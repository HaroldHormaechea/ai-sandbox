package com.aisandbox.server.cli;

import com.aisandbox.server.cli.secrets.ClaudePreInitStep;
import com.aisandbox.server.cli.secrets.ConsoleIO;
import com.aisandbox.server.cli.secrets.EncryptedKeyDecryptor;
import com.aisandbox.server.cli.secrets.EnsureSandboxImage;
import com.aisandbox.server.cli.secrets.GhTokenStep;
import com.aisandbox.server.cli.secrets.GitIdentityStep;
import com.aisandbox.server.cli.secrets.ProcessRunner;
import com.aisandbox.server.cli.secrets.SshKeyStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * UC06 — {@code aisandboxctl secrets seed}.
 *
 * <p>One install-time command that captures the four pieces of state
 * the {@code claude-sandbox} container needs to operate:
 *
 * <ol>
 *   <li>SSH private key → {@code /etc/ai-sandbox-server/secrets/git-key}</li>
 *   <li>git author identity → {@code /etc/ai-sandbox-server/secrets/gitconfig}</li>
 *   <li>gh PAT → {@code /etc/ai-sandbox-server/secrets/gh-token}</li>
 *   <li>Claude pre-init config → {@code /etc/ai-sandbox-server/templates/claude-config/}</li>
 * </ol>
 *
 * <p>The first three were previously dropped by hand under UC05's
 * "manual secrets drop" footnote; UC06 wraps them in a re-runnable
 * wizard and adds the Claude template so newly spawned sessions
 * inherit a logged-in {@code ~/.claude/} via
 * {@code docker-compose.yml}'s new RO bind-mount +
 * {@code entrypoint.sh}'s {@code cp -a} seeding block.
 *
 * <p>Mirrors {@link PkiInitCommand}'s shape: outer container
 * {@code @Command} holder + inner {@link Seed} {@code Callable<Integer>}.
 * Root check, refuse-without-{@code --force} conflict listing, and
 * file-mode + ownership management follow the same patterns; the
 * extracted {@link Ownership} record is the shared seam.
 *
 * <p>UC06 § AC25 — this command is deliberately exempt from
 * {@code profile-java-server-architecture}'s Controller/Facade/Service
 * /Repository layering: it's a thin install-time CLI (root check, file
 * I/O, container shell-outs), not server runtime. Documented in
 * {@code PROJECT_BRIEF.md} {@code ## Profiles}.
 */
@Command(name = "secrets", description = "Secret seeding (UC06).", subcommands = SecretsSeedCommand.Seed.class)
public class SecretsSeedCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(
            name = "seed",
            description = {
                "Walk-through and unassisted secrets onboarding for a fresh install.",
                "",
                "Captures the four pieces of state the claude-sandbox container needs:",
                "  • SSH key      → /etc/ai-sandbox-server/secrets/git-key",
                "  • Git identity → /etc/ai-sandbox-server/secrets/gitconfig",
                "  • gh token     → /etc/ai-sandbox-server/secrets/gh-token   (skip via --no-gh)",
                "  • Claude conf  → /etc/ai-sandbox-server/templates/claude-config/  (skip via --no-claude-preinit)",
                "",
                "Steps run in fixed order. Each step has flag-driven non-interactive form;",
                "missing flags + a TTY → interactive prompt; missing flags + no TTY → fail",
                "fast with the list of missing flags. Re-run policy mirrors `pki init`:",
                "refuse without --force; with --force, every conflict is overwritten.",
                "",
                "Requires root (uid 0)."
            })
    public static class Seed implements Callable<Integer> {

        // ── output locations (overrideable for tests + CI smoke) ──

        @Option(names = "--secrets-dir", description = "Operator-managed secrets dir (default ${DEFAULT-VALUE}).")
        Path secretsDir = Path.of("/etc/ai-sandbox-server/secrets");

        @Option(
                names = "--templates-dir",
                description = "Operator-managed templates dir (default ${DEFAULT-VALUE}).")
        Path templatesDir = Path.of("/etc/ai-sandbox-server/templates");

        @Option(
                names = "--install-dir",
                description = "Install root holding the bundled host/ compose context (default ${DEFAULT-VALUE}).")
        Path installDir = Path.of("/opt/ai-sandbox-server");

        @Option(
                names = "--user",
                description = "System user to own written files (default ${DEFAULT-VALUE}).")
        String systemUserName = "ai-sandbox-server";

        // ── step (a) — SSH key ────────────────────────────────────

        @Option(
                names = "--git-key",
                description = "Path to the private SSH key to copy. Interactive prompt when omitted.")
        Path gitKey;

        @Option(
                names = "--git-key-passphrase-file",
                description = "Path to a file holding the SSH key passphrase. Trailing newline trimmed.")
        Path gitKeyPassphraseFile;

        @Option(
                names = "--git-key-passphrase-env",
                description = "Name of an environment variable holding the SSH key passphrase.")
        String gitKeyPassphraseEnv;

        // ── step (b) — git identity ───────────────────────────────

        @Option(names = "--git-name", description = "Git author name. Interactive prompt when omitted.")
        String gitName;

        @Option(names = "--git-email", description = "Git author email. Interactive prompt when omitted.")
        String gitEmail;

        // ── step (c) — gh token ───────────────────────────────────

        @Option(
                names = "--gh-token-file",
                description = "Path to a pre-generated gh PAT to byte-copy. Mutually exclusive with --no-gh.")
        Path ghTokenFile;

        @Option(names = "--no-gh", description = "Skip the gh authentication step entirely.")
        boolean noGh;

        // ── step (d) — Claude pre-init ────────────────────────────

        @Option(
                names = "--claude-config-source",
                description = "Path to a ~/.claude/-shaped dir to seed the template from."
                        + " Mutually exclusive with --no-claude-preinit.")
        Path claudeConfigSource;

        @Option(
                names = "--no-claude-preinit",
                description = "Skip the Claude pre-init step entirely.")
        boolean noClaudePreInit;

        // ── policy ────────────────────────────────────────────────

        @Option(
                names = "--force",
                description = "Overwrite existing git-key, gitconfig, gh-token, or claude-config/ contents.")
        boolean force;

        // ── test seams (package-private, set via setters below) ───

        private BooleanSupplier rootCheck = Seed::isRoot;
        private ProcessRunner processRunner = new ProcessRunner.Default();
        private ConsoleIO consoleIO = new ConsoleIO.Default();
        private Path sshDir = resolveOperatorSshDir();

        /** Test seam — override the root-check probe. */
        void setRootCheck(BooleanSupplier rootCheck) {
            this.rootCheck = rootCheck;
        }

        /** Test seam — inject a fake {@link ProcessRunner}. */
        void setProcessRunner(ProcessRunner processRunner) {
            this.processRunner = processRunner;
        }

        /** Test seam — inject a fake {@link ConsoleIO}. */
        void setConsoleIO(ConsoleIO consoleIO) {
            this.consoleIO = consoleIO;
        }

        /** Test seam — point {@link SshKeyStep}'s candidate-enumeration at a different folder. */
        void setSshDir(Path sshDir) {
            this.sshDir = sshDir;
        }

        @Override
        public Integer call() throws Exception {
            boolean posix = isPosix();

            // AC1 — root check.
            if (posix && !rootCheck.getAsBoolean()) {
                System.err.println("aisandboxctl secrets seed: must run as root (use sudo).");
                return 2;
            }

            // AC15 — explicit no-`/opt`-writes guard. The wizard never
            // writes under the install dir; everything lands under
            // --secrets-dir / --templates-dir. We refuse to start if an
            // operator points either dir under /opt/ai-sandbox-server/,
            // which would either fail at runtime or silently break the
            // read-only-install contract.
            Path optRoot = Path.of("/opt/ai-sandbox-server");
            if (secretsDir.toAbsolutePath().startsWith(optRoot)
                    || templatesDir.toAbsolutePath().startsWith(optRoot)) {
                System.err.println("aisandboxctl secrets seed: --secrets-dir / --templates-dir cannot live under "
                        + optRoot + " (install dir is read-only).");
                return 2;
            }

            // Resolve the four output targets up front so the conflict
            // check + post-run summary see the same set.
            Path gitKeyOut = secretsDir.resolve("git-key");
            Path gitconfigOut = secretsDir.resolve("gitconfig");
            Path ghTokenOut = secretsDir.resolve("gh-token");
            Path claudeOut = templatesDir.resolve("claude-config");

            // AC12 — no-TTY + missing flag → fail fast with the full
            // list. The check runs BEFORE conflict detection so a
            // missing flag never silently triggers a refuse-to-overwrite
            // exit (which would be a misleading failure mode).
            if (!consoleIO.hasTty()) {
                List<String> missing = new ArrayList<>();
                if (gitKey == null) {
                    missing.add("--git-key");
                }
                if (gitName == null) {
                    missing.add("--git-name");
                }
                if (gitEmail == null) {
                    missing.add("--git-email");
                }
                if (ghTokenFile == null && !noGh) {
                    missing.add("--gh-token-file (or --no-gh)");
                }
                if (claudeConfigSource == null && !noClaudePreInit) {
                    missing.add("--claude-config-source (or --no-claude-preinit)");
                }
                if (!missing.isEmpty()) {
                    System.err.println(
                            "aisandboxctl secrets seed: stdin is not a TTY and the following flags are missing:");
                    for (String m : missing) {
                        System.err.println("  " + m);
                    }
                    return 2;
                }
            }

            // AC13 — conflict detection covers ALL four output paths
            // regardless of --no-gh / --no-claude-preinit (operators who
            // re-seed with opt-outs still need a clear refuse + list).
            List<Path> conflicts = new ArrayList<>();
            for (Path p : List.of(gitKeyOut, gitconfigOut, ghTokenOut)) {
                if (Files.exists(p)) {
                    conflicts.add(p);
                }
            }
            if (Files.exists(claudeOut) && !isEmptyDir(claudeOut)) {
                conflicts.add(claudeOut);
            }
            if (!conflicts.isEmpty() && !force) {
                System.err.println("aisandboxctl secrets seed: refusing to overwrite. Use --force to override.");
                for (Path c : conflicts) {
                    System.err.println("conflict: " + c);
                }
                return 2;
            }
            if (!conflicts.isEmpty()) {
                System.err.println("aisandboxctl secrets seed: --force given; overwriting:");
                for (Path c : conflicts) {
                    System.err.println("  overwrite: " + c);
                }
            }

            // Provision parent dirs (idempotent — pki init already made
            // /etc/ai-sandbox-server/secrets/ with 0700; templatesDir is
            // new in UC06 so we make it 0750 here.)
            ensureDir(secretsDir, "rwx------", posix);
            ensureDir(templatesDir, "rwxr-x---", posix);

            Ownership ownership = posix ? Ownership.resolve(systemUserName, "secrets seed") : null;

            // AC16 — guarantee ai-context:latest before the docker-using
            // steps. Skip when neither interactive step is in play (both
            // opt-outs + flag-driven cover paths needing no docker).
            boolean needsDocker = (!noGh && ghTokenFile == null) || (!noClaudePreInit && claudeConfigSource == null);
            if (needsDocker) {
                new EnsureSandboxImage(processRunner, consoleIO).run(installDir);
            }

            EncryptedKeyDecryptor decryptor = new EncryptedKeyDecryptor(processRunner);

            // AC2/AC3 — step (a).
            new SshKeyStep(processRunner, consoleIO, decryptor, sshDir)
                    .run(gitKey, gitKeyPassphraseFile, gitKeyPassphraseEnv, gitKeyOut, ownership);

            // AC4 — step (b).
            new GitIdentityStep(processRunner, consoleIO).run(gitName, gitEmail, gitconfigOut, ownership);

            // AC5 — step (c). Opt-out path skips writing entirely.
            if (!noGh) {
                new GhTokenStep(processRunner, consoleIO).run(ghTokenFile, ghTokenOut, secretsDir, ownership);
            }

            // AC6 — step (d). Opt-out path still creates the empty
            // template dir so docker-compose.yml's RO mount has a
            // valid attach target (AC7's "Empty template → cp is a
            // no-op" path).
            ClaudePreInitStep claudeStep = new ClaudePreInitStep(processRunner, consoleIO);
            if (noClaudePreInit) {
                ensureDir(claudeOut, "rwxr-x---", posix);
                if (ownership != null) {
                    ownership.chown(claudeOut);
                }
            } else {
                claudeStep.run(claudeConfigSource, claudeOut, ownership);
            }

            // AC22 — audit summary on stdout (stderr was reserved for
            // prompts / warnings / conflict listing).
            System.out.println();
            System.out.println("aisandboxctl secrets seed: complete.");
            System.out.println("  user      : " + systemUserName);
            printFileSummary(gitKeyOut, posix);
            printFileSummary(gitconfigOut, posix);
            if (!noGh) {
                printFileSummary(ghTokenOut, posix);
            } else {
                System.out.println("  gh-token  : (skipped via --no-gh)");
            }
            if (!noClaudePreInit) {
                printDirSummary(claudeOut, posix);
            } else {
                System.out.println("  claude    : (skipped via --no-claude-preinit, empty template dir created)");
            }
            return 0;
        }

        // ── helpers ───────────────────────────────────────────────

        /**
         * Effective SSH dir for candidate enumeration. When the wizard
         * runs under {@code sudo}, {@code $HOME} resolves to
         * {@code /root}, which is rarely what the operator wants; if
         * {@code $SUDO_USER} is set, return that user's
         * {@code ~/.ssh}. Operators with a non-standard home location
         * can always type the full path at the interactive prompt.
         */
        static Path resolveOperatorSshDir() {
            String sudoUser = System.getenv("SUDO_USER");
            if (sudoUser != null && !sudoUser.isEmpty() && !"root".equals(sudoUser)) {
                Path candidate = Path.of("/home", sudoUser, ".ssh");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
            String home = System.getProperty("user.home");
            return Path.of(home == null ? "/root" : home, ".ssh");
        }

        private static boolean isPosix() {
            return java.nio.file.FileSystems.getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix");
        }

        private static boolean isRoot() {
            try {
                Process p =
                        new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
                byte[] out = p.getInputStream().readAllBytes();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return "0".equals(new String(out).trim());
            } catch (IOException ioe) {
                return false;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static void ensureDir(Path p, String posixMode, boolean posix) throws IOException {
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
            if (posix) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(posixMode));
            }
        }

        private static boolean isEmptyDir(Path p) {
            if (!Files.isDirectory(p)) {
                return false;
            }
            try (var s = Files.list(p)) {
                return s.findAny().isEmpty();
            } catch (IOException ioe) {
                return false;
            }
        }

        private static void printFileSummary(Path p, boolean posix) throws IOException {
            String mode = posix && Files.exists(p)
                    ? PosixFilePermissions.toString(Files.getPosixFilePermissions(p))
                    : "(unknown)";
            System.out.println("  " + label(p) + ": " + p + "  (mode " + mode + ")");
        }

        private static void printDirSummary(Path p, boolean posix) throws IOException {
            long count = 0;
            try (var s = Files.walk(p)) {
                count = s.filter(Files::isRegularFile).count();
            }
            String mode = posix && Files.exists(p)
                    ? PosixFilePermissions.toString(Files.getPosixFilePermissions(p))
                    : "(unknown)";
            System.out.println("  " + label(p) + ": " + p + "/  (mode " + mode + ", " + count + " files)");
        }

        private static String label(Path p) {
            String name = p.getFileName().toString();
            return switch (name) {
                case "git-key" -> "git-key  ";
                case "gitconfig" -> "gitconfig";
                case "gh-token" -> "gh-token ";
                case "claude-config" -> "claude   ";
                default -> name;
            };
        }
    }
}
