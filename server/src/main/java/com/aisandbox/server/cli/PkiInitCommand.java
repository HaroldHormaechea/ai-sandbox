package com.aisandbox.server.cli;

import com.aisandbox.server.cli.pki.PemWriter;
import com.aisandbox.server.cli.pki.SelfSignedServerCertGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code aisandboxctl pki init} — UC05 § AC13–AC18 / AC34.
 *
 * <p>The one-shot per-host setup command. Creates the
 * {@code ai-sandbox-server} system user (if missing), provisions every
 * operator-managed directory under {@code /etc/ai-sandbox-server/} +
 * {@code /var/lib/ai-sandbox-server/} + {@code /var/log/ai-sandbox-server/}
 * with the documented owners and modes, mints the self-signed server
 * cert + key, and writes {@code /etc/ai-sandbox-server/config.yaml} with
 * the install-layout defaults baked in.
 *
 * <p>Idempotent only with {@code --force}: by default the command refuses
 * to run when any of {@code pki/}, {@code clients/}, {@code enrollment/},
 * {@code secrets/}, or {@code config.yaml} already exist. Re-running with
 * {@code --force} overwrites every conflict.
 *
 * <p>Requires root (uid 0) on POSIX hosts so {@code useradd} succeeds and
 * directory ownership can be set. Non-POSIX hosts (Windows) short-circuit
 * the root + user-creation steps but still write the cert + config.
 */
@Command(name = "pki", description = "PKI provisioning.", subcommands = PkiInitCommand.Init.class)
public class PkiInitCommand implements Runnable {

    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(
            name = "init",
            description = {
                "One-shot per-host setup for a fresh ai-sandbox-server install.",
                "",
                "Creates the ai-sandbox-server system user, the operator-managed",
                "directory tree under /etc/ai-sandbox-server/, /var/lib/ai-sandbox-server/",
                "and /var/log/ai-sandbox-server/, mints the self-signed server cert,",
                "and writes /etc/ai-sandbox-server/config.yaml with install-layout",
                "defaults baked in. Requires root.",
                "",
                "Idempotent only with --force: by default refuses to overwrite",
                "existing pki/, clients/, enrollment/, secrets/, or config.yaml."
            })
    public static class Init implements Callable<Integer> {

        // UC05 § AC14 — operator-managed directory paths. The defaults
        // wire pki init to the install-layout paths; flags exist so the
        // CI smoke job (and the occasional re-targeted install) can
        // override without recompiling.
        @Option(names = "--pki-dir", description = "PKI directory (default ${DEFAULT-VALUE})")
        Path pkiDir = Path.of("/etc/ai-sandbox-server/pki");

        @Option(names = "--clients-dir", description = "Allowlist directory (default ${DEFAULT-VALUE})")
        Path clientsDir = Path.of("/etc/ai-sandbox-server/clients");

        @Option(names = "--enrollment-dir", description = "Enrollment-token directory (default ${DEFAULT-VALUE})")
        Path enrollmentDir = Path.of("/etc/ai-sandbox-server/enrollment");

        @Option(
                names = "--secrets-dir",
                description = "Container-mounted secrets directory (default ${DEFAULT-VALUE})")
        Path secretsDir = Path.of("/etc/ai-sandbox-server/secrets");

        @Option(names = "--sessions-dir", description = "Per-session host-state root (default ${DEFAULT-VALUE})")
        Path sessionsDir = Path.of("/var/lib/ai-sandbox-server/sessions");

        @Option(names = "--log-dir", description = "Audit-log directory (default ${DEFAULT-VALUE})")
        Path logDir = Path.of("/var/log/ai-sandbox-server");

        @Option(names = "--config", description = "Config file path (default ${DEFAULT-VALUE})")
        Path configFile = Path.of("/etc/ai-sandbox-server/config.yaml");

        @Option(names = "--cn", description = "Server cert Common Name (default ${DEFAULT-VALUE})")
        String cn = "ai-sandbox-server";

        @Option(
                names = "--user",
                description = "System user to own the directory tree (default ${DEFAULT-VALUE})")
        String systemUserName = "ai-sandbox-server";

        @Option(
                names = "--force",
                description = "Overwrite existing pki/, clients/, enrollment/, secrets/, or config.yaml.")
        boolean force;

        // Injectable for tests. Wired manually because the CLI does not
        // load Spring; production callers use the default impl.
        private SystemUserAdmin systemUserAdmin = new SystemUserAdmin.Default();

        // Test seam (b) — gates the "must run as root" check. Production
        // defaults to the real `id -u` probe; tests inject `() -> true` so
        // the rest of the flow runs against a tempdir hierarchy without
        // needing sudo.
        private java.util.function.BooleanSupplier rootCheck = Init::isRoot;

