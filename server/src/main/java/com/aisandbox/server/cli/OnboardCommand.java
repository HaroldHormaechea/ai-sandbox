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
import java.nio.file.FileSystems;
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
 * UC-17 — {@code aisandboxctl onboard}.
 *
 * <p>The re-runnable, per-component onboarding wizard. Where
 * {@code pki init} provisions the PKI + directory tree and
 * {@code secrets seed} captures the four pieces of container pre-flight
 * state in one all-or-nothing pass, {@code onboard} composes both behind
 * a single command with a <b>per-component presence check</b>: each
 * already-present artifact (PKI cert+key+{@code config.yaml},
 * {@code secrets/git-key}, {@code secrets/gitconfig},
 * {@code secrets/gh-token}, the {@code templates/claude-config/}
 * template) is left untouched unless {@code --force}; only the missing
 * pieces are gathered.
 *
 * <p>It is the command the {@code .deb} {@code postinst} drives
 * (non-interactively, via debconf-captured answers) and the command an
 * operator re-runs by hand later to refresh or override config. The four
 * secret steps and the PKI step are <b>reused verbatim</b> — this class
 * orchestrates {@link PkiInitCommand.Init}, {@link SshKeyStep},
 * {@link GitIdentityStep}, {@link GhTokenStep}, {@link ClaudePreInitStep}
 * and {@link EnsureSandboxImage}; it does not re-implement any of them.
 *
 * <p><b>Non-interactive deferral (AC1).</b> When stdin is not a TTY and a
 * still-missing component's required flag was not supplied, that
 * component is <i>deferred</i> with a clear "re-run with …" instruction
 * rather than hanging or failing the package install. The interactive
 * Claude OAuth step in particular is TTY-only ({@code docker run -it}),
 * so under no-TTY it auto-skips (equivalent to {@code --no-claude-preinit})
 * with a "run interactively later" note (AC8).
 *
 * <p><b>Lazy image build.</b> {@link EnsureSandboxImage} only runs when an
 * interactive Docker-using step (web {@code gh} login or Claude pre-init)
 * is actually going to run this invocation — never during a headless
 * dpkg configure. {@code --no-image-build} hard-disables the build and
 * fails fast if a selected step would have needed it.
 *
 * <p>UC-17 — like {@link SecretsSeedCommand} this command is exempt from
 * {@code profile-java-server-architecture}'s Controller/Facade/Service/
 * Repository layering: it is a thin install-time CLI (root check, file
 * I/O, container shell-outs), not server runtime. Documented in
 * {@code PROJECT_BRIEF.md} {@code ## Profiles}.
 */
@Command(
        name = "onboard",
        mixinStandardHelpOptions = true,
        description = {
            "Out-of-box onboarding wizard — provisions everything a fresh install needs",
            "so spawned sessions work out of the box, gathering only what is still missing.",
            "",
            "Per-component check (each left untouched unless --force):",
            "  • PKI         → server cert + key + config.yaml         (via `pki init`)",
            "  • SSH key     → /etc/ai-sandbox-server/secrets/git-key",
            "  • Git identity→ /etc/ai-sandbox-server/secrets/gitconfig",
            "  • gh token    → /etc/ai-sandbox-server/secrets/gh-token  (skip via --no-gh)",
            "  • Claude conf → /etc/ai-sandbox-server/templates/claude-config/ (--no-claude-preinit)",
            "",
            "Re-runnable. Non-interactive + a missing component's flag absent → that",
            "component is deferred with a clear re-run instruction (never hangs / fails).",
            "Claude pre-init OAuth is interactive-only; under no-TTY it auto-skips.",
            "",
            "Requires root (uid 0)."
        })
public class OnboardCommand implements Callable<Integer> {

    // ── output / layout locations (overrideable for tests + CI) ──

    @Option(names = "--secrets-dir", description = "Operator-managed secrets dir (default ${DEFAULT-VALUE}).")
    Path secretsDir = Path.of("/etc/ai-sandbox-server/secrets");

    @Option(names = "--templates-dir", description = "Operator-managed templates dir (default ${DEFAULT-VALUE}).")
    Path templatesDir = Path.of("/etc/ai-sandbox-server/templates");

    @Option(
            names = "--install-dir",
            description = "Install root holding the bundled host/ compose context (default ${DEFAULT-VALUE}).")
    Path installDir = Path.of("/opt/ai-sandbox-server");

    @Option(names = "--pki-dir", description = "PKI directory (default ${DEFAULT-VALUE}).")
    Path pkiDir = Path.of("/etc/ai-sandbox-server/pki");

    @Option(names = "--clients-dir", description = "Allowlist directory (default ${DEFAULT-VALUE}).")
    Path clientsDir = Path.of("/etc/ai-sandbox-server/clients");

    @Option(names = "--enrollment-dir", description = "Enrollment-token directory (default ${DEFAULT-VALUE}).")
    Path enrollmentDir = Path.of("/var/lib/ai-sandbox-server/enrollment");

    @Option(names = "--sessions-dir", description = "Per-session host-state root (default ${DEFAULT-VALUE}).")
    Path sessionsDir = Path.of("/var/lib/ai-sandbox-server/sessions");

    @Option(names = "--log-dir", description = "Audit-log directory (default ${DEFAULT-VALUE}).")
    Path logDir = Path.of("/var/log/ai-sandbox-server");

    @Option(names = "--config", description = "Config file path (default ${DEFAULT-VALUE}).")
    Path configFile = Path.of("/etc/ai-sandbox-server/config.yaml");

    @Option(names = "--user", description = "System user to own written files (default ${DEFAULT-VALUE}).")
    String systemUserName = "ai-sandbox-server";

    // ── PKI passthrough (consumed only when the PKI component is gathered) ──

    @Option(names = "--cn", description = "Server cert Common Name (default ${DEFAULT-VALUE}).")
    String cn = "ai-sandbox-server";

    @Option(
            names = "--san",
            arity = "0..*",
            split = ",",
            description = "SAN entries: DNS:<host> or IP:<addr>. Repeatable and comma-splittable.")
    List<String> sanEntries = new ArrayList<>();

    @Option(names = "--hostname", description = "Convenience for --san DNS:<value>.")
    String hostnameOption;

    @Option(
            names = "--no-auto-hostname",
            description = "Disable auto-derivation of DNS:<getLocalHost().getHostName()>.")
    boolean noAutoHostname;

    // ── SSH key ──

    @Option(names = "--git-key", description = "Path to the private SSH key to copy. Interactive prompt when omitted.")
    Path gitKey;

    @Option(
            names = "--git-key-passphrase-file",
            description = "Path to a file holding the SSH key passphrase. Trailing newline trimmed.")
    Path gitKeyPassphraseFile;

    @Option(
            names = "--git-key-passphrase-env",
            description = "Name of an environment variable holding the SSH key passphrase.")
    String gitKeyPassphraseEnv;

    // ── git identity ──

    @Option(names = "--git-name", description = "Git author name. Interactive prompt when omitted.")
    String gitName;

    @Option(names = "--git-email", description = "Git author email. Interactive prompt when omitted.")
    String gitEmail;

    // ── gh token ──

    @Option(
            names = "--gh-token-file",
            description = "Path to a pre-generated gh PAT to byte-copy. Mutually exclusive with --no-gh.")
    Path ghTokenFile;

    @Option(names = "--no-gh", description = "Skip the gh authentication step entirely.")
    boolean noGh;

    // ── Claude pre-init ──

    @Option(
            names = "--claude-config-source",
            description = "Path to a ~/.claude/-shaped dir to seed the template from."
                    + " Mutually exclusive with --no-claude-preinit.")
    Path claudeConfigSource;

    @Option(names = "--no-claude-preinit", description = "Skip the Claude pre-init step entirely.")
    boolean noClaudePreInit;

    // ── policy ──

    @Option(
            names = "--no-image-build",
            description = "Never build " + EnsureSandboxImage.IMAGE_TAG + "; fail fast if a selected step needs it.")
    boolean noImageBuild;

    @Option(
            names = "--force",
            description = "Re-gather already-present components (PKI, git-key, gitconfig, gh-token, claude template).")
    boolean force;

    // ── test seams (package-private, set via setters below) ──

    private BooleanSupplier rootCheck = OnboardCommand::isRoot;
    private ProcessRunner processRunner = new ProcessRunner.Default();
    private ConsoleIO consoleIO = new ConsoleIO.Default();
    private Path sshDir = resolveOperatorSshDir();
    private SystemUserAdmin systemUserAdmin;

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

    /** Test seam — substitute a fake {@link SystemUserAdmin} (propagated to {@code pki init}). */
    void setSystemUserAdmin(SystemUserAdmin admin) {
        this.systemUserAdmin = admin;
    }

    @Override
    public Integer call() throws Exception {
        boolean posix = isPosix();

        // Root check — useradd (via pki init) + chowns are root-only.
        if (posix && !rootCheck.getAsBoolean()) {
            System.err.println("aisandboxctl onboard: must run as root (use sudo).");
            return 2;
        }

        // Refuse to point the writable dirs under the read-only install dir
        // (parity with `secrets seed` AC15).
        Path optRoot = Path.of("/opt/ai-sandbox-server");
        if (secretsDir.toAbsolutePath().startsWith(optRoot)
                || templatesDir.toAbsolutePath().startsWith(optRoot)) {
            System.err.println("aisandboxctl onboard: --secrets-dir / --templates-dir cannot live under " + optRoot
                    + " (install dir is read-only).");
            return 2;
        }

        boolean hasTty = consoleIO.hasTty();

        // Output targets.
        Path gitKeyOut = secretsDir.resolve("git-key");
        Path gitconfigOut = secretsDir.resolve("gitconfig");
        Path ghTokenOut = secretsDir.resolve("gh-token");
        Path claudeOut = templatesDir.resolve("claude-config");
        Path crt = pkiDir.resolve("server.crt");
        Path key = pkiDir.resolve("server.key");

        List<String> done = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> deferred = new ArrayList<>();

        // ── PKI component ──────────────────────────────────────────────
        boolean pkiAll = Files.exists(crt) && Files.exists(key) && Files.exists(configFile);
        boolean pkiAny = Files.exists(crt) || Files.exists(key) || Files.exists(configFile);
        if (pkiAll && !force) {
            unchanged.add("pki (cert + key + config.yaml present)");
        } else {
            // A partial PKI state needs --force to overwrite the conflicts
            // `pki init` would otherwise refuse on; a clean miss does not.
            boolean pkiForce = force || pkiAny;
            System.err.println("aisandboxctl onboard: provisioning PKI + directory tree"
                    + (pkiForce ? " (repairing existing/partial state)" : "") + " via `pki init`.");
            int rc = runPkiInit(pkiForce);
            if (rc != 0) {
                System.err.println("aisandboxctl onboard: `pki init` failed (exit=" + rc + "); aborting.");
                return rc;
            }
            done.add("pki (cert + key + config.yaml)");
        }

        // Ensure the secrets + templates dirs exist with the documented modes.
        // pki init created secretsDir (0700); templatesDir is UC06-new (0750).
        ensureDir(secretsDir, "rwx------", posix);
        ensureDir(templatesDir, "rwxr-x---", posix);
        Ownership ownership = posix ? Ownership.resolve(systemUserName, "onboard") : null;
        if (ownership != null) {
            ownership.chown(secretsDir);
            ownership.chown(templatesDir);
        }

        // ── decide which secret components need gathering ──────────────
        boolean needGitKey;
        if (Files.exists(gitKeyOut) && !force) {
            unchanged.add("git-key (present)");
            needGitKey = false;
        } else {
            needGitKey = true;
        }

        boolean needGitId;
        if (Files.exists(gitconfigOut) && !force) {
            unchanged.add("gitconfig (present)");
            needGitId = false;
        } else {
            needGitId = true;
        }

        boolean needGh;
        if (noGh) {
            unchanged.add("gh-token (--no-gh)");
            needGh = false;
        } else if (Files.exists(ghTokenOut) && !force) {
            unchanged.add("gh-token (present)");
            needGh = false;
        } else {
            needGh = true;
        }

        boolean claudePresent = Files.isDirectory(claudeOut) && !isEmptyDir(claudeOut);
        boolean needClaude;
        if (noClaudePreInit) {
            unchanged.add("claude template (--no-claude-preinit)");
            needClaude = false;
        } else if (claudePresent && !force) {
            unchanged.add("claude template (present)");
            needClaude = false;
        } else {
            needClaude = true;
        }

        // ── per-component interactivity / deferral ─────────────────────
        boolean doGitKey = false;
        if (needGitKey) {
            if (gitKey != null || hasTty) {
                doGitKey = true;
            } else {
                deferred.add("SSH key — re-run: sudo aisandboxctl onboard --git-key <path>"
                        + " (add --git-key-passphrase-file/-env if the key is encrypted).");
            }
        }

        boolean doGitId = false;
        if (needGitId) {
            if ((gitName != null && gitEmail != null) || hasTty) {
                doGitId = true;
            } else {
                deferred.add("Git identity — re-run: sudo aisandboxctl onboard"
                        + " --git-name <name> --git-email <email>.");
            }
        }

        boolean doGh = false;
        if (needGh) {
            if (ghTokenFile != null || hasTty) {
                doGh = true;
            } else {
                deferred.add("gh token — re-run: sudo aisandboxctl onboard --gh-token-file <path>"
                        + " (or pass --no-gh to skip gh entirely).");
            }
        }

        boolean doClaude = false;
        if (needClaude) {
            if (claudeConfigSource != null) {
                doClaude = true;
            } else if (hasTty) {
                doClaude = true;
            } else {
                // AC8 — interactive OAuth is TTY-only; headless auto-skip.
                deferred.add("Claude pre-init — interactive OAuth needs a terminal; run later from a TTY:"
                        + " sudo aisandboxctl onboard   (or pass --claude-config-source <dir> for an"
                        + " unattended seed).");
            }
        }

        // ── lazy image build gate ──────────────────────────────────────
        // Only the interactive (no-flag, TTY) gh / Claude steps shell out to
        // `docker run`; the flag-driven byte/dir copies do not.
        boolean willInteractiveGh = doGh && ghTokenFile == null && hasTty;
        boolean willInteractiveClaude = doClaude && claudeConfigSource == null && hasTty;
        boolean needsDocker = willInteractiveGh || willInteractiveClaude;
        if (needsDocker && noImageBuild) {
            System.err.println("aisandboxctl onboard: an interactive step would need to build "
                    + EnsureSandboxImage.IMAGE_TAG + " but --no-image-build was given.");
            System.err.println("  Re-run without --no-image-build, or supply --gh-token-file / "
                    + "--claude-config-source, or pass --no-gh / --no-claude-preinit.");
            return 2;
        }
        if (needsDocker) {
            new EnsureSandboxImage(processRunner, consoleIO).run(installDir);
        }

        // ── run the selected steps ─────────────────────────────────────
        EncryptedKeyDecryptor decryptor = new EncryptedKeyDecryptor(processRunner);

        if (doGitKey) {
            new SshKeyStep(processRunner, consoleIO, decryptor, sshDir)
                    .run(gitKey, gitKeyPassphraseFile, gitKeyPassphraseEnv, gitKeyOut, ownership);
            done.add("git-key");
        }

        if (doGitId) {
            new GitIdentityStep(processRunner, consoleIO).run(gitName, gitEmail, gitconfigOut, ownership);
            done.add("gitconfig");
        }

        if (doGh) {
            new GhTokenStep(processRunner, consoleIO).run(ghTokenFile, ghTokenOut, secretsDir, ownership);
            done.add("gh-token");
        }

        // Claude: when gathered, run the step. Otherwise still guarantee an
        // empty template dir exists so docker-compose.yml's RO bind-mount has
        // a valid attach target (mirrors `secrets seed` AC6/AC7).
        if (doClaude) {
            new ClaudePreInitStep(processRunner, consoleIO).run(claudeConfigSource, claudeOut, ownership);
            done.add("claude template");
        } else {
            ensureDir(claudeOut, "rwxr-x---", posix);
            if (ownership != null) {
                ownership.chown(claudeOut);
            }
        }

        // ── summary (stdout; stderr was reserved for prompts / warnings) ──
        System.out.println();
        System.out.println("aisandboxctl onboard: complete.");
        System.out.println("  user      : " + systemUserName);
        if (!done.isEmpty()) {
            System.out.println("  configured: " + String.join(", ", done));
        }
        if (!unchanged.isEmpty()) {
            System.out.println("  unchanged : " + String.join(", ", unchanged));
        }
        if (!deferred.isEmpty()) {
            System.out.println();
            System.out.println("  deferred (run again to complete):");
            for (String d : deferred) {
                System.out.println("    - " + d);
            }
        }
        return 0;
    }

    // ── pki init reuse ─────────────────────────────────────────────────

    /**
     * Run {@link PkiInitCommand.Init} with this command's directory /
     * passthrough options. Fields are package-private and set directly
     * (no second picocli parse). Test seams are propagated so QA can drive
     * the PKI-missing path without a real {@code useradd} or {@code sudo}.
     */
    private int runPkiInit(boolean pkiForce) throws Exception {
        PkiInitCommand.Init init = new PkiInitCommand.Init();
        init.pkiDir = pkiDir;
        init.clientsDir = clientsDir;
        init.enrollmentDir = enrollmentDir;
        init.secretsDir = secretsDir;
        init.sessionsDir = sessionsDir;
        init.logDir = logDir;
        init.configFile = configFile;
        init.cn = cn;
        init.sanEntries = sanEntries;
        init.hostnameOption = hostnameOption;
        init.noAutoHostname = noAutoHostname;
        init.systemUserName = systemUserName;
        init.force = pkiForce;
        init.setRootCheck(rootCheck);
        if (systemUserAdmin != null) {
            init.setSystemUserAdmin(systemUserAdmin);
        }
        return init.call();
    }

    // ── helpers (inlined from SecretsSeedCommand / PkiInitCommand) ──────

    /**
     * Effective SSH dir for candidate enumeration. Under {@code sudo},
     * {@code $HOME} resolves to {@code /root}; prefer {@code $SUDO_USER}'s
     * {@code ~/.ssh} when set. Matches {@code secrets seed}'s resolver so
     * the two wizards enumerate the same candidate keys.
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
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static boolean isRoot() {
        try {
            Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
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
}
