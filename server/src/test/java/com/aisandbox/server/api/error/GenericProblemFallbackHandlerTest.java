package com.aisandbox.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
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
 * UC-12 § AC4 + § 4 operational-signal guard — real-context test that
 * the GENERIC unmapped-exception fallback still produces a documented
 * {@code internal_error} Problem+JSON envelope AND still logs the
 * operational "Unmapped exception in REST flow" WARN line for
 * truly-unmapped throwables.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>UC-12 option (a) (the analyst's chosen fix shape) removes the
 * {@code @ExceptionHandler(Throwable.class)} catch-all from
 * {@link ProblemDetailsAdvice} and re-implements the generic
 * fallback as a separate {@code GenericProblemFallbackHandler}
 * {@link org.springframework.web.server.WebExceptionHandler} at
 * LOWEST precedence. Two contract points must NOT regress:
 *
 * <ol>
 *   <li><b>Body shape</b> — HTTP 500,
 *       {@code application/problem+json}, top-level {@code code:
 *       internal_error}.</li>
 *   <li><b>Audit trail</b> — the operational WARN line
 *       {@code "Unmapped exception in REST flow"} fires on the
 *       {@link ProblemDetailsAdvice} logger category. That line is the
 *       operational signal that a domain-specific handler is missing.
 *       UC-12 § 4 explicitly calls it out as the silent-regression
 *       canary: AC3 verifies the line is SILENT for the five
 *       enrollment exceptions, so if it stops firing for genuinely-
 *       unmapped exceptions too, the AC3 assertion would silently go
 *       vacuous.</li>
 * </ol>
 *
 * <h2>Test-first cascade</h2>
 *
 * <p>This test initially fails against current {@code main} because
 * {@code GenericProblemFallbackHandler} does NOT yet exist — the
 * generic catch-all still lives on {@link ProblemDetailsAdvice}. The
 * developer's option (a) implementation introduces the new handler
 * and routes through it; this test goes green WITHOUT a QA-side edit.
 *
 * <p>The body-shape + WARN-line assertions intentionally do NOT pin
 * the handler class name — they pin the wire contract and the
 * operational-signal logger category. The developer is free to land
 * option (a) as either a new handler bean OR by surfacing the
 * fallback via Spring's framework-default handler chain, as long as
 * both contract points hold.
 *
 * <h2>mTLS bypass</h2>
 *
 * <p>The synthetic endpoint is not mTLS-exempt; mirroring
 * {@link com.aisandbox.server.stream.api.StreamExceptionRoutingTest},
 * the test harness registers a {@link WebFilter} at
 * {@link Ordered#HIGHEST_PRECEDENCE} that pre-stashes a non-anonymous
 * {@link ClientIdentity} so the {@code MtlsEnforcementFilter} admits
 * the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenericProblemFallbackHandlerTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-uc12-fallback-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc12-fallback");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc12-fallback-bootstrap-client");

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
                            "ai-sandbox-uc12-fallback-cleanup"));
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

    @TestConfiguration
    static class FallbackRoutingTestConfig {

        @Bean
        SyntheticUnmappedThrowerController syntheticUnmappedThrowerController() {
            return new SyntheticUnmappedThrowerController();
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
     * Synthetic endpoint that throws a deliberately-unmapped
     * {@link RuntimeException}. The exception type is NOT any of:
     *
     * <ul>
     *   <li>{@link java.util.NoSuchElementException} (mapped to 404),</li>
     *   <li>{@link java.security.cert.CertificateException} (400),</li>
     *   <li>{@link IllegalArgumentException} (400),</li>
     *   <li>{@code DecodingException} / {@code ServerWebInputException}
     *       (400),</li>
     *   <li>{@code ResponseStatusException} (echoed),</li>
     *   <li>the five {@link com.aisandbox.server.enrollment.facade.EnrollmentFacade}
     *       exceptions (handled by
     *       {@code EnrollmentWebExceptionHandler}),</li>
     *   <li>{@link com.aisandbox.server.stream.facade.StreamFacade.SessionNotRunningException}
     *       (handled by {@code StreamProblemDetailsAdvice}).</li>
     * </ul>
     *
     * <p>so the ONLY handler that can claim it is the generic
     * unmapped-fallback under test.
     */
    @RestController
    @RequestMapping("/v1/test-only")
    static class SyntheticUnmappedThrowerController {

        @GetMapping("/unmapped-exception")
        public Mono<String> throwUnmapped() {
            throw new RuntimeException("not in any specific handler");
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
    void unmapped_runtime_exception_falls_through_to_500_internal_error_problem_json_and_emits_warn_line()
            throws Exception {
        WebTestClient client = buildClient(port);

        byte[] respBody = client.get()
                .uri("/v1/test-only/unmapped-exception")
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(respBody).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(respBody);
        assertThat(root.path("status").asInt(-1)).isEqualTo(500);
        assertThat(root.path("code").asText(null))
                .as("UC-12 § AC4 — top-level code MUST be `internal_error` for the generic fallback")
                .isEqualTo("internal_error");
        // Flat shape regression guard.
        assertThat(root.path("properties").path("code").asText(null))
                .as("regression guard: `code` MUST NOT nest under `properties`")
                .isNull();

        // UC-12 § 4 — the operational-signal log line still fires for
        // genuinely-unmapped exceptions. This is the canary that makes
        // the AC3 silence assertion non-vacuous.
        assertThat(problemDetailsLogAppender.list)
                .as("UC-12 § AC4 — the 'Unmapped exception in REST flow' WARN line MUST still fire for"
                        + " genuinely-unmapped exceptions; this is the operational signal that a"
                        + " domain-specific handler is missing.")
                .anySatisfy(evt -> {
                    assertThat(evt.getLevel()).isEqualTo(Level.WARN);
                    assertThat(evt.getFormattedMessage())
                            .contains("Unmapped exception in REST flow")
                            .contains("not in any specific handler");
                });
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
