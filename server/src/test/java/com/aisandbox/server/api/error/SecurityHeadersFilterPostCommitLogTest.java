package com.aisandbox.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.identity.ClientIdentityExtractor;
import com.aisandbox.server.sessions.service.DockerEnumerationService;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.test.CertFixtures;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * UC-17 bug-pin — proves the {@code SecurityHeadersFilter}
 * post-commit log noise is silenced on a SUCCESSFUL real-port REST
 * call.
 *
 * <h2>The bug this test catches</h2>
 *
 * <p>Pre-fix shape (UC-15 era):
 *
 * <pre>{@code
 * public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
 *     return chain.filter(exchange).then(Mono.fromRunnable(() -> apply(exchange)));
 * }
 * }</pre>
 *
 * <p>The {@code apply(exchange)} mutation ran AFTER the downstream
 * handler emitted its body. For any endpoint whose handler emits a
 * full body (Spring writes status + headers + first bytes onto the
 * wire as part of {@code chain.filter}'s Mono completion path), the
 * response was already committed by the time the
 * {@code Mono.fromRunnable} fired. {@code apply(exchange)} then
 * called {@code response.getHeaders().set(...)} on the sealed
 * {@link org.springframework.http.HttpHeaders}, which threw
 * {@link UnsupportedOperationException}. Reactor surfaced that as
 * two correlated log lines:
 *
 * <ul>
 *   <li>WARN on the {@link ProblemDetailsAdvice} category — the
 *       generic-fallback handler picked up the UOE as an unmapped
 *       exception and logged it with the literal prefix
 *       {@code "Unmapped exception in REST flow"}.</li>
 *   <li>ERROR on the
 *       {@code org.springframework.web.server.adapter.HttpWebHandlerAdapter}
 *       category — Reactor logged that an
 *       {@link UnsupportedOperationException} arrived after the
 *       {@code ServerHttpResponse} was already committed.</li>
 * </ul>
 *
 * <p>The post-fix shape (UC-17, commit d4fa1bd) runs
 * {@code apply(exchange)} SYNCHRONOUSLY BEFORE {@code chain.filter},
 * so the headers are written on the still-uncommitted response. No
 * UOE is manufactured; neither log line should fire on a successful
 * REST call.
 *
 * <h2>Harness</h2>
 *
 * <p>Real {@code @SpringBootTest(RANDOM_PORT)} TLS, mTLS-bypass
 * via a {@link Ordered#HIGHEST_PRECEDENCE} {@link WebFilter} that
 * pre-stashes a non-anonymous {@link ClientIdentity} (identical to
 * the pattern in
 * {@link com.aisandbox.server.api.SessionEnumerationFailureControllerTest}),
 * and a {@link Primary} Mockito mock of {@link ProcessExecutor} that
 * stubs {@code docker compose ls --all --format json} → empty array,
 * which is the smallest possible happy-path response for the
 * docker-touching {@code GET /v1/sessions} endpoint without needing
 * a real Docker daemon.
 *
 * <p>Driving a successful REST call (rather than a synthetic
 * post-commit throw like
 * {@link PostCommitNoiseSilenceTest#post_commit_downstream_error_does_not_fire_unmapped_warn_or_already_committed_error})
 * is the point: the bug fires on EVERY successful response on the
 * pre-fix code path, not only on exception paths.
 *
 * <h2>What this test asserts</h2>
 *
 * <ol>
 *   <li>Status 200 OK (the call succeeded — empty body is a valid
 *       enumeration).</li>
 *   <li>The four baseline AC22 security headers (HSTS, nosniff,
 *       X-Frame-Options, Referrer-Policy) ride on the wire — proves
 *       the filter's apply() actually ran AND the values reached the
 *       client (affirmative coverage, not just "no log noise").</li>
 *   <li>ZERO WARN events on {@link ProblemDetailsAdvice} whose
 *       message starts with {@code "Unmapped exception in REST flow"}
 *       (pre-fix: exactly one per request).</li>
 *   <li>ZERO ERROR events on {@code HttpWebHandlerAdapter} whose
 *       message contains BOTH {@code "UnsupportedOperationException"}
 *       AND {@code "already committed"} (pre-fix: at least one per
 *       request).</li>
 * </ol>
 *
 * <p>The pre-fix demonstration procedure (toggle production back to
 * the broken shape, observe failure, restore, observe pass) is
 * orchestrated by the team lead — QA cannot edit production code.
 * See the Test Summary for the recorded output of that toggle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersFilterPostCommitLogTest {

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
            ROOT = Files.createTempDirectory("ai-sandbox-uc17-postcommit-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "uc17-postcommit");
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc17-postcommit-bootstrap-client");

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
                            "ai-sandbox-uc17-postcommit-cleanup"));
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
     * @Primary mock of {@link ProcessExecutor} — the docker subprocess
     * seam. Stubbed to return an EMPTY compose-ls payload (the
     * cheapest happy-path response for {@code GET /v1/sessions} on a
     * non-Docker sandbox). Also pre-stashes a non-anonymous
     * {@link ClientIdentity} via a {@link Ordered#HIGHEST_PRECEDENCE}
     * {@link WebFilter} so the mTLS gate admits the request.
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
                                new ClientIdentity("uc17-postcommit-client", "deadbeef".repeat(8), BigInteger.ONE));
                return chain.filter(exchange);
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    DockerEnumerationService enumeration;

    @Autowired
    SessionRegistryService registry;

    private ListAppender<ILoggingEvent> problemDetailsAppender;
    private Logger problemDetailsLogger;
    private Level problemDetailsPriorLevel;

    private ListAppender<ILoggingEvent> webHandlerAppender;
    private Logger webHandlerLogger;
    private Level webHandlerPriorLevel;

    @BeforeEach
    void resetMockAndAttachAppenders() throws Exception {
        reset(executor);
        registry.invalidate();
        resetStickyFlag(enumeration);

        // Stub the only docker subprocess this test exercises: an
        // EMPTY compose-ls payload. Modern argv path — no fallback.
        when(executor.run(
                        argThat(argv -> argv != null && argv.size() >= 3 && "ls".equals(argv.get(2))),
                        any(),
                        any(),
                        any()))
                .thenReturn(new ProcessExecutor.Result(0, "[]", ""));

        // Appender #1 — pre-fix WARN signature.
        problemDetailsLogger = (Logger) LoggerFactory.getLogger(ProblemDetailsAdvice.class);
        problemDetailsPriorLevel = problemDetailsLogger.getLevel();
        problemDetailsAppender = new ListAppender<>();
        problemDetailsAppender.setContext(problemDetailsLogger.getLoggerContext());
        problemDetailsAppender.start();
        problemDetailsLogger.addAppender(problemDetailsAppender);
        problemDetailsLogger.setLevel(Level.WARN);

        // Appender #2 — pre-fix ERROR signature (Reactor's
        // already-committed dispatcher line).
        webHandlerLogger =
                (Logger) LoggerFactory.getLogger("org.springframework.web.server.adapter.HttpWebHandlerAdapter");
        webHandlerPriorLevel = webHandlerLogger.getLevel();
        webHandlerAppender = new ListAppender<>();
        webHandlerAppender.setContext(webHandlerLogger.getLoggerContext());
        webHandlerAppender.start();
        webHandlerLogger.addAppender(webHandlerAppender);
        webHandlerLogger.setLevel(Level.ERROR);
    }

    @AfterEach
    void detachAppenders() {
        if (problemDetailsLogger != null && problemDetailsAppender != null) {
            problemDetailsLogger.detachAppender(problemDetailsAppender);
            problemDetailsAppender.stop();
            problemDetailsLogger.setLevel(problemDetailsPriorLevel);
        }
        if (webHandlerLogger != null && webHandlerAppender != null) {
            webHandlerLogger.detachAppender(webHandlerAppender);
            webHandlerAppender.stop();
            webHandlerLogger.setLevel(webHandlerPriorLevel);
        }
    }

    /**
     * UC-17 bug-pin — a successful {@code GET /v1/sessions} MUST NOT
     * fire the pre-fix WARN / ERROR pair. Also affirms the four
     * baseline AC22 security headers ride on the wire (proves the
     * filter ran and didn't get silenced by the ordering rearrangement).
     */
    @Test
    void successful_get_sessions_does_not_fire_unmapped_warn_or_already_committed_error_and_carries_security_headers()
            throws Exception {
        WebTestClient client = buildClient(port);

        // Status + header wire-presence (AC22 affirmative coverage).
        client.get()
                .uri("/v1/sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Strict-Transport-Security", "max-age=63072000")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("X-Frame-Options", "DENY")
                .expectHeader()
                .valueEquals("Referrer-Policy", "no-referrer");

        // Give the server a brief window to flush any deferred WARN /
        // ERROR log lines that the exception-handler chain might emit
        // asynchronously after the response writer terminates. 250ms
        // is enough on local CI without being a flake risk on slow
        // GH-Actions runners.
        Thread.sleep(250);

        // Assertion 1 — bug-pin WARN. Pre-fix: exactly 1 per request
        // (the generic-fallback handler logged the UOE as unmapped).
        // Post-fix: 0 (no UOE manufactured, no unmapped path entered).
        long unmappedWarnCount = problemDetailsAppender.list.stream()
                .filter(evt -> Level.WARN.equals(evt.getLevel()))
                .filter(evt -> evt.getFormattedMessage().startsWith("Unmapped exception in REST flow"))
                .count();
        assertThat(unmappedWarnCount)
                .as(
                        "UC-17 bug-pin — a successful GET /v1/sessions MUST NOT trigger an "
                                + "\"Unmapped exception in REST flow\" WARN on ProblemDetailsAdvice. "
                                + "Pre-fix shape (chain.filter(...).then(apply)) commits the response BEFORE "
                                + "apply() runs; apply() then mutates sealed headers and throws "
                                + "UnsupportedOperationException, which the generic fallback logs as "
                                + "unmapped. Events on ProblemDetailsAdvice: %s",
                        problemDetailsAppender.list)
                .isEqualTo(0L);

        // Assertion 2 — bug-pin ERROR. Pre-fix: at least 1 per
        // request (Reactor logged the UOE arriving after commit).
        // Post-fix: 0 (no UOE → no already-committed ERROR with the
        // UOE bracketed-type signature).
        long alreadyCommittedErrorCount = webHandlerAppender.list.stream()
                .filter(evt -> Level.ERROR.equals(evt.getLevel()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains("UnsupportedOperationException"))
                .filter(msg -> msg.contains("already committed"))
                .count();
        assertThat(alreadyCommittedErrorCount)
                .as(
                        "UC-17 bug-pin — a successful GET /v1/sessions MUST NOT trigger an "
                                + "UnsupportedOperationException / already-committed ERROR on the "
                                + "HttpWebHandlerAdapter category. That log line is the precise "
                                + "pre-fix signature of SecurityHeadersFilter mutating sealed headers "
                                + "after the response committed. Events on HttpWebHandlerAdapter: %s",
                        webHandlerAppender.list)
                .isEqualTo(0L);
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Reflectively clears the {@code composeListAllSupported} volatile
     * field on the singleton {@link DockerEnumerationService} so this
     * test method starts from the unprobed state regardless of any
     * prior test method's effect on the singleton.
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
                .responseTimeout(Duration.ofSeconds(15))
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
