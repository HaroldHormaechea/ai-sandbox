package com.aisandbox.server.cli;

import com.aisandbox.server.cli.pki.PemWriter;
import com.aisandbox.server.cli.pki.SelfSignedServerCertGenerator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        Path enrollmentDir = Path.of("/var/lib/ai-sandbox-server/enrollment");

        @Option(names = "--secrets-dir", description = "Container-mounted secrets directory (default ${DEFAULT-VALUE})")
        Path secretsDir = Path.of("/etc/ai-sandbox-server/secrets");

        @Option(names = "--sessions-dir", description = "Per-session host-state root (default ${DEFAULT-VALUE})")
        Path sessionsDir = Path.of("/var/lib/ai-sandbox-server/sessions");

        @Option(names = "--log-dir", description = "Audit-log directory (default ${DEFAULT-VALUE})")
        Path logDir = Path.of("/var/log/ai-sandbox-server");

        @Option(names = "--config", description = "Config file path (default ${DEFAULT-VALUE})")
        Path configFile = Path.of("/etc/ai-sandbox-server/config.yaml");

        @Option(names = "--cn", description = "Server cert Common Name (default ${DEFAULT-VALUE})")
        String cn = "ai-sandbox-server";

        // UC05 § AC15 — SAN composition. Modern TLS clients ignore CN and
        // verify against the SubjectAlternativeName extension only; an
        // operator-issued mint without SAN cannot be verified by curl/
        // OkHttp/browsers even though the cert is otherwise valid.
        //
        // `--san` is both repeatable (--san DNS:a --san IP:b) and
        // comma-splittable (--san DNS:a,IP:b); picocli supports both
        // simultaneously with arity="0..*" + split=",".
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

        @Option(names = "--user", description = "System user to own the directory tree (default ${DEFAULT-VALUE})")
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
            ensureDir(secretsDir, "rwx------", posix);
            Path sessionsParent = sessionsDir.getParent();
            if (sessionsParent != null) {
                ensureDir(sessionsParent, "rwxr-x---", posix);
            }
            ensureDir(sessionsDir, "rwxr-x---", posix);
            // UC04 — enrollment dir moved to /var/lib/ai-sandbox-server/enrollment
            // (FHS: writable runtime state belongs under /var/lib, not /etc).
            // Created here, after sessionsDir, so its parent already exists at
            // the right mode. Chown handled separately below — etcRoot's
            // recursive chown no longer covers it.
            ensureDir(enrollmentDir, "rwx------", posix);
            ensureDir(logDir, "rwxr-x---", posix);

            // Chown the entire tree to ai-sandbox-server:ai-sandbox-server.
            // Resolve owner/group once: on a test host (or any environment
            // where the user wasn't actually created), the lookup throws
            // UserPrincipalNotFoundException — we log a single warning and
            // skip every chown rather than blowing up mid-flow. The CI
            // smoke job exercises real chown as root in ubuntu:24.04.
            Ownership ownership = posix ? Ownership.resolve(systemUserName, "pki init") : null;
            if (posix && ownership != null) {
                ownership.chownTree(etcRoot);
                if (sessionsParent != null) {
                    ownership.chown(sessionsParent);
                }
                ownership.chownTree(sessionsDir);
                // Enrollment dir lives under /var/lib (sibling of sessions),
                // so it is not covered by the etcRoot chownTree above.
                ownership.chownTree(enrollmentDir);
                ownership.chownTree(logDir);
            }

            // 5. Cert mint (AC15). The key file gets mode 0600 explicitly,
            //    overriding the parent dir's permissions.
            //
            //    SAN composition (UC07): caller-supplied entries first,
            //    then auto-derived hostname (unless --no-auto-hostname),
            //    then loopback (DNS:localhost + IP:127.0.0.1). DNS values
            //    are lowercased before dedup so '--san DNS:Foo --hostname foo'
            //    collapses to one entry. See SelfSignedServerCertGenerator
            //    for the on-the-wire encoding.
            List<String> finalSan = composeSanEntries(sanEntries, hostnameOption, noAutoHostname);
            Path crt = pkiDir.resolve("server.crt");
            Path key = pkiDir.resolve("server.key");
            var mat = new SelfSignedServerCertGenerator().generate(cn, finalSan);
            PemWriter.writeCert(crt, mat.certificate());
            PemWriter.writePrivateKey(key, mat.keyPair().getPrivate());
            if (posix) {
                Files.setPosixFilePermissions(crt, PosixFilePermissions.fromString("rw-r--r--"));
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
                if (ownership != null) {
                    ownership.chown(crt);
                    ownership.chown(key);
                }
            }

            // 6. Config write (AC16). bakedConfigYaml() encodes every
            //    install-layout default so the operator never edits paths
            //    post-install.
            Files.writeString(configFile, bakedConfigYaml());
            if (posix) {
                Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-r-----"));
                if (ownership != null) {
                    ownership.chown(configFile);
                }
            }

            // 7. Print summary.
            System.out.println("PKI + directory tree initialised:");
            System.out.println("  user    : " + systemUserName);
            System.out.println("  cert    : " + crt + "  (mode 0644)");
            System.out.println("  key     : " + key + "  (mode 0600)");
            System.out.println("  san     : " + (finalSan.isEmpty() ? "<none>" : String.join(", ", finalSan)));
            System.out.println("  clients : " + clientsDir + "  (mode 0700)");
            System.out.println("  enroll  : " + enrollmentDir + "  (mode 0700)");
            System.out.println("  secrets : " + secretsDir + "  (mode 0700)");
            System.out.println("  sessions: " + sessionsDir + "  (mode 0750)");
            System.out.println("  logs    : " + logDir + "  (mode 0750)");
            System.out.println("  config  : " + configFile);
            System.out.println();
            System.out.println("Next: populate " + secretsDir + " with your SSH key (git-key) and optional gh-token,");
            System.out.println(
                    "      then: mint at least one client cert (aisandboxctl client mint <name>) — the server");
            System.out.println("      refuses to start on an empty allowlist,");
            System.out.println("      then `systemctl enable --now ai-sandbox-server`.");
            return 0;
        }

        // ── SAN composition ──────────────────────────────────────────

        /**
         * Compose the final ordered, deduplicated SAN entry list per the
         * documented policy (see class Javadoc):
         *
         * <ol>
         *   <li>caller-supplied {@code --san} entries (preserved order)</li>
         *   <li>{@code --hostname <X>} translated to {@code DNS:<X>}</li>
         *   <li>{@code DNS:<getLocalHost().getHostName()>} unless {@code --no-auto-hostname}</li>
         *   <li>{@code DNS:localhost}</li>
         *   <li>{@code IP:127.0.0.1}</li>
         * </ol>
         *
         * <p>Dedup uses a {@link LinkedHashSet} keyed on the lowercased entry
         * (case-folding applied to the DNS value half so {@code DNS:Foo} and
         * {@code DNS:foo} collapse). Order is preserved across the composition
         * — the first occurrence wins. Blank / null entries are dropped.
         *
         * <p>{@link UnknownHostException} from {@link InetAddress#getLocalHost()}
         * emits a single stderr warning and falls through to the loopback-only
         * baseline (entries 4 + 5).
         */
        static List<String> composeSanEntries(List<String> sanEntries, String hostnameOption, boolean noAutoHostname) {
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            List<String> out = new ArrayList<>();

            if (sanEntries != null) {
                for (String e : sanEntries) {
                    addSanEntry(out, dedup, e);
                }
            }
            if (hostnameOption != null && !hostnameOption.isBlank()) {
                addSanEntry(out, dedup, "DNS:" + hostnameOption.trim());
            }
            if (!noAutoHostname) {
                try {
                    String h = InetAddress.getLocalHost().getHostName();
                    if (h != null && !h.isBlank()) {
                        addSanEntry(out, dedup, "DNS:" + h);
                    }
                } catch (UnknownHostException uhe) {
                    System.err.println("aisandboxctl pki init: warning — getLocalHost().getHostName() failed ("
                            + uhe.getClass().getSimpleName()
                            + "); falling through to loopback-only SAN. Pass --hostname or --san to fix.");
                }
            }
            addSanEntry(out, dedup, "DNS:localhost");
            addSanEntry(out, dedup, "IP:127.0.0.1");
            return out;
        }

        /**
         * Append {@code raw} to {@code out} iff its canonical form is not
         * already present in {@code dedup}. Blank / null entries are dropped.
         * Malformed entries (no {@code ':'}) are passed through verbatim so
         * the cert generator's parser can produce the canonical error
         * message at mint time (rather than us duplicating its validation
         * here).
         *
         * <p><b>Canonical form:</b> tag uppercased ({@code DNS:}, {@code IP:}),
         * value lowercased. {@code SelfSignedServerCertGenerator.parseSanEntries}
         * does a case-sensitive switch on the tag, so an emitted lowercase
         * {@code dns:} would crash mint — every entry the composer produces
         * MUST carry the uppercase tag. Lowercasing the value half is what
         * collapses {@code DNS:Foo} and {@code DNS:foo} to a single entry;
         * IP values are unaffected by case folding (digits) but get the
         * same treatment for uniformity.
         */
        private static void addSanEntry(List<String> out, LinkedHashSet<String> dedup, String raw) {
            if (raw == null) {
                return;
            }
            String entry = raw.trim();
            if (entry.isEmpty()) {
                return;
            }
            int colon = entry.indexOf(':');
            String canonical;
            if (colon < 0) {
                // Malformed — passed through so the parser emits the
                // canonical error message. dedup key matches input so
                // duplicates are still suppressed.
                canonical = entry;
            } else {
                String tag = entry.substring(0, colon).toUpperCase(Locale.ROOT);
                String value = entry.substring(colon + 1).toLowerCase(Locale.ROOT);
                canonical = tag + ":" + value;
            }
            if (dedup.add(canonical)) {
                out.add(canonical);
            }
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
                Process p =
                        new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
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
                          dir: /var/lib/ai-sandbox-server/enrollment
                        audit:
                          file: /var/log/ai-sandbox-server/audit.log
                    """;
        }
    }
}
