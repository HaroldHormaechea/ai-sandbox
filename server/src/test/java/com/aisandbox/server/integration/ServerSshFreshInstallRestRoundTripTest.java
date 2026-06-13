package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.service.HostShellSessionService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.test.CertFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.netty.http.client.HttpClient;

/**
 * UC-63 — true end-to-end fresh-install verification of the SERVER SSH SESSION
 * row over real HTTPS against a booted {@code @SpringBootTest(RANDOM_PORT)}
 * Netty+TLS context, exercising the EXACT flow that was broken on a freshly
 * installed server.
 *
 * <h2>Why this proves the fix end-to-end (not just at the unit seam)</h2>
 *
 * <p>The shipped bug: on a fresh install the socket's parent directory does not
 * exist; {@code tmux new-session} writes "error creating &lt;sock&gt;" to stderr
 * yet exits {@code 0}; {@code ensureCreated()} trusted that {@code 0}, so
 * {@code POST /v1/sessions/server-ssh} returned a false {@code 200} while no
 * tmux existed — {@code GET /v1/sessions} then returned {@code []} (the pinned
 * row never listed) and the terminal WebSocket closed {@code 1011}
 * ({@code NoSuchElementException: session 0 disappeared during open}).
 *
 * <p>This test reproduces that environment precisely: the {@code server-ssh}
 * socket's parent dir is DELIBERATELY ABSENT before the request, and the entire
 * real business stack runs unmocked
 * (SessionController → SessionFacade → HostShellSessionService) against a
 * <b>real</b> host {@code tmux} (the {@link ProcessExecutor} seam delegates tmux
 * argv to a real executor and only neutralises the Docker-enumeration argv so a
 * Docker-less CI host enumerates zero container sessions). It then asserts the
 * post-fix behaviour:
 *
 * <ol>
 *   <li>the socket-parent dir is absent before the request (the fresh-install
 *       gap, AC1/AC8a precondition);</li>
 *   <li>{@code POST /v1/sessions/server-ssh} → {@code 200} with
 *       {@code type=server-ssh}, {@code n=0} (AC3/AC4 — honest success);</li>
 *   <li>the socket-parent dir was auto-created (AC1);</li>
 *   <li>a <b>real</b> {@code tmux -S <sock> has-session} now succeeds — a live
 *       host tmux truly exists (AC2/AC4, no exit-code lie);</li>
 *   <li>{@code GET /v1/sessions} → {@code 200} and the pinned {@code server-ssh}
 *       row (the one that used to be MISSING) IS listed, {@code state=running}
 *       (AC4);</li>
 *   <li>the registry's running {@code n=0} row satisfies
 *       {@code StreamFacade.openStream}'s pre-bridge {@code findSession(0)} gate
 *       — the precise predicate whose emptiness threw
 *       {@code NoSuchElementException} → {@code 1011}. With the row present, that
 *       1011 root cause is eliminated (AC4);</li>
 *   <li>the honest {@code "Created server-ssh host tmux"} INFO log fires (it only
 *       logs AFTER the post-create presence probe confirms the session).</li>
 * </ol>
 *
 * <p>Only the client-cert mTLS handshake is bypassed (via a
 * {@code HIGHEST_PRECEDENCE} identity-stashing {@link WebFilter} — the
 * established pattern in {@link SessionsRestRoundTripTest} /
 * {@code SessionEnumerationFailureControllerTest}); that is orthogonal to UC-63.
 * Guarded by a {@code tmux} availability assumption, mirroring
 * {@code HostShellSessionLiveTmuxTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerSshFreshInstallRestRoundTripTest {

    private static final Duration TMUX_TIMEOUT = Duration.ofSeconds(10);

    private static final Path ROOT;
    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SECRETS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path HOST_STATE_ROOT;

    /**
     * The fresh-install gap: the {@code server-ssh} socket lives under a subdir
     * that is intentionally NOT created here — {@code ensureCreated()} must
     * provision it. Kept separate from {@link #HOST_STATE_ROOT} (which exists, so
     * the rest of the server is undisturbed) to isolate exactly the missing
     * socket-parent dir.
     */
    private static final Path SERVER_SSH_SOCKET_PARENT;

    private static final Path SERVER_SSH_SOCKET;
    private static final String SERVER_SSH_NAME =
            "ai-sandbox-server-ssh-uc63-it-" + ProcessHandle.current().pid();

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-uc63-freshinstall-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            HOST_STATE_ROOT = Files.createDirectories(ROOT.resolve("host-state"));

            // INTENTIONALLY NOT created — this is the fresh-install gap.
            SERVER_SSH_SOCKET_PARENT = ROOT.resolve("freshstate");
            SERVER_SSH_SOCKET = SERVER_SSH_SOCKET_PARENT.resolve("server-ssh.sock");

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc63-freshinstall");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc63-bootstrap-client");

            // HostScriptLocator validates these at context init; the host-shell flow
            // never invokes them (it is in-process tmux), so inert shims suffice.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("lifecycle.sh"));

            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                // Kill the real host tmux this test brought up before deleting its socket.
                                try {
                                    new ProcessExecutor()
                                            .run(
                                                    List.of("tmux", "-S", SERVER_SSH_SOCKET.toString(), "kill-server"),
                                                    null,
                                                    Duration.ofSeconds(5));
                                } catch (Exception ignored) {
                                    // best-effort teardown
                                }
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
                            "ai-sandbox-uc63-freshinstall-cleanup"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ai-sandbox.server.tls.port", () -> 0);
        r.add("ai-sandbox.server.tls.bind-address", () -> "127.0.0.1");
        r.add("ai-sandbox.server.pki.dir", PKI_DIR::toString);
        r.add("ai-sandbox.server.clients.dir", CLIENTS_DIR::toString);
        r.add(
                "ai-sandbox.server.audit.file",
                () -> AUDIT_DIR.resolve("audit.log").toString());
        r.add("ai-sandbox.server.enrollment.dir", ENROLLMENT_DIR::toString);
        r.add("ai-sandbox.server.hostscripts.repo-root", SCRIPTS_DIR::toString);
        r.add("ai-sandbox.server.sessions.host-state-root", HOST_STATE_ROOT::toString);
        r.add("ai-sandbox.server.secrets.dir", SECRETS_DIR::toString);
        // UC-63 — the host shell IS the system under test here: enabled, pointed at
        // a socket whose parent dir is ABSENT (the fresh-install gap).
        r.add("ai-sandbox.server.server-ssh.enabled", () -> true);
        r.add("ai-sandbox.server.server-ssh.socket-path", SERVER_SSH_SOCKET::toString);
        r.add("ai-sandbox.server.server-ssh.session-name", () -> SERVER_SSH_NAME);
        r.add("ai-sandbox.server.server-ssh.workdir", HOST_STATE_ROOT::toString);
        r.add("server.port", () -> 0);
        r.add("server.shutdown", () -> "immediate");
        r.add("ai-sandbox.server.shutdown.rest-grace-seconds", () -> 1);
        r.add("ai-sandbox.server.shutdown.total-grace-seconds", () -> 2);
    }

    @TestConfiguration
    static class TestSeamsConfig {

        /**
         * Real tmux, stubbed Docker. {@code tmux} argv is delegated to a real
         * {@link ProcessExecutor} (the host shell genuinely comes up); every other
         * argv (the Docker-enumeration chain) returns an empty {@code []} success so
         * a Docker-less CI host enumerates zero container sessions instead of
         * erroring. Only the 4-arg overload needs overriding — the 3-arg overload
         * delegates to it.
         */
        @Bean
        @Primary
        ProcessExecutor tmuxRealDockerStubbedExecutor() {
            return new ProcessExecutor() {
                @Override
                public ProcessExecutor.Result run(
                        List<String> argv, Path workingDir, Map<String, String> env, Duration timeout)
                        throws IOException {
                    if (argv != null && !argv.isEmpty() && "tmux".equals(argv.get(0))) {
                        return super.run(argv, workingDir, env, timeout); // REAL host tmux
                    }
                    // Docker enumeration → zero container sessions (no Docker on CI).
                    return new ProcessExecutor.Result(0, "[]", "");
                }
            };
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter testIdentityStuffer() {
            return (exchange, chain) -> {
                exchange.getAttributes()
                        .put(
                                ClientIdentityExtractor.ATTR,
                                new ClientIdentity("uc63-freshinstall-client", "deadbeef".repeat(8), BigInteger.ONE));
                return chain.filter(exchange);
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    SessionRegistryService registry;

    @Test
    void fresh_install_post_server_ssh_creates_dir_lists_row_and_removes_1011_precondition() throws Exception {
        assumeTrue(tmuxAvailable(), "host tmux not available — skipping UC-63 live fresh-install verification");

        // Capture the honest "Created server-ssh host tmux" INFO log.
        ListAppender<ILoggingEvent> logs = attachAppender(HostShellSessionService.class);

        // (1) Fresh-install precondition — the socket-parent dir is ABSENT.
        assertThat(Files.exists(SERVER_SSH_SOCKET_PARENT))
                .as(
                        "precondition — the server-ssh socket-parent dir MUST be absent before the request "
                                + "(the exact fresh-install gap: %s)",
                        SERVER_SSH_SOCKET_PARENT)
                .isFalse();

        WebTestClient client = buildClient(port);
        registry.invalidate();

        // (2) POST /v1/sessions/server-ssh → 200 with type=server-ssh, n=0
        //     (AC3/AC4 — an HONEST 200: previously a false 200 with no tmux).
        byte[] postBody = client.post()
                .uri("/v1/sessions/server-ssh")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(postBody).isNotNull().isNotEmpty();
        JsonNode created = new ObjectMapper().readTree(postBody);
        assertThat(created.path("type").asText())
                .as("AC4 — POST /v1/sessions/server-ssh returns the server-ssh row type")
                .isEqualTo(SpecialSessions.TYPE_SERVER_SSH);
        assertThat(created.path("n").asInt())
                .as("AC6 — the reserved server-ssh id")
                .isEqualTo(SpecialSessions.SERVER_SSH_N);

        // (3) AC1 — the missing socket-parent dir was auto-created.
        assertThat(Files.isDirectory(SERVER_SSH_SOCKET_PARENT))
                .as("AC1 — ensureCreated auto-created the missing socket-parent dir on a fresh install")
                .isTrue();

        // (4) AC2/AC4 — a REAL host tmux now exists on the socket (no exit-code lie).
        assertThat(rawHasSession())
                .as("AC2/AC4 — a live host tmux genuinely exists on the auto-created socket "
                        + "(raw `tmux -S <sock> has-session` exit 0)")
                .isTrue();

        // (5) AC4 — GET /v1/sessions lists the pinned server-ssh row that used to be
        //     MISSING (the bug: POST→200 but GET→[]).
        registry.invalidate();
        byte[] listBody = client.get()
                .uri("/v1/sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(listBody).isNotNull().isNotEmpty();
        JsonNode list = new ObjectMapper().readTree(listBody);
        assertThat(list.isArray())
                .as("GET /v1/sessions MUST return a JSON array")
                .isTrue();
        JsonNode serverSshRow = null;
        for (JsonNode row : list) {
            if (row.path("n").asInt() == SpecialSessions.SERVER_SSH_N) {
                serverSshRow = row;
                break;
            }
        }
        assertThat(serverSshRow)
                .as("AC4 — GET /v1/sessions MUST list the pinned server-ssh row (was [] before the fix)")
                .isNotNull();
        assertThat(serverSshRow.path("type").asText()).isEqualTo(SpecialSessions.TYPE_SERVER_SSH);
        assertThat(serverSshRow.path("state").asText())
                .as("AC4 — the host-shell row is running once the tmux exists")
                .isEqualTo("running");

        // (6) The 1011 root cause is removed: StreamFacade.openStream's pre-bridge
        //     findSession(0) gate (over registry.list()) is now satisfied with a
        //     running row, so it can no longer throw
        //     `NoSuchElementException: session 0 disappeared during open` → 1011.
        registry.invalidate();
        SessionRecord gate = registry.list().stream()
                .filter(s -> s.n() == SpecialSessions.SERVER_SSH_N)
                .findFirst()
                .orElse(null);
        assertThat(gate)
                .as("the StreamFacade.openStream findSession(0) gate is now satisfied — the 1011 "
                        + "(`session 0 disappeared during open`) precondition is eliminated")
                .isNotNull();
        assertThat(gate.state()).isEqualTo("running");

        // (7) The honest create log fired (only logged AFTER the post-create probe).
        assertThat(logs.list)
                .as("the honest `Created server-ssh host tmux` INFO log fires only after the presence probe")
                .anyMatch(e ->
                        e.getLevel() == Level.INFO && e.getFormattedMessage().contains("Created server-ssh host tmux"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private boolean rawHasSession() throws IOException {
        return new ProcessExecutor()
                        .run(
                                List.of(
                                        "tmux",
                                        "-S",
                                        SERVER_SSH_SOCKET.toString(),
                                        "has-session",
                                        "-t",
                                        SERVER_SSH_NAME),
                                null,
                                TMUX_TIMEOUT)
                        .exitCode()
                == 0;
    }

    private static boolean tmuxAvailable() {
        try {
            return new ProcessExecutor()
                            .run(List.of("tmux", "-V"), null, Duration.ofSeconds(5))
                            .exitCode()
                    == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> type) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
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

    private static WebTestClient buildClient(int port) throws Exception {
        SslContext ssl = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        HttpClient http = HttpClient.create().secure(spec -> spec.sslContext(ssl));
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(http))
                .baseUrl("https://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }
}