        /** Test seam — substitute a fake SystemUserAdmin before invoking {@link #call()}. */
        void setSystemUserAdmin(SystemUserAdmin admin) {
            this.systemUserAdmin = admin;
        }

        /** Test seam — override the root-check probe. */
        void setRootCheck(java.util.function.BooleanSupplier rootCheck) {
            this.rootCheck = rootCheck;
        }

        @Override
        public Integer call() throws Exception {
            boolean posix = isPosix();

            // 1. Root check (POSIX only). UC05 § AC13 — useradd is root-only,
            //    and we need to chown directories to ai-sandbox-server.
            //    Uses the injectable rootCheck seam so tests can bypass.
            if (posix && !rootCheck.getAsBoolean()) {
                System.err.println("aisandboxctl pki init: must run as root (use sudo).");
                return 2;
            }

            // 2. Refuse-without-force (AC17). Collect every conflict, print
            //    the full list, then exit 2. config.yaml is included even
            //    though it is a file rather than a directory.
            List<Path> conflicts = new ArrayList<>();
            for (Path p : List.of(pkiDir, clientsDir, enrollmentDir, secretsDir, configFile)) {
                if (Files.exists(p)) {
                    conflicts.add(p);
                }
            }
            if (!conflicts.isEmpty() && !force) {
                System.err.println("aisandboxctl pki init: refusing to overwrite. Use --force to override.");
                for (Path c : conflicts) {
                    System.err.println("conflict: " + c);
                }
                return 2;
            }

            // 3. System user (AC13). Skipped on non-POSIX hosts.
            if (posix) {
                if (!systemUserAdmin.userExists(systemUserName)) {
                    System.out.println("Creating system user: " + systemUserName);
                    systemUserAdmin.createSystemUser(systemUserName);
                } else {
                    System.out.println("System user already present: " + systemUserName);
                }
            }

            // 4. Directory creation + chown + chmod (AC14). The full
            //    operator-managed tree. /etc/ai-sandbox-server itself is
            //    0750 (parent); the children holding key material are
            //    0700; /var/lib + /var/log are 0750.
            //    Derive etcRoot from pkiDir's parent so a test (or anyone
            //    passing --pki-dir <tmp>/pki) gets a consistent tree rooted
            //    at the supplied parent; defaults to /etc/ai-sandbox-server
            //    via pkiDir's default value of /etc/ai-sandbox-server/pki.
            Path etcRoot = (pkiDir.getParent() != null) ? pkiDir.getParent() : Path.of("/etc/ai-sandbox-server");
            ensureDir(etcRoot, "rwxr-x---", posix);
            ensureDir(pkiDir, "rwx------", posix);
            ensureDir(clientsDir, "rwx------", posix);
            ensureDir(enrollmentDir, "rwx------", posix);
            ensureDir(secretsDir, "rwx------", posix);
            Path sessionsParent = sessionsDir.getParent();
            if (sessionsParent != null) {
                ensureDir(sessionsParent, "rwxr-x---", posix);
            }
            ensureDir(sessionsDir, "rwxr-x---", posix);
            ensureDir(logDir, "rwxr-x---", posix);

            // Chown the entire tree to ai-sandbox-server:ai-sandbox-server.
            // Resolve owner/group once: on a test host (or any environment
            // where the user wasn't actually created), the lookup throws
            // UserPrincipalNotFoundException — we log a single warning and
            // skip every chown rather than blowing up mid-flow. The CI
            // smoke job exercises real chown as root in ubuntu:24.04.
            Ownership ownership = posix ? resolveOwnership(systemUserName) : null;
            if (posix && ownership != null) {
                chownTreeWith(ownership, etcRoot);
                if (sessionsParent != null) {
                    chownWith(ownership, sessionsParent);
                }
                chownTreeWith(ownership, sessionsDir);
                chownTreeWith(ownership, logDir);
            }

            // 5. Cert mint (AC15). The key file gets mode 0600 explicitly,
            //    overriding the parent dir's permissions.
            Path crt = pkiDir.resolve("server.crt");
            Path key = pkiDir.resolve("server.key");
            var mat = new SelfSignedServerCertGenerator().generate(cn);
            PemWriter.writeCert(crt, mat.certificate());
            PemWriter.writePrivateKey(key, mat.keyPair().getPrivate());
            if (posix) {
                Files.setPosixFilePermissions(crt, PosixFilePermissions.fromString("rw-r--r--"));
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
                if (ownership != null) {
                    chownWith(ownership, crt);
                    chownWith(ownership, key);
                }
            }

            // 6. Config write (AC16). bakedConfigYaml() encodes every
            //    install-layout default so the operator never edits paths
            //    post-install.
            Files.writeString(configFile, bakedConfigYaml());
            if (posix) {
                Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-r-----"));
                if (ownership != null) {
                    chownWith(ownership, configFile);
                }
            }

            // 7. Print summary.
            System.out.println("PKI + directory tree initialised:");
            System.out.println("  user    : " + systemUserName);
            System.out.println("  cert    : " + crt + "  (mode 0644)");
            System.out.println("  key     : " + key + "  (mode 0600)");
            System.out.println("  clients : " + clientsDir + "  (mode 0700)");
            System.out.println("  enroll  : " + enrollmentDir + "  (mode 0700)");
            System.out.println("  secrets : " + secretsDir + "  (mode 0700)");
            System.out.println("  sessions: " + sessionsDir + "  (mode 0750)");
            System.out.println("  logs    : " + logDir + "  (mode 0750)");
            System.out.println("  config  : " + configFile);
            System.out.println();
            System.out.println("Next: populate " + secretsDir
                    + " with your SSH key (git-key) and optional gh-token,");
            System.out.println("      then `systemctl enable --now ai-sandbox-server`.");
            return 0;
        }

