package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * v0.0.19 {@code .deb}-install crashloop regression guard — empty-allowlist
 * boot.
 *
 * <p>A brand-new {@code .deb} install has a valid server cert+key but an
 * <em>empty</em> {@code clients/} allowlist directory (the operator has not
 * yet minted or enrolled any client cert). Before the fix,
 * {@link com.aisandbox.server.config.PropertiesValidationStartupCheck#verify()}
 * threw {@code IllegalStateException} on an empty allowlist; because that
 * check fires from {@code ApplicationStartedEvent} during {@code
 * SpringApplication.run(...)}, the freshly-installed service booted, threw,
 * and died — and systemd's {@code Restart=on-failure} looped it until
 * {@code StartLimitBurst} latched it {@code failed}. That is the crashloop
 * v0.0.19 shipped.
 *
 * <p>The fix downgrades the empty-allowlist branch to a {@code LOG.warn}: the
 * server now starts on an empty allowlist and (with {@code
 * clientAuth=OPTIONAL} + the mTLS enforcement filter) 401s every request
 * until a client is authorized — but it does NOT refuse to start. This test
 * is the literal reproduction: it boots a real Spring context against a valid
 * server cert+key and an EMPTY clients directory (no client PEM is written)
 * and asserts the context refreshes and the TLS listener binds.
 *
 * <p>This class is the empty-allowlist sibling of
 * {@link SslContextBootOrderTest} (whose fixture pre-populates the allowlist
 * with one client PEM). It reuses that test's static-temp-tree +
 * {@link DynamicPropertySource} bring-up pattern verbatim — see that class's
 * Javadoc for why the scratch tree is built in {@code <clinit>} rather than
 * via JUnit's {@code @TempDir}, and why the audit-file system property is set
 * eagerly for {@code logback-spring.xml}.
 *
 * <p>Naming note: like {@link SslContextBootOrderTest}, this class
 * deliberately drops the {@code IT} suffix. {@code server/build.gradle.kts}
 * excludes {@code **&#47;*IT.class} from {@code :server:test}; the {@code Test}
 * suffix ensures this crashloop guard runs on every PR with no Docker / DinD
 * dependency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmptyAllowlistBootTest {

    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;
    private static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-empty-allowlist-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            // Real server cert + key inside pkiDir — the cert/key checks are
            // HARD boot failures and must pass for the empty-allowlist branch
            // to be the only thing under test.
            CertFixtures.writeServerMaterialTo(PKI_DIR, "empty-allowlist-test-server");

            // DELIBERATELY write NO client PEM. CLIENTS_DIR exists but is
            // empty — this is the fresh-install bootstrap state and the
            // literal v0.0.19 reproduction. Before the fix this made the
            // context-startup throw; after the fix it only logs a warning.

            // UC02 host scripts as executable empty shims — only the
            // Files.isRegularFile && Files.isExecutable predicate is checked
            // at boot.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("lifecycle.sh"));

            // logback-spring.xml resolves ${ai-sandbox.server.audit.file}
            // during ApplicationEnvironmentPreparedEvent, before
            // @DynamicPropertySource entries are merged. Set it as a JVM-wide
            // system property so logback's property substitution finds it;
            // otherwise the AUDIT_FILE appender tries to open
            // /var/log/ai-sandbox-server/audit.log and crashes startup before
            // any of our beans are touched. (Mirrors SslContextBootOrderTest.)
            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

            // Clean the whole scratch tree on JVM exit.
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                try (var paths = Files.walk(ROOT)) {
                                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                                        try {
                                            Files.deleteIfExists(p);
                                        } catch (IOException ignored) {
                                            // best-effort cleanup
                                        }
                                    });
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            },
                            "ai-sandbox-empty-allowlist-cleanup"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        // Port 0 — let the OS pick a free port (no race / no flake).
        r.add("ai-sandbox.server.tls.port", () -> 0);
        r.add("ai-sandbox.server.tls.bind-address", () -> "127.0.0.1");
        r.add("ai-sandbox.server.pki.dir", PKI_DIR::toString);
        r.add("ai-sandbox.server.clients.dir", CLIENTS_DIR::toString);
        r.add("ai-sandbox.server.hostscripts.repo-root", SCRIPTS_DIR::toString);
        r.add(
                "ai-sandbox.server.audit.file",
                () -> AUDIT_DIR.resolve("audit.log").toString());
        r.add("ai-sandbox.server.enrollment.dir", ENROLLMENT_DIR::toString);
        r.add("ai-sandbox.server.sessions.host-state-root", SESSIONS_DIR::toString);
        r.add("ai-sandbox.server.secrets.dir", SECRETS_DIR::toString);
        r.add("server.port", () -> 0);
        r.add("server.shutdown", () -> "immediate");
        r.add("ai-sandbox.server.shutdown.rest-grace-seconds", () -> 1);
        r.add("ai-sandbox.server.shutdown.total-grace-seconds", () -> 2);
    }

    @Autowired
    ReloadableSslContextHolder holder;

    @LocalServerPort
    int port;

    /**
     * The core crashloop guard: the context refreshes and the application
     * starts even though the allowlist directory is empty. If the JUnit
     * lifecycle reaches this method at all, {@code SpringApplication.run(...)}
     * completed without {@code PropertiesValidationStartupCheck} aborting on
     * the empty allowlist — the exact failure v0.0.19 shipped. We additionally
     * assert the TLS holder is populated as a positive signal the reactive web
     * server wired up fully.
     */
    @Test
    void context_boots_on_an_empty_allowlist() {
        assertThat(holder).isNotNull();
        assertThat(holder.current()).isNotNull();
    }

    /**
     * Belt-and-braces: the TLS listener actually bound on a real port despite
     * the empty allowlist. We open and close a plain TCP socket — no TLS
     * handshake — just to confirm the port is open and accepting.
     */
    @Test
    void tls_listener_is_bound_with_an_empty_allowlist() throws IOException {
        assertThat(port).isGreaterThan(0);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            assertThat(s.isConnected()).isTrue();
        }
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
            // Windows path — fall back to the platform's chmod.
            target.toFile().setExecutable(true, false);
        }
    }
}
