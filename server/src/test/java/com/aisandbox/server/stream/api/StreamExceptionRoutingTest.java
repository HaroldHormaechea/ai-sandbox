package com.aisandbox.server.stream.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.stream.facade.StreamFacade;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * UC-12 § AC5 — real-context exception-routing regression guard for
 * {@link StreamFacade.SessionNotRunningException}.
 *
 * <h2>Scope clarification — synthetic REST endpoint</h2>
 *
 * <p>{@link StreamFacade.SessionNotRunningException} is thrown by
 * {@link StreamFacade}{@code .openStream(...)} at the WebSocket-handshake
 * layer (see {@code SessionStreamHandler:149}), NOT from a REST
 * controller. To exercise the
 * {@link StreamProblemDetailsAdvice} (a {@code @RestControllerAdvice})
 * via the SAME WebFlux dispatcher path that
 * {@link EnrollmentExceptionRoutingTest} exercises, the harness wires
 * a TEST-ONLY {@link RestController} that throws the same exception
 * type on a synthetic {@code GET /v1/test-only/stream-not-running}
 * endpoint. The synthetic controller is a stand-in for "a future REST
 * customer of this exception" — it is NOT a model of the production
 * thrower path, which today lives strictly inside the WebSocket
 * handler. The pre-fix vs. post-fix routing of the exception through
 * the controller-advice / WebExceptionHandler chain is the bug under
 * test; whether the production WebSocket handler eventually feeds
 * REST customers is a separate concern.
 *
 * <h2>Test-first cascade — UC-12 § AC5 EMPIRICAL BRANCH</h2>
 *
 * <p>This test is the §3 EMPIRICAL BRANCH gate. The analyst's
 * proposal accepted that the enrollment advice was broken under
 * WebFlux but called the stream advice "almost certainly broken" with
 * no empirical proof. This test PROVES it either way:
 *
 * <ul>
 *   <li><b>If this test FAILS on current {@code main}</b> with HTTP
 *       500 + {@code "Unmapped exception in REST flow"} WARN +
 *       {@code "ServerHttpResponse already committed"} ERROR —
 *       STREAM-SIDE BUG CONFIRMED. The developer lands items 3+4
 *       of the proposal (delete {@link StreamProblemDetailsAdvice},
 *       add {@code StreamWebExceptionHandler}).</li>
 *   <li><b>If this test PASSES on current {@code main}</b> —
 *       STREAM-SIDE BUG ABSENT. The developer DROPS items 3+4 and
 *       records the differential in the PR body.</li>
 * </ul>
 *
 * <p>Either way this test ships as the regression guard for whichever
 * code shape ends up at post-fix HEAD — it is the contract pin that
 * the documented {@code 409 session_not_running} envelope reaches the
 * client when this exception is thrown from a REST controller, never
 * mind which production handler fires it.
 *
 * <h2>mTLS bypass</h2>
 *
 * <p>The synthetic endpoint is NOT mTLS-exempt (only
 * {@code /v1/enrollment} is, per {@code MtlsEnforcementFilter}). To
 * avoid the client-cert dance, the test harness registers a
 * {@link WebFilter} at {@link Ordered#HIGHEST_PRECEDENCE} that pre-
 * stashes a non-anonymous {@link ClientIdentity} into the exchange
 * attributes; {@link ClientIdentityExtractor} honours the pre-stashed
 * value, and {@code MtlsEnforcementFilter} admits the request because
 * a real identity is now present. This is identical to what a real
 * client cert would produce downstream of the TLS handshake, just
 * minus the cryptographic ceremony — appropriate because what we
 * exercise here is the EXCEPTION DISPATCH chain, not the L5 mTLS
 * gate (covered by {@code MtlsDispatchTest} and friends).
 *
 * <h2>Hard prohibitions (UC-12 § AC3, transitively for sibling
 * coverage)</h2>
 *
 * <p>NO {@code MockServerWebExchange}, NO
 * {@code WebTestClient.bindToController}, NO {@code WebFilter}-adapter
 * shortcuts for the exception handler under test. The
 * identity-stuffer above is an mTLS-gate workaround, not an
 * exception-handler shortcut — it stops short of the advice chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamExceptionRoutingTest {

    private static final Path ROOT;
    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-uc12-stream-routing-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc12-stream-routing");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc12-stream-bootstrap-client");

            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));

            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

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
                            "ai-sandbox-uc12-stream-routing-cleanup"));
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

    /**
     * Test-only configuration that (a) exposes the synthetic
     * {@code GET /v1/test-only/stream-not-running} REST endpoint and
     * (b) pre-stashes a non-anonymous {@link ClientIdentity} so the
     * {@code MtlsEnforcementFilter} admits the request without a real
     * client cert. See class Javadoc § "mTLS bypass".
     */
    @TestConfiguration
    static class StreamRoutingTestConfig {

        @Bean
        SyntheticStreamThrowerController syntheticStreamThrowerController() {
            return new SyntheticStreamThrowerController();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter testIdentityStuffer() {
            return (exchange, chain) -> {
                exchange.getAttributes()
                        .put(
                                ClientIdentityExtractor.ATTR,
                                new ClientIdentity("uc12-test-client", "deadbeef".repeat(8), BigInteger.ONE));
                return chain.filter(exchange);
            };
        }
    }

    /**
     * Synthetic REST endpoint that throws
     * {@link StreamFacade.SessionNotRunningException}. The exception
     * type is exactly the one
     * {@link StreamFacade}{@code .openStream(...)} throws (and
     * {@link StreamProblemDetailsAdvice} maps); the synthetic origin
     * is a stand-in for "any future REST customer of this exception"
     * — class Javadoc § "Scope clarification" makes the bound
     * explicit.
     */
    @RestController
    @RequestMapping("/v1/test-only")
    static class SyntheticStreamThrowerController {

        @GetMapping("/stream-not-running")
        public Mono<String> throwSessionNotRunning() {
            throw new StreamFacade.SessionNotRunningException(7, "stopped");
        }
    }

    @LocalServerPort
    int port;

    private ListAppender<ILoggingEvent> problemDetailsLogAppender;
    private Logger problemDetailsLogger;
    private Level priorLevel;

    @BeforeEach
    void attachLogAppender() {
        problemDetailsLogger = (Logger) LoggerFactory.getLogger(ProblemDetailsAdvice.class);
        priorLevel = problemDetailsLogger.getLevel();
        problemDetailsLogAppender = new ListAppender<>();
        problemDetailsLogAppender.setContext(problemDetailsLogger.getLoggerContext());
        problemDetailsLogAppender.start();
        problemDetailsLogger.addAppender(problemDetailsLogAppender);
        problemDetailsLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachLogAppender() {
        if (problemDetailsLogger != null && problemDetailsLogAppender != null) {
            problemDetailsLogger.detachAppender(problemDetailsLogAppender);
            problemDetailsLogAppender.stop();
            problemDetailsLogger.setLevel(priorLevel);
        }
    }

    @Test
    void session_not_running_exception_routes_to_409_problem_json_with_n_and_state() throws Exception {
        WebTestClient client = buildClient(port);

        byte[] respBody = client.get()
                .uri("/v1/test-only/stream-not-running")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(respBody).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(respBody);
        assertThat(root.path("status").asInt(-1)).isEqualTo(409);
        assertThat(root.path("code").asText(null)).isEqualTo("session_not_running");
        assertThat(root.path("n").asInt(-1))
                .as("UC-04 § AC37 — `n` carries the offending session number")
                .isEqualTo(7);
        assertThat(root.path("state").asText(null))
                .as("UC-04 § AC37 — `state` carries the not-running container state")
                .isEqualTo("stopped");
        // Flat shape regression guard.
        assertThat(root.path("properties").path("code").asText(null))
                .as("regression guard: `code` MUST NOT nest under `properties`")
                .isNull();

        assertThat(problemDetailsLogAppender.list)
                .as("UC-12 § AC5 — 'Unmapped exception in REST flow' MUST NOT fire for SessionNotRunningException")
                .noneSatisfy(evt -> assertThat(evt.getFormattedMessage()).contains("Unmapped exception in REST flow"));
    }

    // ── WebTestClient over the random TLS port ─────────────────────────

    private static WebTestClient buildClient(int port) throws Exception {
        SslContext ssl = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        HttpClient http = HttpClient.create().secure(spec -> spec.sslContext(ssl));
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(http))
                .baseUrl("https://127.0.0.1:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    // ── helpers (filesystem scaffolding) ───────────────────────────────

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
}