        // ── helpers ──────────────────────────────────────────────────

        private static boolean isPosix() {
            return java.nio.file.FileSystems.getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix");
        }

        private static boolean isRoot() {
            // POSIX guarantees `id -u`. We use that rather than parsing
            // SystemProperty heuristics because the latter is brittle on
            // containerised setups where user.name reflects the
            // container's runtime user but uid-0 ownership is what
            // useradd / chown actually require.
            try {
                Process p = new ProcessBuilder("id", "-u")
                        .redirectErrorStream(true)
                        .start();
                byte[] out = p.getInputStream().readAllBytes();
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
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

        /** Pre-resolved owner + group, captured once so a per-file lookup can't surprise us mid-walk. */
        private record Ownership(UserPrincipal owner, GroupPrincipal group) {}

        /**
         * Resolve {@code <user>:<user>} once. Returns {@code null} when the
         * lookup fails (the user isn't on the host — typical for unit-test
         * runs). On {@code null} the caller skips every chown; production
         * environments where {@code aisandboxctl pki init} just created the
         * user via {@code SystemUserAdmin} reach this path with a live user
         * and a non-null Ownership.
         */
        private static Ownership resolveOwnership(String user) {
            UserPrincipalLookupService lookup =
                    java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService();
            try {
                UserPrincipal owner = lookup.lookupPrincipalByName(user);
                GroupPrincipal group = lookup.lookupPrincipalByGroupName(user);
                return new Ownership(owner, group);
            } catch (IOException ioe) {
                System.err.println(
                        "aisandboxctl pki init: skipping chown — user '" + user + "' not resolvable on this host ("
                                + ioe.getClass().getSimpleName() + "). Production runs MUST be invoked as root after"
                                + " the system user has been created.");
                return null;
            }
        }

        private static void chownWith(Ownership ownership, Path p) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(p, PosixFileAttributeView.class);
            view.setOwner(ownership.owner());
            view.setGroup(ownership.group());
        }

        private static void chownTreeWith(Ownership ownership, Path root) throws IOException {
            try (var stream = Files.walk(root)) {
                for (var it = stream.iterator(); it.hasNext(); ) {
                    chownWith(ownership, it.next());
                }
            }
        }

        // UC05 § AC16 — every install-layout default is baked in. The
        // annotated /opt/ai-sandbox-server/sample-config.yaml stays in
        // the release zip for operators who want to study tunable knobs;
        // the file written here is intentionally minimal and pins only
        // the paths the operator must not edit.
        private static String bakedConfigYaml() {
            return """
                    # /etc/ai-sandbox-server/config.yaml — generated by
                    # `aisandboxctl pki init`. Every key has a sensible default
                    # baked into the fat jar; this file pins the install-layout
                    # paths so the operator never edits them.
                    #
                    # See /opt/ai-sandbox-server/sample-config.yaml for the full
                    # annotated reference of tunable knobs.
                    ai-sandbox:
                      server:
                        pki:
                          dir: /etc/ai-sandbox-server/pki
                        clients:
                          dir: /etc/ai-sandbox-server/clients
                        hostscripts:
                          repo-root: /opt/ai-sandbox-server/host
                        sessions:
                          host-state-root: /var/lib/ai-sandbox-server/sessions
                        secrets:
                          dir: /etc/ai-sandbox-server/secrets
                        enrollment:
                          dir: /etc/ai-sandbox-server/enrollment
                        audit:
                          file: /var/log/ai-sandbox-server/audit.log
                    """;
        }
    }
}
