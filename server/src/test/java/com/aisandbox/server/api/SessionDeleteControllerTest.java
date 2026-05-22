package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.sessions.facade.SessionFacade;
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
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.netty.http.client.HttpClient;

/**
 * BUG 2 (session-create-delete-fix) — controller-level pin for the
 * {@code DELETE /v1/sessions/{n}} HTTP status mapping, exercised against
 * a real {@code @SpringBootTest(RANDOM_PORT)} Netty+TLS context so the
 * production {@code ProblemDetailsAdvice} / {@code
 * GenericProblemFallbackHandler} translation runs for real (the same
 * harness as {@link SessionEnumerationFailureControllerTest}).
 *
 * <p>The seam here is a {@code @Primary} Mockito mock of
 * {@link SessionFacade}: this test isolates the controller's
 * exception→status mapping, NOT the facade's existence-gate logic
 * (that is pinned at the unit layer by
 * {@link com.aisandbox.server.sessions.SessionFacadeTest} and end-to-end
 * by {@link com.aisandbox.server.integration.SessionsRestIT}). The three
 * controller contracts:
 *
 * <ul>
 *   <li>facade throws {@link NoSuchElementException} (absent N,
 *       force=false) → HTTP 404 {@code session_not_found}.</li>
 *   <li>facade returns {@code false} (clean.sh ran but exited non-zero)
 *       → HTTP 500 {@code internal_error}.</li>
 *   <li>facade throws {@link IOException} (enumeration outage) → a 5xx
 *       (500 {@code internal_error} via the generic fallback), and
 *       crucially NOT a 404 — an outage is "unknown", never "absent".</li>
 * </ul>
 *
 * <p>The {@link WebFilter} at {@link Ordered#HIGHEST_PRECEDENCE}
 * pre-stashes a non-anonymous {@link ClientIdentity} so {@code
 * MtlsEnforcementFilter} admits the request without a real client cert —
 * identical mTLS-bypass shape to
 * {@link com.aisandbox.server.stream.api.StreamExceptionRoutingTest} and
 * {@link SessionEnumerationFailureControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionDeleteControllerTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-bug2-delete-ctrl-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "bug2-delete-ctrl");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "bug2-bootstrap-client");

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
                            "ai-sandbox-bug2-delete-ctrl-cleanup"));
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
     * Replaces the production {@link SessionFacade} {@code @Component}
     * with a Mockito mock ({@code @Primary} so the controller's
     * constructor-autowire picks it) and pre-stashes a non-anonymous
     * {@link ClientIdentity} so the request clears the mTLS gate.
     */
    @TestConfiguration
    static class TestSeamsConfig {

        @Bean
        @Primary
        SessionFacade mockSessionFacade() {
            return mock(SessionFacade.class);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter testIdentityStuffer() {
            return (exchange, chain) -> {
                exchange.getAttributes()
                        .put(
                                ClientIdentityExtractor.ATTR,
                                new ClientIdentity("bug2-delete-client", "deadbeef".repeat(8), BigInteger.ONE));
                return chain.filter(exchange);
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    SessionFacade facade; // the @Primary mock from TestSeamsConfig

    @BeforeEach
    void resetMock() {
        reset(facade);
    }

    /**
     * BUG 2 — DELETE of an absent session with the default force=false
     * MUST be a 404 {@code session_not_found} (the facade's existence
     * gate throws {@link NoSuchElementException}, mapped by
     * {@code ProblemDetailsAdvice.handleNotFound}). Pre-fix this surfaced
     * as a 500 because {@code clean.sh}'s exit-1 was treated as a failure.
     */
    @Test
    void delete_absent_session_force_false_is_404_session_not_found() throws Exception {
        when(facade.deleteSession(eq(99), eq(false))).thenThrow(new NoSuchElementException("session 99 not found"));

        WebTestClient client = buildClient(port);

        byte[] body = client.delete()
                .uri("/v1/sessions/99")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.path("code").asText())
                .as("BUG 2 — absent N (force=false) MUST be session_not_found, not internal_error")
                .isEqualTo("session_not_found");
        assertThat(root.path("status").asInt()).isEqualTo(404);
    }

    /**
     * A genuine {@code clean.sh} failure (the script ran and exited
     * non-zero → facade returns {@code false}) MUST surface as 500
     * {@code internal_error}. This is the contract the 404 path
     * deliberately narrowed 500 down to.
     */
    @Test
    void delete_clean_failure_is_500_internal_error() throws Exception {
        when(facade.deleteSession(eq(3), eq(false))).thenReturn(false);

        WebTestClient client = buildClient(port);

        byte[] body = client.delete()
                .uri("/v1/sessions/3")
                .exchange()
                .expectStatus()
                .is5xxServerError()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.path("status").asInt()).isEqualTo(500);
        assertThat(root.path("code").asText())
                .as("clean.sh non-zero exit MUST be internal_error")
                .isEqualTo("internal_error");
    }

    /**
     * BUG 2 — an enumeration OUTAGE (facade's {@code registry.exists}
     * throws {@link IOException}, force=false) MUST surface as a 5xx via
     * the generic fallback and MUST NOT be downgraded to a 404. A 404 on
     * an outage would be a false "session doesn't exist".
     */
    @Test
    void delete_enumeration_outage_force_false_is_5xx_not_404() throws Exception {
        when(facade.deleteSession(eq(7), eq(false))).thenThrow(new IOException("docker enumeration unavailable"));

        WebTestClient client = buildClient(port);

        int status = client.delete()
                .uri("/v1/sessions/7")
                .exchange()
                .returnResult(byte[].class)
                .getStatus()
                .value();

        assertThat(status)
                .as("BUG 2 — enumeration outage MUST be a 5xx (never a false 404)")
                .isGreaterThanOrEqualTo(500)
                .isLessThan(600);
        assertThat(status).isNotEqualTo(404);
    }

    /**
     * force=true is the operator escape hatch — it must reach the facade
     * with {@code force=true} and, on a successful clean (facade returns
     * true), produce a 204. Guards that the {@code ?force=true} query
     * param is parsed and forwarded.
     */
    @Test
    void delete_force_true_returns_204_and_forwards_force_flag() throws Exception {
        when(facade.deleteSession(eq(5), eq(true))).thenReturn(true);

        WebTestClient client = buildClient(port);

        client.delete()
                .uri("/v1/sessions/5?force=true")
                .exchange()
                .expectStatus()
                .isNoContent();

        verify(facade).deleteSession(eq(5), eq(true));
        verify(facade, never()).deleteSession(anyInt(), eq(false));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static WebTestClient buildClient(int port) throws Exception {
        SslContext ssl = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        HttpClient http = HttpClient.create().secure(spec -> spec.sslContext(ssl));
        return WebTestClient.bindToServer(new ReactorClientHttpConnector(http))
                .baseUrl("https://127.0.0.1:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(15))
                .build();
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
}
