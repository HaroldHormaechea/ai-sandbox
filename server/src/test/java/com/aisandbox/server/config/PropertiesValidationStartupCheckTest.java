package com.aisandbox.server.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level branch guard for {@link PropertiesValidationStartupCheck#verify()}
 * — the boot-preflight check whose empty-allowlist branch was the v0.0.19
 * {@code .deb} crashloop.
 *
 * <p>The integration sibling {@code
 * com.aisandbox.server.integration.EmptyAllowlistBootTest} boots a real Spring
 * context to prove the empty-allowlist path no longer aborts startup; this
 * class exercises {@code verify()} directly (package-private, same package) so
 * every POSITIVE and NEGATIVE branch is asserted without paying the cost of a
 * context bring-up:
 *
 * <ul>
 *   <li>POSITIVE — an <em>empty</em> clients directory does NOT throw (the
 *       fix); a populated clients directory does NOT throw either (sanity).</li>
 *   <li>NEGATIVE — every HARD precondition still throws
 *       {@code IllegalStateException}: missing clients <em>directory</em>,
 *       missing / unreadable server cert, missing / unreadable server key,
 *       missing / non-executable host script, missing / unwritable audit
 *       directory.</li>
 * </ul>
 *
 * <p>{@code verify()} only checks {@code Files.isRegularFile} /
 * {@code isReadable} / {@code isExecutable} / {@code isWritable} predicates —
 * it never parses the cert or key — so the fixtures here are plain readable
 * files, not real PEM material.
 *
 * <p>Permission-based negatives (unreadable cert/key, unwritable audit dir)
 * are gated on {@link #permissionsAreEnforced(Path)}: a {@code root} user
 * bypasses POSIX read/write bits, so those cases are skipped (not failed) when
 * the test JVM can read a {@code 000} file — e.g. inside the root-running CI
 * smoke container. The non-executable-script negative needs no such gate:
 * {@code access(X_OK)} fails even for root when a file carries zero execute
 * bits.
 */
class PropertiesValidationStartupCheckTest {

    // ── POSITIVE branches ────────────────────────────────────────────

    @Test
    void empty_allowlist_directory_does_not_throw(@TempDir Path root) throws Exception {
        seedValidTree(root);
        // clients/ exists but is empty — the fresh-install bootstrap state.
        assertThatCode(() -> check(root).verify())
                .as("v0.0.19 fix — an empty allowlist must NOT abort boot; it logs a warning instead")
                .doesNotThrowAnyException();
    }

    @Test
    void populated_allowlist_directory_does_not_throw(@TempDir Path root) throws Exception {
        seedValidTree(root);
        // One client PEM present — the steady-state install.
        Files.writeString(root.resolve("clients").resolve("alice.crt"), "client-material\n");
        assertThatCode(() -> check(root).verify()).doesNotThrowAnyException();
    }

    // ── NEGATIVE branches — HARD failures that must still throw ───────

    @Test
    void missing_clients_directory_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        Files.delete(root.resolve("clients"));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Allowlist directory missing");
    }

    @Test
    void missing_server_certificate_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        Files.delete(root.resolve("pki").resolve("server.crt"));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server certificate")
                .hasMessageContaining("not readable");
    }

    @Test
    void missing_server_key_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        Files.delete(root.resolve("pki").resolve("server.key"));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server private key")
                .hasMessageContaining("not readable");
    }

    @Test
    void unreadable_server_certificate_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        assumeTrue(permissionsAreEnforced(root), "POSIX read perms not enforced for this user (root?) — skip");
        Path crt = root.resolve("pki").resolve("server.crt");
        Files.setPosixFilePermissions(crt, EnumSet.noneOf(PosixFilePermission.class));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server certificate")
                .hasMessageContaining("not readable");
    }

    @Test
    void missing_host_script_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        Files.delete(root.resolve("hostscripts").resolve("clean.sh"));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required UC02 host script");
    }

    @Test
    void non_executable_host_script_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        assumeTrue(
                root.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "non-POSIX filesystem — cannot strip the execute bit");
        Path spawn = root.resolve("hostscripts").resolve("spawn.sh");
        // 0644 — readable, NOT executable. access(X_OK) fails even for root
        // when zero execute bits are set, so this needs no root gate.
        Files.setPosixFilePermissions(
                spawn,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
        assertThatThrownBy(() -> check(root).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required UC02 host script");
    }

    @Test
    void missing_audit_directory_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        // Point the audit file at a parent directory that does not exist.
        ServerProperties props = props(
                root.resolve("pki"),
                root.resolve("clients"),
                root.resolve("hostscripts"),
                root.resolve("no-such-dir").resolve("audit.log"));
        assertThatThrownBy(() -> new PropertiesValidationStartupCheck(props).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Audit log directory missing or not writable");
    }

    @Test
    void unwritable_audit_directory_throws(@TempDir Path root) throws Exception {
        seedValidTree(root);
        assumeTrue(permissionsAreEnforced(root), "POSIX write perms not enforced for this user (root?) — skip");
        Path auditDir = root.resolve("audit");
        // r-x------ : readable + traversable but NOT writable.
        Files.setPosixFilePermissions(
                auditDir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThatThrownBy(() -> check(root).verify())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Audit log directory missing or not writable");
        } finally {
            // Restore write perms so @TempDir cleanup can delete the tree.
            Files.setPosixFilePermissions(
                    auditDir,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    // ── fixtures / helpers ───────────────────────────────────────────

    /**
     * Build a fully-valid {@code /etc} tree under {@code root}: pki/ with a
     * readable server.crt + server.key, an empty clients/ directory, three
     * executable host-script shims, and a writable audit/ directory. Each
     * negative test breaks exactly one of these.
     */
    private static void seedValidTree(Path root) throws Exception {
        Path pki = Files.createDirectories(root.resolve("pki"));
        Files.writeString(pki.resolve("server.crt"), "test-cert-material\n");
        Files.writeString(pki.resolve("server.key"), "test-key-material\n");

        Files.createDirectories(root.resolve("clients"));

        Path scripts = Files.createDirectories(root.resolve("hostscripts"));
        writeExecutableShim(scripts.resolve("spawn.sh"));
        writeExecutableShim(scripts.resolve("attach.sh"));
        writeExecutableShim(scripts.resolve("clean.sh"));
        writeExecutableShim(scripts.resolve("lifecycle.sh"));

        Files.createDirectories(root.resolve("audit"));
    }

    private static PropertiesValidationStartupCheck check(Path root) {
        return new PropertiesValidationStartupCheck(props(
                root.resolve("pki"),
                root.resolve("clients"),
                root.resolve("hostscripts"),
                root.resolve("audit").resolve("audit.log")));
    }

    /**
     * Construct {@link ServerProperties} for {@code verify()} via the
     * backwards-compatible 8-arg constructor (Enrollment / Sessions / Secrets
     * default in — {@code verify()} never reads them). Mirrors the
     * construction style in {@code RequestSizeLimitFilterTest} /
     * {@code PerIpRateLimiterTest}.
     */
    private static ServerProperties props(Path pkiDir, Path clientsDir, Path scriptsDir, Path auditFile) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(pkiDir),
                new ServerProperties.Clients(clientsDir),
                new ServerProperties.Hostscripts(scriptsDir),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(auditFile, 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    private static void writeExecutableShim(Path target) throws IOException {
        Files.writeString(target, "#!/bin/sh\nexit 0\n");
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException uoe) {
            target.toFile().setExecutable(true, false);
        }
    }

    /**
     * Probe whether POSIX read/write bits are actually enforced for the test
     * JVM. Creates a {@code 000} file and checks it is unreadable; a root user
     * reads it anyway, so {@code false} means "skip permission-based
     * negatives". Returns {@code false} on non-POSIX filesystems too.
     */
    private static boolean permissionsAreEnforced(Path dir) throws IOException {
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return false;
        }
        Path probe = Files.createTempFile(dir, "perm-probe", ".tmp");
        try {
            Files.setPosixFilePermissions(probe, EnumSet.noneOf(PosixFilePermission.class));
            return !Files.isReadable(probe);
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
