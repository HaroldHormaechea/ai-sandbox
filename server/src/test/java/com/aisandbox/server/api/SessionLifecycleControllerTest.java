package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.sessions.dto.LifecycleAction;
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
 * UC-46 AC4 — controller-level pin for the {@code POST
 * /v1/sessions/{n}/{stop|start|pause|unpause}} HTTP status mapping, exercised
 * against a real {@code @SpringBootTest(RANDOM_PORT)} Netty+TLS context so the
 * production {@code ProblemDetailsAdvice} / {@code
 * GenericProblemFallbackHandler} translation runs for real. Mirrors {@link
 * SessionDeleteControllerTest}: a {@code @Primary} Mockito mock of {@link
 * SessionFacade} isolates the controller's exception→status mapping (the
 * facade's transition/mutex logic is pinned at the unit layer by {@link
 * com.aisandbox.server.sessions.SessionFacadeTest}).
 *
 * <p>Four controller contracts:
 *
 * <ul>
 *   <li>facade returns {@code true} (lifecycle.sh exited 0) → HTTP 204.</li>
 *   <li>facade returns {@code false} (lifecycle.sh ran but exited non-zero)
 *       → HTTP 500 {@code internal_error}.</li>
 *   <li>facade throws {@link NoSuchElementException} (absent N) → HTTP 404
 *       {@code session_not_found}.</li>
 *   <li>facade throws {@link SessionFacade.InvalidLifecycleTransitionException}
 *       (out-of-state action) → HTTP 409 {@code session_state_conflict},
 *       carrying {@code currentState}.</li>
 * </ul>
 *
 * <p>A bonus contract: an unmapped action token (e.g. {@code /restart}) 404s as
 * an unmatched path (the {@code @PostMapping} path regex constrains the
 * segment), and the facade is never consulted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionLifecycleControllerTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-uc46-lifecycle-ctrl-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc46-lifecycle-ctrl");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc46-bootstrap-client");

            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));
            // UC-46 — lifecycle.sh must exist + be executable for the locator /
            // startup check to admit the context.
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
                            "ai-sandbox-uc46-lifecycle-ctrl-cleanup"));
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
                                new ClientIdentity("uc46-lifecycle-client", "deadbeef".repeat(8), BigInteger.ONE));
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

    /** AC4 — a valid action whose lifecycle.sh exits 0 returns 204. */
    @Test
    void valid_action_returns_204() throws Exception {
        // The controller now passes the raw path token (String) to the facade,
        // which parses it via LifecycleAction.fromToken (keeps the api→dto
        // boundary clean — LayeringTest).
        when(facade.lifecycle(eq(5), eq("stop"))).thenReturn(true);

        WebTestClient client = buildClient(port);
        client.post().uri("/v1/sessions/5/stop").exchange().expectStatus().isNoContent();

        verify(facade).lifecycle(eq(5), eq("stop"));
    }

    /** AC4 — lifecycle.sh ran but exited non-zero → 500 internal_error. */
    @Test
    void script_nonzero_is_500_internal_error() throws Exception {
        when(facade.lifecycle(eq(3), eq("pause"))).thenReturn(false);

        WebTestClient client = buildClient(port);
        byte[] body = client.post()
                .uri("/v1/sessions/3/pause")
                .exchange()
                .expectStatus()
                .is5xxServerError()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.path("status").asInt()).isEqualTo(500);
        assertThat(root.path("code").asText()).isEqualTo("internal_error");
    }

    /** AC4 — an absent N → 404 session_not_found. */
    @Test
    void unknown_session_is_404_session_not_found() throws Exception {
        when(facade.lifecycle(eq(99), eq("start")))
                .thenThrow(new NoSuchElementException("session 99 not found"));

        WebTestClient client = buildClient(port);
        byte[] body = client.post()
                .uri("/v1/sessions/99/start")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.path("code").asText()).isEqualTo("session_not_found");
        assertThat(root.path("status").asInt()).isEqualTo(404);
    }

    /**
     * AC4 — an out-of-state action (e.g. START on a running session) → 409
     * session_state_conflict, carrying the current state so the client can
     * reconcile. Never a silent no-op.
     */
    @Test
    void invalid_transition_is_409_session_state_conflict() throws Exception {
        when(facade.lifecycle(eq(5), eq("start")))
                .thenThrow(new SessionFacade.InvalidLifecycleTransitionException(5, LifecycleAction.START, "running"));

        WebTestClient client = buildClient(port);
        byte[] body = client.post()
                .uri("/v1/sessions/5/start")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.path("code").asText())
                .as("an out-of-state lifecycle action MUST be session_state_conflict (409)")
                .isEqualTo("session_state_conflict");
        assertThat(root.path("status").asInt()).isEqualTo(409);
        assertThat(root.path("currentState").asText()).isEqualTo("running");
    }

    /**
     * The {@code @PostMapping} path regex constrains the action segment to
     * {@code stop|start|pause|unpause}; any other token (e.g. {@code restart})
     * 404s as an unmatched route and the facade is never consulted.
     */
    @Test
    void unmapped_action_token_404s_and_never_reaches_facade() throws Exception {
        WebTestClient client = buildClient(port);
        client.post().uri("/v1/sessions/5/restart").exchange().expectStatus().isNotFound();

        verify(facade, never()).lifecycle(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
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
