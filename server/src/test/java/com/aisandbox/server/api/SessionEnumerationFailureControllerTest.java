package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.sessions.service.DockerEnumerationService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.test.CertFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.netty.http.client.HttpClient;

/**
 * UC-15 AC1 / AC4 / AC6 — real-context regression guard for
 * {@code GET /v1/sessions} under both docker-compose binaries:
 *
 * <ol>
 *   <li>A runner whose binary REJECTS {@code docker compose ls --all}
 *       (exit=125, stderr {@code "unknown flag: --all"} — the literal
 *       signature observed on potato-server, 2026-05-21 evening) MUST
 *       fall back to {@code docker ps --filter
 *       label=com.docker.compose.project} and surface a populated
 *       JSON array. Pre-UC-15 this same input produced HTTP 200 with
 *       an empty array (and a noisy WARN line) because the
 *       compose-ls call's exit-125 path returned {@code List.of()}.</li>
 *   <li>A runner whose binary SUPPORTS {@code docker compose ls --all}
 *       MUST behave as before — the modern argv is the only path
 *       exercised, no fallback {@code docker ps} probe is issued, and
 *       the response is a populated JSON array.</li>
 * </ol>
 *
 * <p>The test boots a real {@code @SpringBootTest(RANDOM_PORT)} context
 * — same harness as
 * {@link com.aisandbox.server.enrollment.api.EnrollmentExceptionRoutingTest}
 * and
 * {@link com.aisandbox.server.stream.api.StreamExceptionRoutingTest} —
 * with three test-only seams:
 *
 * <ul>
 *   <li>A {@code @Primary} Mockito mock of {@link ProcessExecutor}
 *       overrides the production {@code @Component} so every
 *       {@code docker} subprocess is stubbed at the argv layer.</li>
 *   <li>A {@link WebFilter} at {@link Ordered#HIGHEST_PRECEDENCE}
 *       pre-stashes a non-anonymous {@link ClientIdentity} into the
 *       exchange attributes so {@code MtlsEnforcementFilter} admits
 *       the request without a real client cert (identical pattern to
 *       {@link com.aisandbox.server.stream.api.StreamExceptionRoutingTest}'s
 *       {@code testIdentityStuffer}; this is the mTLS-gate workaround,
 *       NOT a shortcut around the production code under test).</li>
 *   <li>A {@link BeforeEach} that resets the {@link ProcessExecutor}
 *       mock, invalidates the
 *       {@link SessionRegistryService}'s 1-second cache, AND
 *       reflectively clears the
 *       {@link DockerEnumerationService}'s sticky
 *       {@code composeListAllSupported} flag so each test method
 *       starts from the unprobed initial state regardless of the prior
 *       test method's effect on the singleton service.</li>
 * </ul>
 *
 * <p>What this test specifically does NOT test: the production WebSocket
 * handler, the audit log, the spawn / clean paths, the
 * {@code SessionFacade} business logic — those are covered by their own
 * tests. The argv shape of the docker calls is covered by the unit-
 * level {@link com.aisandbox.server.sessions.DockerEnumerationServiceTest}
 * (UC-15 AC2 / AC6 service-level pin); this test sits one layer up at
 * the controller / response-shape boundary (AC1 / AC4 / AC6
 * controller-level pin).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionEnumerationFailureControllerTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-uc15-sessions-enum-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc15-sessions-enum");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc15-bootstrap-client");

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
                            "ai-sandbox-uc15-sessions-enum-cleanup"));
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
     * Replaces the production {@link ProcessExecutor} {@code @Component}
     * with a Mockito mock so every {@code docker} subprocess is stubbed
     * at the argv layer. {@code @Primary} so the constructor-autowired
     * {@link DockerEnumerationService} picks this bean.
     *
     * <p>Also pre-stashes a non-anonymous {@link ClientIdentity} via a
     * {@link Ordered#HIGHEST_PRECEDENCE} {@link WebFilter} — the same
     * mTLS-bypass shape used by
     * {@link com.aisandbox.server.stream.api.StreamExceptionRoutingTest}.
     * The mock is the seam this test cares about; the identity stuffer
     * is only there to let the request reach the controller.
     */
    @TestConfiguration
    static class TestSeamsConfig {

        @Bean
        @Primary
        ProcessExecutor mockProcessExecutor() {
            return mock(ProcessExecutor.class);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter testIdentityStuffer() {
            return (exchange, chain) -> {
                exchange.getAttributes()
                        .put(
                                ClientIdentityExtractor.ATTR,
                                new ClientIdentity("uc15-test-client", "deadbeef".repeat(8), BigInteger.ONE));
                return chain.filter(exchange);
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ProcessExecutor executor; // the @Primary mock from TestSeamsConfig

    @Autowired
    DockerEnumerationService enumeration;

    @Autowired
    SessionRegistryService registry;

    @BeforeEach
    void resetMockAndCachesAndStickyFlag() throws Exception {
        // Each test method starts from a clean slate. The singleton
        // service holds (a) the volatile composeListAllSupported flag
        // which is sticky across enumerate() calls AND across test
        // methods, and (b) the 1-second SessionRegistryService cache
        // which would otherwise serve the prior test's result.
        reset(executor);
        registry.invalidate();
        resetStickyFlag(enumeration);
    }

    /**
     * UC-15 AC1 / AC4 / AC6 — when the runner's docker-compose binary
     * rejects {@code --all} with the empirical signature (exit=125,
     * stderr {@code "unknown flag: --all"}), {@code GET /v1/sessions}
     * MUST still return HTTP 200 with a populated JSON array. The
     * fallback path through {@code docker ps -a --filter
     * label=com.docker.compose.project} is what makes this possible.
     *
     * <p>Pre-UC-15 this exact input produced HTTP 200 + an empty array
     * (the compose-ls exit-125 branch returned {@code List.of()}); the
     * test name reflects the POST-fix behaviour — the pre-fix narrative
     * lives in this Javadoc.
     */
    @Test
    void unknown_all_flag_routes_through_fallback_to_populated_json_array() throws Exception {
        // First argv: `docker compose ls --all --format json` → exit=125
        // + stderr "unknown flag: --all".
        when(executor.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(125, "", "unknown flag: --all\n"));

        // Second argv: `docker ps -a --format json --filter
        // label=com.docker.compose.project` (no -q) → exit=0 + NDJSON
        // with two ai-sandbox projects (4 and 7).
        String fallbackNdjson =
                """
                {"Names":"ai-sandbox-4-claude-sandbox-1","Labels":{"com.docker.compose.project":"ai-sandbox-4","com.docker.compose.service":"claude-sandbox"}}
                {"Names":"ai-sandbox-7-claude-sandbox-1","Labels":{"com.docker.compose.project":"ai-sandbox-7","com.docker.compose.service":"claude-sandbox"}}
                """;
        when(executor.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && "docker".equals(argv.get(0))
                                && "ps".equals(argv.get(1))
                                && argv.contains("--filter")
                                && !argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, fallbackNdjson, ""));

        // Third argv (per project): `docker ps -a -q --filter
        // label=com.docker.compose.project=<project>` → exit=0 + "cid\n".
        when(executor.run(
                        argThat(argv -> argv != null
                                && argv.size() >= 2
                                && "docker".equals(argv.get(0))
                                && "ps".equals(argv.get(1))
                                && argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));

        // Fourth argv (per project): `docker inspect --format ... cid`
        // (3-arg overload) → exit=0 + "label|running|true".
        when(executor.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "uc15-test|running|true", ""));

        // Fifth argv (per running project): `docker compose -p X exec ... tmux display-message`
        // (3-arg overload) → exit=0 + a non-idle title.
        when(executor.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        WebTestClient client = buildClient(port);

        byte[] respBody = client.get()
                .uri("/v1/sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(respBody).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(respBody);

        // AC1 / AC6 — populated JSON array (NOT the pre-fix empty array).
        assertThat(root.isArray())
                .as("UC-15 AC1 — GET /v1/sessions MUST return a JSON array")
                .isTrue();
        assertThat(root.size())
                .as("UC-15 AC1 — fallback enumeration MUST surface both ai-sandbox projects from the NDJSON")
                .isEqualTo(2);

        // Order: SessionRecord list is sorted by `n` in enumerate().
        assertThat(root.get(0).path("n").asInt()).isEqualTo(4);
        assertThat(root.get(1).path("n").asInt()).isEqualTo(7);
        // Each row carries the running state + label from the
        // inspect-combined output.
        for (JsonNode row : (Iterable<JsonNode>) root::elements) {
            assertThat(row.path("state").asText()).isEqualTo("running");
            assertThat(row.path("label").asText()).isEqualTo("uc15-test");
        }
    }

    /**
     * UC-15 AC4 — on a runner whose docker-compose binary speaks the
     * modern {@code --all} argv, the response is unchanged from the
     * pre-UC-15 contract: HTTP 200 + a populated JSON array, driven by
     * the {@code docker compose ls --all --format json} payload, with
     * no fallback {@code docker ps --filter} probe. Pins that UC-15
     * doesn't regress the happy path.
     */
    @Test
    void modern_compose_returns_populated_array_unchanged() throws Exception {
        String composeLsJson =
                """
                [
                  {"Name":"ai-sandbox-2","Status":"running(1)"},
                  {"Name":"ai-sandbox-3","Status":"running(1)"}
                ]
                """;
        when(executor.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, composeLsJson, ""));

        // Modern containerId argv: `docker compose -p X ps -q --all
        // claude-sandbox` → exit=0 + "cid\n".
        when(executor.run(
                        argThat(argv ->
                                argv != null && argv.contains("compose") && argv.contains("ps") && argv.contains("-q")),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid\n", ""));

        when(executor.run(argThat(argv -> argv != null && argv.contains("inspect")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "modern-label|running|true", ""));

        when(executor.run(argThat(argv -> argv != null && argv.contains("display-message")), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));

        WebTestClient client = buildClient(port);

        byte[] respBody = client.get()
                .uri("/v1/sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(respBody).isNotNull().isNotEmpty();
        JsonNode root = new ObjectMapper().readTree(respBody);

        assertThat(root.isArray())
                .as("UC-15 AC4 — modern docker-compose MUST still return a JSON array")
                .isTrue();
        assertThat(root.size())
                .as("UC-15 AC4 — modern docker-compose MUST surface both projects from the ls payload")
                .isEqualTo(2);
        assertThat(root.get(0).path("n").asInt()).isEqualTo(2);
        assertThat(root.get(1).path("n").asInt()).isEqualTo(3);
        for (JsonNode row : (Iterable<JsonNode>) root::elements) {
            assertThat(row.path("state").asText()).isEqualTo("running");
            assertThat(row.path("label").asText()).isEqualTo("modern-label");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Reflectively clears the {@code composeListAllSupported} volatile
     * field on the singleton {@link DockerEnumerationService} so each
     * test method starts from the unprobed state. The field is
     * package-private nullable {@link Boolean} — {@code null} means
     * "unknown, probe on next enumerate()".
     */
    private static void resetStickyFlag(DockerEnumerationService svc) throws Exception {
        Field f = DockerEnumerationService.class.getDeclaredField("composeListAllSupported");
        f.setAccessible(true);
        f.set(svc, null);
    }

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

    // Reference for IDE: ApiDtos.SessionSummary is the wire-shape we
    // assert on (kept as an import-driving reference; the JSON parses
    // the same payload).
    @SuppressWarnings("unused")
    private static List<?> _summaryShapeRef() {
        return List.of();
    }
}
