package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * AC19 / BUG 2 (session-create-delete-fix) — true end-to-end
 * create → list → delete contract over real HTTPS against a booted
 * {@code @SpringBootTest(RANDOM_PORT)} Netty+TLS context.
 *
 * <h2>Why a {@code *Test} (not {@code *IT}) in the integration package</h2>
 *
 * <p>{@code server/build.gradle.kts} routes {@code **&#47;*IT.class} to the
 * separate {@code integrationTest} task, which is {@code enabled = false}
 * unless {@code AI_SANDBOX_DIND=1}. The user's hard requirement is a
 * true create→list→delete that runs in NORMAL CI (the ungated
 * {@code :server:test} lane). Because this variant mocks only the
 * subprocess seam — it needs no real Docker — it is named {@code *Test}
 * so {@code :server:test} discovers and runs it unconditionally. The
 * {@code AI_SANDBOX_DIND}-gated real-script tier stays in
 * {@link SessionsRestIT}.
 *
 * <h2>Injection seam = {@link ProcessExecutor}</h2>
 *
 * <p>A {@code @TestConfiguration} provides a {@code @Bean @Primary} mock
 * {@link ProcessExecutor} that stubs every subprocess at the argv layer
 * (spawn.sh / clean.sh / {@code docker …} are faked) while the ENTIRE
 * real business stack runs unmocked:
 *
 * <pre>
 *   SessionController
 *     → SessionFacade        (spawn mutex, parseAssignedN, per-N lock,
 *                             BUG 2 existence gate)
 *       → ScriptExecutorService
 *         → SessionRegistryService (1-second cache, exists())
 *           → DockerEnumerationService (compose-ls / docker-ps parsing)
 *             → ProcessExecutor   ← the ONLY mocked seam
 * </pre>
 *
 * <p>Mocking at {@code ProcessExecutor} (rather than at
 * {@code ScriptExecutorService}) is deliberately higher fidelity — it
 * exercises Fix C's real {@code registry.exists()} path through the
 * enumeration + cache code, not a stubbed boolean.
 *
 * <h2>mTLS gate</h2>
 *
 * <p>A {@link WebFilter} at {@link Ordered#HIGHEST_PRECEDENCE}
 * pre-stashes a non-anonymous {@link ClientIdentity} so {@code
 * MtlsEnforcementFilter} admits the request without a real client cert
 * (the same pattern as
 * {@link com.aisandbox.server.api.SessionEnumerationFailureControllerTest}
 * / {@code StreamExceptionRoutingTest}).
 *
 * <h2>Round trip</h2>
 *
 * <ol>
 *   <li>POST /v1/sessions — stub spawn.sh argv → exit 0 + stdout
 *       {@code ai-sandbox-3} ⇒ 201 {@code {n:3}}.</li>
 *   <li>GET /v1/sessions — stub enumeration → project 3 running ⇒ 200,
 *       a JSON array with {@code n:3}.</li>
 *   <li>DELETE /v1/sessions/3 — real {@code exists(3)==true} (the
 *       enumeration stub still reports project 3) ⇒ stub clean.sh exit 0
 *       ⇒ 204.</li>
 *   <li>DELETE /v1/sessions/99 — real {@code exists(99)==false} ⇒ 404
 *       {@code session_not_found}; clean.sh NEVER invoked for 99.</li>
 * </ol>
 *
 * <p>An enumeration-outage delete (enumeration stub throws ⇒ 5xx ≠ 404)
 * lives in its own test method so its conflicting stub setup doesn't
 * disturb the happy round trip.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionsRestRoundTripTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-bug2-sessions-roundtrip-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "bug2-sessions-roundtrip");
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
                            "ai-sandbox-bug2-sessions-roundtrip-cleanup"));
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
                                new ClientIdentity("bug2-roundtrip-client", "deadbeef".repeat(8), BigInteger.ONE));
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
    void resetSeams() throws Exception {
        reset(executor);
        registry.invalidate();
        resetStickyFlag(enumeration);
    }

    /**
     * The full create → list → delete → delete-absent contract over real
     * HTTPS with only the {@link ProcessExecutor} subprocess seam mocked.
     */
    @Test
    void create_list_delete_round_trip() throws Exception {
        stubSpawnReturnsProject3();
        stubEnumerationReportsProject3Running();
        stubCleanExitZero();

        WebTestClient client = buildClient(port);

        // 1. POST /v1/sessions → 201 {n:3} (parseAssignedN lifts 3 from
        //    the spawn.sh stdout "ai-sandbox-3 ready").
        byte[] spawnBody = client.post()
                .uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"label\":\"it-roundtrip\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(spawnBody).isNotNull().isNotEmpty();
        JsonNode spawned = new ObjectMapper().readTree(spawnBody);
        assertThat(spawned.path("n").asInt())
                .as("POST /v1/sessions MUST report the assigned N parsed from spawn.sh output")
                .isEqualTo(3);

        // 2. GET /v1/sessions → 200 [ {n:3, state:running} ].
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
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("n").asInt()).isEqualTo(3);
        assertThat(list.get(0).path("state").asText()).isEqualTo("running");

        // 3. DELETE /v1/sessions/3 → 204 (real exists(3)==true, clean exit 0).
        client.delete().uri("/v1/sessions/3").exchange().expectStatus().isNoContent();

        // 4. DELETE /v1/sessions/99 → 404 session_not_found (real
        //    exists(99)==false). clean.sh MUST NOT be invoked for 99.
        byte[] notFoundBody = client.delete()
                .uri("/v1/sessions/99")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(notFoundBody).isNotNull().isNotEmpty();
        JsonNode nf = new ObjectMapper().readTree(notFoundBody);
        assertThat(nf.path("code").asText())
                .as("BUG 2 — absent N (force=false) MUST be session_not_found")
                .isEqualTo("session_not_found");

        // clean.sh ran exactly for session 3 and never for 99.
        org.mockito.Mockito.verify(executor)
                .run(argThat(SessionsRestRoundTripTest::isCleanArgvForSession3), any(), any(), any());
        org.mockito.Mockito.verify(executor, never())
                .run(argThat(SessionsRestRoundTripTest::isCleanArgvForSession99), any(), any(), any());
    }

    /**
     * BUG 2 — a delete issued while session enumeration is DOWN (the
     * {@code docker compose ls} subprocess THROWS, so {@code
     * registry.exists} cannot answer) MUST surface as a 5xx, never a
     * false 404. The IOException propagates through {@code
     * registry.exists} → {@code SessionFacade.deleteSession} → the
     * generic fallback as a 500.
     */
    @Test
    void delete_during_enumeration_outage_is_5xx_not_404() throws Exception {
        when(executor.run(argThat(SessionsRestRoundTripTest::isComposeLsArgv), any(), any(), any()))
                .thenThrow(new IOException("docker daemon unreachable"));

        WebTestClient client = buildClient(port);

        int status = client.delete()
                .uri("/v1/sessions/3")
                .exchange()
                .returnResult(byte[].class)
                .getStatus()
                .value();

        assertThat(status)
                .as("BUG 2 — enumeration outage on delete MUST be a 5xx (never a false 404)")
                .isGreaterThanOrEqualTo(500)
                .isLessThan(600);
        assertThat(status).isNotEqualTo(404);

        // clean.sh must NOT have run — the gate failed before reaching it.
        org.mockito.Mockito.verify(executor, never())
                .run(argThat(SessionsRestRoundTripTest::isAnyCleanArgv), any(), any(), any());
    }

    // ── stub helpers ─────────────────────────────────────────────────────

    /** spawn.sh argv → exit 0 + stdout containing the assigned project tag. */
    private void stubSpawnReturnsProject3() throws IOException {
        when(executor.run(argThat(SessionsRestRoundTripTest::isSpawnArgv), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "ai-sandbox-3 ready", ""));
    }

    /** clean.sh argv → exit 0 (successful teardown). */
    private void stubCleanExitZero() throws IOException {
        when(executor.run(argThat(SessionsRestRoundTripTest::isAnyCleanArgv), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
    }

    /**
     * Stub the full enumeration chain so {@code registry.list()} / {@code
     * registry.exists()} report exactly one running project (N=3) on the
     * modern docker-compose path.
     */
    private void stubEnumerationReportsProject3Running() throws IOException {
        // docker compose ls --all --format json  (4-arg overload)
        when(executor.run(argThat(SessionsRestRoundTripTest::isComposeLsArgv), any(), any(), any()))
                .thenReturn(
                        new ProcessExecutor.Result(0, "[ {\"Name\":\"ai-sandbox-3\",\"Status\":\"running(1)\"} ]", ""));
        // docker compose -p ai-sandbox-3 ps -q --all claude-sandbox  (4-arg)
        when(executor.run(argThat(SessionsRestRoundTripTest::isContainerIdArgv), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "cid3\n", ""));
        // docker inspect --format '…|…|…' cid  (3-arg overload)
        when(executor.run(argThat(SessionsRestRoundTripTest::isInspectArgv), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "it-roundtrip|running|true", ""));
        // UC-27 — docker compose -p … exec -T claude-sandbox test -f
        // /tmp/aisandbox-ready  (3-arg overload). enumerate() now probes the
        // readiness marker for running sessions; stub it present (exit 0) so
        // the session reports `running` rather than `provisioning`. Without
        // this stub the @Primary mock returns null → NPE → HTTP 500.
        when(executor.run(argThat(SessionsRestRoundTripTest::isReadyMarkerArgv), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "", ""));
        // docker compose -p … exec … tmux display-message …  (3-arg overload)
        when(executor.run(argThat(SessionsRestRoundTripTest::isDisplayMessageArgv), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "doing-thing", ""));
    }

    // ── argv predicates ──────────────────────────────────────────────────

    private static boolean isSpawnArgv(List<String> argv) {
        return argv != null && !argv.isEmpty() && argv.get(0).endsWith("spawn.sh");
    }

    private static boolean isAnyCleanArgv(List<String> argv) {
        return argv != null && !argv.isEmpty() && argv.get(0).endsWith("clean.sh");
    }

    private static boolean isCleanArgvForSession3(List<String> argv) {
        return isAnyCleanArgv(argv) && argv.contains("3");
    }

    private static boolean isCleanArgvForSession99(List<String> argv) {
        return isAnyCleanArgv(argv) && argv.contains("99");
    }

    private static boolean isComposeLsArgv(List<String> argv) {
        return argv != null && argv.size() >= 3 && "ls".equals(argv.get(2));
    }

    private static boolean isContainerIdArgv(List<String> argv) {
        return argv != null && argv.contains("compose") && argv.contains("ps") && argv.contains("-q");
    }

    private static boolean isInspectArgv(List<String> argv) {
        return argv != null && argv.contains("inspect");
    }

    private static boolean isReadyMarkerArgv(List<String> argv) {
        return argv != null && argv.contains("test") && argv.contains("/tmp/aisandbox-ready");
    }

    private static boolean isDisplayMessageArgv(List<String> argv) {
        return argv != null && argv.contains("display-message");
    }

    // ── infra helpers ─────────────────────────────────────────────────────

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
}
