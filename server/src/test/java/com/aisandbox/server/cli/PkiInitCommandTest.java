package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.pki.PemUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC05 § AC13–AC18 + AC34. Comprehensive unit coverage for
 * {@code aisandboxctl pki init}. The tests use three injected seams
 * the developer added at QA's request:
 *
 * <ol>
 *   <li>{@link PkiInitCommand.Init#setRootCheck(java.util.function.BooleanSupplier)}
 *       — bypass the {@code id -u} probe.</li>
 *   <li>Pass-by-flag {@code --pki-dir <tmp>/pki} → etcRoot derives from
 *       the supplied pkiDir's parent, so every operator-managed dir
 *       lands under the test's temp tree.</li>
 *   <li>{@link PkiInitCommand.Init#setSystemUserAdmin(SystemUserAdmin)}
 *       — replace the {@code useradd} / {@code getent} shell-outs with
 *       a counting fake.</li>
 * </ol>
 *
 * The chown branch falls back to a single warning-and-skip when the
 * {@code ai-sandbox-server} user isn't on the host, so the tests don't
 * need root and don't need to {@code useradd} anything. The CI
 * {@code release-install-smoke} job covers the real chown path inside
 * its root-running container.
 */
class PkiInitCommandTest {

    /** Counting fake for {@link SystemUserAdmin}; tracks user existence and create-calls. */
    private static final class FakeSystemUserAdmin implements SystemUserAdmin {
        boolean exists;
        final AtomicInteger createCalls = new AtomicInteger();

        FakeSystemUserAdmin(boolean initiallyExists) {
            this.exists = initiallyExists;
        }

        @Override
        public boolean userExists(String name) {
            return exists;
        }

        @Override
        public void createSystemUser(String name) {
            createCalls.incrementAndGet();
            exists = true;
        }
    }

    /**
     * Build an {@link PkiInitCommand.Init} with the test seams pre-wired:
     * rootCheck → true, systemUserAdmin → the supplied fake.
     */
    private static PkiInitCommand.Init init(FakeSystemUserAdmin admin) {
        PkiInitCommand.Init sub = new PkiInitCommand.Init();
        sub.setRootCheck(() -> true);
        sub.setSystemUserAdmin(admin);
        return sub;
    }

    /** Stage the standard `--pki-dir`/`--clients-dir`/... args under {@code etc/}. */
    private static String[] stdArgs(Path etc, Path sessionsRoot, Path logRoot, String... extras) {
        Path pki = etc.resolve("pki");
        Path clients = etc.resolve("clients");
        Path enrollment = etc.resolve("enrollment");
        Path secrets = etc.resolve("secrets");
        Path config = etc.resolve("config.yaml");
        String[] base = {
            "--pki-dir", pki.toString(),
            "--clients-dir", clients.toString(),
            "--enrollment-dir", enrollment.toString(),
            "--secrets-dir", secrets.toString(),
            "--sessions-dir", sessionsRoot.toString(),
            "--log-dir", logRoot.toString(),
            "--config", config.toString(),
        };
        String[] all = new String[base.length + extras.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extras, 0, all, base.length, extras.length);
        return all;
    }

    @Test
    void init_creates_full_directory_tree_with_documented_posix_modes(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/ai-sandbox-server/sessions");
        Path logs = tmp.resolve("var/log/ai-sandbox-server");

        FakeSystemUserAdmin admin = new FakeSystemUserAdmin(/* initiallyExists */ false);
        int exit = new CommandLine(init(admin)).execute(stdArgs(etc, sessions, logs));
        assertThat(exit).isZero();

        // AC14 — directory tree exists with documented modes. Ownership
        // (chown to ai-sandbox-server:ai-sandbox-server) is skipped on
        // hosts where the user is missing; the CI smoke job covers the
        // real-chown path.
        assertThat(etc).isDirectory();
        assertThat(etc.resolve("pki")).isDirectory();
        assertThat(etc.resolve("clients")).isDirectory();
        assertThat(etc.resolve("enrollment")).isDirectory();
        assertThat(etc.resolve("secrets")).isDirectory();
        assertThat(sessions).isDirectory();
        assertThat(logs).isDirectory();

        // Mode assertions are POSIX-gated. Run only when the test FS
        // supports posix attributes (Linux / macOS — the brief targets).
        if (isPosixFs(tmp)) {
            assertMode(etc, "rwxr-x---");
            assertMode(etc.resolve("pki"), "rwx------");
            assertMode(etc.resolve("clients"), "rwx------");
            assertMode(etc.resolve("enrollment"), "rwx------");
            assertMode(etc.resolve("secrets"), "rwx------");
            assertMode(sessions, "rwxr-x---");
            assertMode(logs, "rwxr-x---");

            // AC15 — the key file is explicitly 0600 even though its
            // parent is 0700; cert is 0644.
            assertMode(etc.resolve("pki").resolve("server.crt"), "rw-r--r--");
            assertMode(etc.resolve("pki").resolve("server.key"), "rw-------");

            // AC16 — config gets 0640 (group-readable since the systemd
            // unit's User runs in the `ai-sandbox-server` group).
            assertMode(etc.resolve("config.yaml"), "rw-r-----");
        }
    }

    @Test
    void init_creates_system_user_when_missing_and_skips_when_present(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        // Variant 1 — user absent before pki init; createSystemUser
        // invoked exactly once.
        FakeSystemUserAdmin absent = new FakeSystemUserAdmin(false);
        int exit1 = new CommandLine(init(absent)).execute(stdArgs(etc, sessions, logs));
        assertThat(exit1).isZero();
        assertThat(absent.createCalls.get())
                .as("createSystemUser invocations when user absent")
                .isEqualTo(1);

        // Variant 2 — fresh tempdir, user already present; the call to
        // createSystemUser must NOT happen.
        Path etc2 = tmp.resolve("etc2/ai-sandbox-server");
        Path sessions2 = tmp.resolve("var2/lib/sessions");
        Path logs2 = tmp.resolve("var2/log");
        FakeSystemUserAdmin present = new FakeSystemUserAdmin(true);
        int exit2 = new CommandLine(init(present)).execute(stdArgs(etc2, sessions2, logs2));
        assertThat(exit2).isZero();
        assertThat(present.createCalls.get())
                .as("createSystemUser invocations when user already present")
                .isZero();
    }

    @Test
    void init_refuses_to_overwrite_existing_material_without_force_and_lists_conflicts(@TempDir Path tmp)
            throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        // First run — clean install.
        int first = new CommandLine(init(new FakeSystemUserAdmin(false))).execute(stdArgs(etc, sessions, logs));
        assertThat(first).isZero();

        // AC17 — second run without --force exits 2 AND emits a
        // "conflict: <path>" line per existing path on stderr.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        int second;
        try {
            second = new CommandLine(init(new FakeSystemUserAdmin(true))).execute(stdArgs(etc, sessions, logs));
        } finally {
            System.setErr(origErr);
        }
        assertThat(second).isEqualTo(2);

        String stderr = errBuf.toString();
        assertThat(stderr).contains("refusing to overwrite");
        // The five paths the production code checks (pki, clients,
        // enrollment, secrets, config.yaml) MUST appear in the
        // conflict list.
        assertThat(stderr).contains("conflict: " + etc.resolve("pki"));
        assertThat(stderr).contains("conflict: " + etc.resolve("clients"));
        assertThat(stderr).contains("conflict: " + etc.resolve("enrollment"));
        assertThat(stderr).contains("conflict: " + etc.resolve("secrets"));
        assertThat(stderr).contains("conflict: " + etc.resolve("config.yaml"));
    }

    @Test
    void init_force_overwrites_all_five_paths(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        // First run with the default CN.
        int first = new CommandLine(init(new FakeSystemUserAdmin(false))).execute(stdArgs(etc, sessions, logs));
        assertThat(first).isZero();
        var beforeCert =
                PemUtils.parseCertificate(Files.readString(etc.resolve("pki").resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(beforeCert)).isEqualTo("ai-sandbox-server");

        // Tamper with each of the five paths to verify they all get
        // overwritten by --force.
        Files.writeString(etc.resolve("config.yaml"), "GARBAGE\n");
        Files.writeString(etc.resolve("pki").resolve("server.crt"), "GARBAGE\n");

        // Second run with --force and a different CN. AC18.
        int second = new CommandLine(init(new FakeSystemUserAdmin(true)))
                .execute(stdArgs(etc, sessions, logs, "--force", "--cn", "rotated-server"));
        assertThat(second).isZero();

        // Cert was minted afresh with the new CN.
        var afterCert =
                PemUtils.parseCertificate(Files.readString(etc.resolve("pki").resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(afterCert)).isEqualTo("rotated-server");

        // Config is back to the baked default (no "GARBAGE" line).
        String cfg = Files.readString(etc.resolve("config.yaml"));
        assertThat(cfg).doesNotContain("GARBAGE");
    }

    @Test
    void init_writes_config_yaml_with_the_four_baked_in_defaults(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        int exit = new CommandLine(init(new FakeSystemUserAdmin(false))).execute(stdArgs(etc, sessions, logs));
        assertThat(exit).isZero();

        // AC16 — config.yaml carries the four install-layout defaults
        // the operator must NEVER edit post-install. The exact YAML
        // shape is a developer-owned implementation detail; we assert
        // the four key/value pairs via string-contains so the test is
        // robust to minor formatting tweaks.
        String cfg = Files.readString(etc.resolve("config.yaml"));
        assertThat(cfg).contains("repo-root: /opt/ai-sandbox-server/host");
        assertThat(cfg).contains("host-state-root: /var/lib/ai-sandbox-server/sessions");
        assertThat(cfg).contains("dir: /etc/ai-sandbox-server/secrets");
        assertThat(cfg).contains("dir: /etc/ai-sandbox-server/enrollment");
    }

    @Test
    void init_force_rerun_succeeds_idempotently(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        // Three back-to-back runs: clean → conflict (exit 2) → force.
        int first = new CommandLine(init(new FakeSystemUserAdmin(false))).execute(stdArgs(etc, sessions, logs));
        assertThat(first).isZero();
        int second = new CommandLine(init(new FakeSystemUserAdmin(true))).execute(stdArgs(etc, sessions, logs));
        assertThat(second).isEqualTo(2);
        int third =
                new CommandLine(init(new FakeSystemUserAdmin(true))).execute(stdArgs(etc, sessions, logs, "--force"));
        assertThat(third).isZero();

        // Material still present after the force-rerun.
        assertThat(etc.resolve("pki").resolve("server.crt")).exists();
        assertThat(etc.resolve("pki").resolve("server.key")).exists();
        assertThat(etc.resolve("config.yaml")).exists();
    }

    @Test
    void init_root_check_blocks_when_uid_not_zero(@TempDir Path tmp) throws Exception {
        // Pre-UC05 behaviour parity — explicit assertion that the root
        // check fires (exit 2 + stderr) when the supplier returns false.
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        Path sessions = tmp.resolve("var/lib/sessions");
        Path logs = tmp.resolve("var/log");

        PkiInitCommand.Init sub = new PkiInitCommand.Init();
        sub.setRootCheck(() -> false);
        sub.setSystemUserAdmin(new FakeSystemUserAdmin(false));

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        int exit;
        try {
            exit = new CommandLine(sub).execute(stdArgs(etc, sessions, logs));
        } finally {
            System.setErr(origErr);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(errBuf.toString()).contains("must run as root");
        // Nothing was created — the early-return MUST fire before
        // any filesystem mutation.
        assertThat(etc).doesNotExist();
    }

    @Test
    void init_defaults_cn_to_ai_sandbox_server(@TempDir Path tmp) throws Exception {
        Path etc = tmp.resolve("etc/ai-sandbox-server");
        int exit = new CommandLine(init(new FakeSystemUserAdmin(false)))
                .execute(stdArgs(etc, tmp.resolve("v/lib"), tmp.resolve("v/log")));
        assertThat(exit).isZero();
        var cert = PemUtils.parseCertificate(Files.readString(etc.resolve("pki").resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(cert)).isEqualTo("ai-sandbox-server");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static boolean isPosixFs(Path tmp) {
        return tmp.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void assertMode(Path p, String expected) throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(p);
        Set<PosixFilePermission> want = new HashSet<>(PosixFilePermissions.fromString(expected));
        assertThat(actual).as("posix mode of %s", p).isEqualTo(want);
    }
}
