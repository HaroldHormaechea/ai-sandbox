package com.aisandbox.server.enrollment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import com.aisandbox.server.test.CertFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.http.client.HttpClient;

/**
 * UC-12 § AC3 + AC11 — real-context exception-routing regression
 * guard for {@code POST /v1/enrollment}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>UC-11 introduced {@link EnrollmentWebExceptionHandler} (a
 * reactive-aware {@link org.springframework.web.server.WebExceptionHandler}
 * bean) to map the five enrollment failure exceptions to their
 * documented Problem+JSON envelopes. UC-11's own integration test
 * passed because it drove the handler in isolation via {@link
 * org.springframework.mock.web.server.MockServerWebExchange}; the
 * real-bean-stack routing was never exercised. Production logs on
 * potato-server (2026-05-21 09:50 +02:00) then showed that
 * {@link ProblemDetailsAdvice#handleAny} — a
 * {@code @ExceptionHandler(Throwable.class)} on a
 * {@code @RestControllerAdvice} — was consuming each enrollment
 * exception FIRST (at the {@code RequestMappingHandlerAdapter} /
 * {@code InvocableHandlerMethod} layer), committing the response,
 * and then leaving the request to fall through as HTTP 500 with a
 * wrapped {@code ServerHttpResponse already committed}
 * {@link UnsupportedOperationException}. UC-12 § AC3 mandates a
 * real-context test that registers BOTH advices in the same Spring
 * context and asserts the routing.
 *
 * <h2>Test-first cascade (UC-12 § AC10 / § AC11)</h2>
 *
 * <p>This test is written and committed BEFORE the developer's
 * production change. Against current {@code main} (server-v0.0.12)
 * every parameterised invocation MUST fail in a predictable pattern:
 *
 * <ul>
 *   <li>{@code response.status} is 500 (not the documented 401/409/429).</li>
 *   <li>{@code ProblemDetailsAdvice}'s WARN line
 *       {@code "Unmapped exception in REST flow"} fires once per
 *       request.</li>
 *   <li>{@code HttpWebHandlerAdapter} (or equivalent) logs
 *       {@code "ServerHttpResponse already committed"} at ERROR.</li>
 * </ul>
 *
 * <p>The developer's production change (option (a) from the analyst's
 * proposal — remove {@code @ExceptionHandler(Throwable.class)} from
 * {@code ProblemDetailsAdvice} and move the generic fallback to a
 * lowest-precedence {@code WebExceptionHandler}) then makes every
 * parameter invocation green WITHOUT a QA-side edit. The cascade
 * signal is captured by the orchestrator from this test's pre-fix
 * gradle output.
 *
 * <h2>Hard prohibitions (UC-12 § AC3)</h2>
 *
 * <p>NO {@code MockServerWebExchange}. NO
 * {@code WebTestClient.bindToController}. NO {@code WebFilter}-adapter
 * shortcuts. Those are the exact shortcuts that masked the bug in
 * UC-11. The harness here boots a real {@code @SpringBootTest(RANDOM_PORT)}
 * context, points {@link WebTestClient#bindToServer(ReactorClientHttpConnector)}
 * at the random TLS port, and lets the request traverse the real
 * filter chain + dispatcher + advice chain end-to-end. The
 * {@code POST /v1/enrollment} path is the only mTLS-exempt route on
 * the server (UC-04 § B2), so no client cert is needed; an insecure
 * trust manager is used for the server-cert chain because the cert
 * material here is throwaway-self-signed (chain verification is
 * exercised by other tests).
 *
 * <h2>Parameterisation</h2>
 *
 * <p>The exception types are discovered reflectively via
 * {@link EnrollmentFacade#getClass()}{@code .getDeclaredClasses()},
 * filtered for {@code public static} {@link RuntimeException}
 * subclasses. The mapping table is the same one
 * {@link EnrollmentWebExceptionHandler#handle(
 * org.springframework.web.server.ServerWebExchange, Throwable)} ships
 * — this is the AC3 contract pin, so if a sixth exception is added to
 * {@code EnrollmentFacade} but the handler/test mapping is not, the
 * test fails loudly (the {@code Arguments} stream surfaces the new
 * class but no matching expectation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentExceptionRoutingTest {

    /** Token shape that satisfies {@code @Pattern} validation on the request body. */
    private static final String FAKE_TOKEN = "fake-test-token-not-a-real-key" + "0".repeat(33);

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
            ROOT = Files.createTempDirectory("ai-sandbox-uc12-enroll-routing-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc12-enrollment-routing");

            // Seed one disposable client PEM. NOTE: as of the v0.0.19
            // crashloop fix the boot check no longer refuses to start on an
            // empty allowlist (it logs a warning), so this seed is no longer
            // REQUIRED for boot — it is kept as harmless. /v1/enrollment is
            // mTLS-exempt either way, so the allowlist content is never
            // consulted on the request path under test.
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc12-bootstrap-client");

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
                            "ai-sandbox-uc12-enroll-routing-cleanup"));
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
     * Replaces the production {@link EnrollmentFacade} with a Mockito
     * mock so each parameterised invocation can program a specific
     * exception throw on the next {@code redeem(...)} call. Marked
     * {@code @Primary} so {@link EnrollmentController}'s constructor-
     * autowire picks this bean over the production one.
     */
    @TestConfiguration
    static class StubFacadeConfig {
        @Bean
        @Primary
        EnrollmentFacade stubEnrollmentFacade() {
            return mock(EnrollmentFacade.class);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    EnrollmentFacade facade; // the @Primary mock from StubFacadeConfig

    private ListAppender<ILoggingEvent> problemDetailsLogAppender;
    private Logger problemDetailsLogger;
    private Level priorLevel;

    @BeforeEach
    void attachLogAppender() {
        // Reset the Mockito mock so prior parameterised invocations'
        // `doThrow(...)` stubs don't leak into the next parameter.
        // The @Autowired EnrollmentFacade is the same mock instance
        // across every @ParameterizedTest invocation in this class.
        reset(facade);

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

    /**
     * Reflectively enumerates every {@code public static} nested
     * {@link RuntimeException} subclass on {@link EnrollmentFacade} and
     * pairs each with its expected wire status / code per the
     * {@link EnrollmentWebExceptionHandler} mapping table. If a new
     * exception is added to the facade and someone forgets to extend
     * the handler / this mapping, the {@link #expectedFor(Class)}
     * call below throws and the parameter shows up as a failing test
     * with the offending class name in the failure message — exactly
     * the maintenance-hazard regression UC-12 § Pitfalls calls out
     * for option (b), and a forward-compatibility pin for option (a).
     */
    @SuppressWarnings("unchecked")
    static Stream<Arguments> enrollmentExceptionTypes() {
        return Arrays.stream(EnrollmentFacade.class.getDeclaredClasses())
                .filter(c -> RuntimeException.class.isAssignableFrom(c))
                .filter(c -> Modifier.isStatic(c.getModifiers()))
                .filter(c -> Modifier.isPublic(c.getModifiers()))
                .map(c -> (Class<? extends RuntimeException>) c)
                .map(c -> Arguments.of(c, expectedFor(c)));
    }

    @ParameterizedTest(name = "{0} → HTTP {1}")
    @MethodSource("enrollmentExceptionTypes")
    void enrollment_exception_routes_through_web_exception_handler_to_documented_problem_json(
            Class<? extends RuntimeException> exType, Expected expected) throws Exception {
        // Programme the stub facade to throw this exception on the
        // next redeem(...) call. Use `doThrow().when(...)` rather than
        // `when(...).thenThrow(...)` so the stubbing call itself does
        // NOT trigger any previously-installed stub (across
        // parameterised invocations the @Autowired facade mock is the
        // same instance; `when(facade.redeem(...))` would actually
        // INVOKE the prior thenThrow on the setup-time call).
        RuntimeException thrown = instantiate(exType);
        doThrow(thrown).when(facade).redeem(any(), any());

        WebTestClient client = buildClient(port);

        String body = "{\"token\":\"" + FAKE_TOKEN + "\"}";
        byte[] respBody = client.post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(expected.status)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(respBody)
                .as("response body must not be empty for %s", exType.getSimpleName())
                .isNotNull()
                .isNotEmpty();

        JsonNode root = new ObjectMapper().readTree(respBody);
        assertThat(root.path("status").asInt(-1))
                .as("RFC-9457 top-level status must equal documented mapping for %s", exType.getSimpleName())
                .isEqualTo(expected.status);
        assertThat(root.path("code").asText(null))
                .as("RFC-9457 top-level code MUST be the documented wire code for %s", exType.getSimpleName())
                .isEqualTo(expected.code);
        // Negative pin — UC-11 § AC4 flat-shape guard: `code` must NOT
        // be nested under a `properties` object.
        assertThat(root.path("properties").path("code").asText(null))
                .as("regression guard: `code` MUST NOT nest under `properties` for %s", exType.getSimpleName())
                .isNull();

        assertNoUnmappedExceptionWarning(exType);
    }

    private void assertNoUnmappedExceptionWarning(Class<? extends RuntimeException> exType) {
        assertThat(problemDetailsLogAppender.list)
                .as(
                        "UC-12 § AC3 — ProblemDetailsAdvice 'Unmapped exception in REST flow' MUST NOT"
                                + " fire when %s is correctly routed to EnrollmentWebExceptionHandler",
                        exType.getSimpleName())
                .noneSatisfy(evt -> assertThat(evt.getFormattedMessage()).contains("Unmapped exception in REST flow"));
    }

    // ── instantiate one exception per type ─────────────────────────────

    private static RuntimeException instantiate(Class<? extends RuntimeException> c) throws Exception {
        if (c.equals(EnrollmentFacade.RateLimitedException.class)) {
            return new EnrollmentFacade.RateLimitedException("198.51.100.10");
        }
        if (c.equals(EnrollmentFacade.TokenInvalidException.class)) {
            return new EnrollmentFacade.TokenInvalidException();
        }
        if (c.equals(EnrollmentFacade.TokenExpiredException.class)) {
            return new EnrollmentFacade.TokenExpiredException();
        }
        if (c.equals(EnrollmentFacade.TokenRedeemedException.class)) {
            return new EnrollmentFacade.TokenRedeemedException();
        }
        if (c.equals(EnrollmentFacade.CertAlreadyExistsException.class)) {
            return new EnrollmentFacade.CertAlreadyExistsException(
                    "alice-phone", new FileAlreadyExistsException("/etc/ai-sandbox-server/clients/alice-phone.crt"));
        }
        // Generic fallback — try a no-arg constructor so a new
        // exception type added to EnrollmentFacade still parameterises
        // (the failing assertion later will surface the missing
        // mapping).
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0) {
                ctor.setAccessible(true);
                return (RuntimeException) ctor.newInstance();
            }
        }
        throw new IllegalStateException("No known constructor for " + c.getName()
                + " — extend instantiate(...) and expectedFor(...) when adding a new EnrollmentFacade exception.");
    }

    // ── expected mapping table (mirrors EnrollmentWebExceptionHandler) ─

    private static Expected expectedFor(Class<? extends RuntimeException> c) {
        if (c.equals(EnrollmentFacade.RateLimitedException.class)) {
            return new Expected(429, "enrollment_rate_limited");
        }
        if (c.equals(EnrollmentFacade.TokenInvalidException.class)) {
            return new Expected(401, "enrollment_token_invalid");
        }
        if (c.equals(EnrollmentFacade.TokenExpiredException.class)) {
            return new Expected(401, "enrollment_token_expired");
        }
        if (c.equals(EnrollmentFacade.TokenRedeemedException.class)) {
            return new Expected(401, "enrollment_token_redeemed");
        }
        if (c.equals(EnrollmentFacade.CertAlreadyExistsException.class)) {
            return new Expected(409, "client_name_conflict");
        }
        throw new IllegalStateException("No expected mapping for " + c.getName()
                + " — extend EnrollmentWebExceptionHandler and expectedFor(...) when adding a new exception.");
    }

    record Expected(int status, String code) {}

    // ── WebTestClient over the random TLS port ─────────────────────────

    /**
     * Build a {@link WebTestClient} bound to the random TLS port. The
     * Reactor-Netty connector uses {@link InsecureTrustManagerFactory}
     * for the server-cert trust manager — chain validation is not what
     * this test exercises, and the CertFixtures-minted server cert has
     * no SubjectAltName for {@code 127.0.0.1} anyway. No client cert is
     * configured: {@code POST /v1/enrollment} is mTLS-EXEMPT (UC-04 §
     * B2), so the {@code MtlsEnforcementFilter} admits anonymous TLS
     * connections to this exact path.
     */
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
