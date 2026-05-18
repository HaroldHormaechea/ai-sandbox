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
 * UC03 boot-order regression guard.
 *
 * <p>Before commit {@code b45e7d6}, the production wiring loaded the
 * initial server cert+key inside {@code ServerCertWatcher.@PostConstruct}.
 * {@code NettyServerCustomizer#applyTls} reads
 * {@link ReloadableSslContextHolder#current()} during
 * {@code ReactiveWebServerApplicationContext.onRefresh()} — strictly
 * earlier in the lifecycle than any non-eager {@code @PostConstruct} —
 * so a fresh boot crashed with {@code IllegalStateException: SSL context
 * not yet initialised} and the listener never came up. v0.0.4 and v0.0.5
 * shipped this bug.
 *
 * <p>The fix moves the initial load into the
 * {@code PrimaryConfiguration#reloadableSslContextHolder} bean factory,
 * so the holder is populated before the reactive web server is created.
 *
 * <p>This test boots a real Spring context against
 * {@link CertFixtures}-generated material in a temp PKI tree. The pass
 * criteria:
 *
 * <ol>
 *   <li>Context refreshes without throwing (implicit — JUnit fails the
 *       test class if it cannot wire up).</li>
 *   <li>{@link ReloadableSslContextHolder#current()} returns a non-null
 *       {@code SslContext} — the bean-creation-order invariant.</li>
 *   <li>The reactive web server's TLS listener actually bound (we open
 *       and close a TCP socket against the random port).</li>
 * </ol>
 *
 * <p>Naming note: this class deliberately drops the {@code IT} suffix the
 * other Spring-context classes in this package use. {@code server/build
 * .gradle.kts} excludes {@code **&#47;*IT.class} from {@code :server:test}
 * and only runs them via the gated {@code integrationTest} task when
 * {@code AI_SANDBOX_DIND=1}. This test needs neither Docker nor DinD —
 * it is a plain unit-tier Spring-context test — so the {@code Test}
 * suffix is the semantically correct fit, and it ensures the regression
 * guard actually runs on every PR.
 *
 * <p>Temp-dir strategy: this test populates its scratch tree via
 * {@link Files#createTempDirectory(String, java.nio.file.attribute.FileAttribute[])}
 * inside a static initialiser rather than using JUnit's {@code @TempDir}.
 * Static {@code @TempDir} fields are injected by the {@code TempDirectory}
 * extension's {@code beforeAll} callback, which fires AFTER
 * {@code SpringExtension}'s {@code beforeAll} that boots the context —
 * the {@code @DynamicPropertySource} suppliers would then see null paths
 * and the {@code PropertiesValidationStartupCheck} would fail. Doing it
 * in {@code <clinit>} keeps the dirs ready before any extension runs. A
 * shutdown hook handles cleanup so the project's existing {@code @TempDir}
 * machinery is not bypassed by leaked scratch trees across CI runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SslContextBootOrderTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-boot-order-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            // Real server cert + key inside pkiDir.
            CertFixtures.writeServerMaterialTo(PKI_DIR, "boot-order-test-server");
            // One client PEM so PropertiesValidationStartupCheck's
            // refuse-empty allowlist policy is satisfied.
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "boot-order-test-client");
            // UC02 host scripts as executable empty shims — only the
            // Files.isRegularFile && Files.isExecutable predicate is
            // checked at boot.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));

            // logback-spring.xml resolves ${ai-sandbox.server.audit.file}
            // during ApplicationEnvironmentPreparedEvent, which fires
            // BEFORE @DynamicPropertySource entries are merged into the
            // Environment. Set the value as a JVM-wide system property
            // so logback's property substitution picks it up via its
            // system-property fallback. Without this the AUDIT_FILE
            // RollingFileAppender tries to open
            // /var/log/ai-sandbox-server/audit.log and crashes the
            // context-startup before any of our beans are touched.
            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

            // Cleanup the whole scratch tree on JVM exit.
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
                            "ai-sandbox-boot-order-cleanup"));
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
        // The reactive web server itself; setPort(0) in the customizer
        // already drives this from ai-sandbox.server.tls.port, but
        // Spring Boot's WebServerInitializedEvent reflects the bound
        // port into local.server.port for @LocalServerPort to read.
        r.add("server.port", () -> 0);
        // Keep the test profile minimal; explicit disables avoid CI
        // surprises.
        r.add("server.http2.enabled", () -> false);
        r.add("server.shutdown", () -> "immediate");
        r.add("ai-sandbox.server.shutdown.rest-grace-seconds", () -> 1);
        r.add("ai-sandbox.server.shutdown.total-grace-seconds", () -> 2);
    }

    @Autowired
    ReloadableSslContextHolder holder;

    @LocalServerPort
    int port;

    /**
     * Core invariant: the holder is populated by the time the reactive
     * web server is built. Before the fix, {@code current()} threw
     * {@code IllegalStateException: SSL context not yet initialised}
     * inside the {@code NettyServerCustomizer}'s
     * {@code .secure(sslHolder.current())} call during context refresh,
     * which Spring re-wrapped as a context-startup failure — the test
     * class would fail to wire up at all.
     */
    @Test
    void holder_is_populated_before_the_reactive_web_server_is_built() {
        assertThat(holder).isNotNull();
        assertThat(holder.current()).isNotNull();
    }

    /**
     * Belt-and-braces: the TLS listener actually bound. If
     * {@code NettyServerCustomizer#applyTls} had failed (the pre-fix
     * symptom), the context would have aborted and {@code port} would
     * be {@code -1} or unbound. We connect a plain TCP socket — no TLS
     * handshake — just to confirm the port is open. Connection success
     * is sufficient; we close immediately.
     */
    @Test
    void tls_listener_is_bound_on_a_real_port() throws IOException {
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
