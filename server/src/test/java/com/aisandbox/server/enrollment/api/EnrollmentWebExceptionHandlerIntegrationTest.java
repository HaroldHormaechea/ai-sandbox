package com.aisandbox.server.enrollment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aisandbox.server.api.RequestSizeLimitFilter;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * UC11 § AC5 + AC6 — reactive-aware exception → {@code
 * application/problem+json} contract.
 *
 * <h2>Scope</h2>
 *
 * <p>Exercises {@link EnrollmentWebExceptionHandler} directly via a
 * {@link MockServerWebExchange} so the assertions can pin the exact
 * response body, status, content-type, and SLF4J log silence on a
 * per-exception-type basis. Each of the five UC11-managed exceptions
 * is fed into the handler and the resulting response is decoded
 * synchronously; the corresponding {@link ProblemDetailsAdvice} log
 * appender (the same SLF4J {@code LOG} field that the production
 * "Unmapped exception in REST flow" line was originating from
 * pre-fix) is captured for the duration of each test, asserting that
 * line does NOT fire when the WebExceptionHandler catches the
 * exception correctly.
 *
 * <h2>Why not @SpringBootTest(RANDOM_PORT) or @WebFluxTest</h2>
 *
 * <p>The full-boot path (used by sibling tests under
 * {@code com.aisandbox.server.integration.*}) would also work, but it
 * requires a fully-wired enrollment service graph, TLS material on
 * disk, a Reactor-Netty bind on a random port, and a corresponding
 * {@code @DynamicPropertySource} block — overkill for verifying the
 * exception-to-response wiring. {@code @WebFluxTest} is not available
 * in Spring Boot 4.0.6's autoconfigure jar (the WebFlux test slice
 * was reorganised in 4.x). Driving the handler directly with a
 * {@link MockServerWebExchange} gives byte-identical body output,
 * full status / content-type / order-precedence coverage, and the
 * ability to attach a {@link ListAppender} to the
 * {@link ProblemDetailsAdvice} logger to prove the catch-all
 * fallback never fires for the five mapped failure modes — exactly
 * the regression UC11 § AC5 was added to catch.
 *
 * <h2>413 payload-too-large leg</h2>
 *
 * <p>The 413 leg is upstream of the enrollment exception handler —
 * {@link RequestSizeLimitFilter} short-circuits the request before
 * any controller / facade / handler code runs. UC11 § AC5 still
 * wants the regression test that the 413 path produces
 * {@code payload_too_large} body with no {@code "ServerHttpResponse
 * already committed"} log noise; we cover that by invoking the filter
 * directly with an oversize payload and asserting the response body
 * + log silence.
 */
class EnrollmentWebExceptionHandlerIntegrationTest {

    private static final String FAKE_TOKEN =
            "fake-test-token-not-a-real-key" + "0".repeat(33); // 63+ chars, matches body validator pattern.

    private EnrollmentWebExceptionHandler handler;
    private ListAppender<ILoggingEvent> problemDetailsLogAppender;
    private Logger problemDetailsLogger;
    private Level priorLevel;

    @BeforeEach
    void attachLogAppender() {
        handler = new EnrollmentWebExceptionHandler();
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
    void rate_limited_exception_returns_429_problem_json_with_correct_code() {
        MockServerWebExchange exchange = postEnrollmentExchange();
        EnrollmentFacade.RateLimitedException ex = new EnrollmentFacade.RateLimitedException("198.51.100.10");

        handler.handle(exchange, ex).block();

        assertResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "enrollment_rate_limited");
        assertProblemDetailsAdviceNeverLoggedUnmappedFor(ex);
    }

    @Test
    void token_invalid_exception_returns_401_enrollment_token_invalid() {
        MockServerWebExchange exchange = postEnrollmentExchange();
        EnrollmentFacade.TokenInvalidException ex = new EnrollmentFacade.TokenInvalidException();

        handler.handle(exchange, ex).block();

        assertResponse(exchange, HttpStatus.UNAUTHORIZED, "enrollment_token_invalid");
        assertProblemDetailsAdviceNeverLoggedUnmappedFor(ex);
    }

    @Test
    void token_expired_exception_returns_401_enrollment_token_expired() {
        MockServerWebExchange exchange = postEnrollmentExchange();
        EnrollmentFacade.TokenExpiredException ex = new EnrollmentFacade.TokenExpiredException();

        handler.handle(exchange, ex).block();

        assertResponse(exchange, HttpStatus.UNAUTHORIZED, "enrollment_token_expired");
        assertProblemDetailsAdviceNeverLoggedUnmappedFor(ex);
    }

    @Test
    void token_redeemed_exception_returns_401_enrollment_token_redeemed() {
        MockServerWebExchange exchange = postEnrollmentExchange();
        EnrollmentFacade.TokenRedeemedException ex = new EnrollmentFacade.TokenRedeemedException();

        handler.handle(exchange, ex).block();

        assertResponse(exchange, HttpStatus.UNAUTHORIZED, "enrollment_token_redeemed");
        assertProblemDetailsAdviceNeverLoggedUnmappedFor(ex);
    }

    @Test
    void cert_already_exists_exception_returns_409_client_name_conflict() {
        MockServerWebExchange exchange = postEnrollmentExchange();
        EnrollmentFacade.CertAlreadyExistsException ex = new EnrollmentFacade.CertAlreadyExistsException(
                "alice-phone", new FileAlreadyExistsException("/etc/ai-sandbox-server/clients/alice-phone.crt"));

        handler.handle(exchange, ex).block();

        assertResponse(exchange, HttpStatus.CONFLICT, "client_name_conflict");
        assertProblemDetailsAdviceNeverLoggedUnmappedFor(ex);
    }

    @Test
    void unmapped_exception_propagates_via_Mono_error_for_default_chain_fallthrough() {
        // UC11 § AC6 — for any throwable that is NOT one of the five
        // mapped types, the handler returns Mono.error(...) so the
        // next handler in Spring's chain (the framework default →
        // ProblemDetailsAdvice.handleAny via its
        // @ExceptionHandler(Throwable.class) catch-all) processes it.
        MockServerWebExchange exchange = postEnrollmentExchange();
        RuntimeException ex = new RuntimeException("not in any specific handler");

        Mono<Void> result = handler.handle(exchange, ex);

        // Response was NOT written by the enrollment handler — the
        // status / body are still pristine.
        assertThatThrownBy(result::block)
                .as("UC11 § AC6 — unmapped exception must propagate so the next handler can process it")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not in any specific handler");
        assertThat(exchange.getResponse().getStatusCode())
                .as("UC11 § AC6 — fall-through must NOT commit the response from the enrollment handler")
                .isNull();
    }

    @Test
    void already_committed_response_short_circuits_to_Mono_error() {
        // UC11 § AC5 — defensive guard: if some upstream handler has
        // already committed the response, the enrollment handler MUST
        // NOT attempt to overwrite the status/body (which is what
        // produced the pre-UC11 UnsupportedOperationException:
        // ServerHttpResponse already committed). It instead returns
        // Mono.error(ex) so the framework default logs the inconsistency.
        MockServerWebExchange exchange = postEnrollmentExchange();
        exchange.getResponse().setComplete().block();
        EnrollmentFacade.RateLimitedException ex = new EnrollmentFacade.RateLimitedException("198.51.100.10");

        Mono<Void> result = handler.handle(exchange, ex);

        assertThatThrownBy(result::block)
                .as("UC11 § AC5 — committed-response branch must surface the original exception")
                .isInstanceOf(EnrollmentFacade.RateLimitedException.class);
    }

    @Test
    void problem_details_advice_handleAny_catches_truly_unmapped_exceptions() {
        // UC11 § AC6 — sibling regression: ProblemDetailsAdvice's
        // generic Throwable fallback still produces the documented
        // 500 / internal_error shape for exceptions that no other
        // handler processes. This is the safety net for endpoints
        // outside the enrollment domain.
        ProblemDetailsAdvice advice = new ProblemDetailsAdvice();
        org.springframework.http.ProblemDetail pd = advice.handleAny(new RuntimeException("genuinely unmapped"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getType().toString()).endsWith("/internal_error");
        assertThat(pd.getProperties()).containsEntry("code", "internal_error");

        // The "Unmapped exception in REST flow" line MUST fire — this
        // is the operational signal that a domain-specific handler is
        // missing (the exact line UC11 § AC5 verifies is silent for
        // enrollment exceptions).
        assertThat(problemDetailsLogAppender.list)
                .as(
                        "ProblemDetailsAdvice.handleAny must log the unmapped-exception line for genuinely unmapped throwables")
                .anySatisfy(evt -> assertThat(evt.getFormattedMessage()).contains("Unmapped exception in REST flow"));
    }

    @Test
    void request_size_limit_filter_returns_413_payload_too_large_with_no_unmapped_log() {
        // UC11 § AC5 — 413 leg: requests over the 256-byte cap are
        // short-circuited by RequestSizeLimitFilter before any
        // controller / facade / handler code runs. Assert the body
        // shape (status, content-type, code) AND that the
        // ProblemDetailsAdvice.handleAny log line does NOT fire (the
        // pre-UC11 symptom was "Unmapped exception in REST flow" /
        // "ServerHttpResponse already committed" cascading from the
        // 413 path).
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(propsWithMaxBytes(65536));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(512); // > 256-byte enrollment cap.
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/enrollment").headers(headers).contentType(MediaType.APPLICATION_JSON));
        WebFilterChain chain = e -> Mono.error(new AssertionError("filter should have short-circuited"));

        filter.filter(ex, chain).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(ex.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        // Pin the wire shape — top-level `code`, NOT nested under
        // `properties`. The 413 path was the pre-UC11 latent home of
        // the nested-properties bug; this assertion ensures the
        // developer's RENDER_MAPPER fix covers it too.
        String body = readBody(ex);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new AssertionError("413 problem+json body must parse as JSON; got: " + body, e);
        }
        assertThat(root.path("code").asText(null))
                .as("RFC-9457: top-level `code` for 413 path. body=%s", body)
                .isEqualTo("payload_too_large");
        assertThat(root.path("status").asInt(-1)).isEqualTo(413);
        assertThat(root.path("properties").path("code").asText(null))
                .as("regression guard: 413 path MUST NOT nest `code` under `properties`. body=%s", body)
                .isNull();

        assertProblemDetailsAdviceNeverLoggedUnmapped();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static MockServerWebExchange postEnrollmentExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"token\":\"" + FAKE_TOKEN + "\"}"));
    }

    private static void assertResponse(MockServerWebExchange exchange, HttpStatus status, String code) {
        assertThat(exchange.getResponse().getStatusCode())
                .as("status for code=%s", code)
                .isEqualTo(status);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .as("content-type for code=%s", code)
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        // Parse the body as JSON and assert RFC-9457-compliant shape:
        // status / type / detail / code MUST live at the TOP LEVEL of
        // the document. The `code` MUST NOT be nested under a
        // `properties` object — that nested shape would break the
        // Android client's `parseProblemJson` and was the exact
        // regression UC11 § AC4 / S2 surfaced. ProblemDetailsAdvice's
        // static RENDER_MAPPER registers ProblemDetailJacksonMixin so
        // the @JsonAnyGetter on getProperties flattens onto the top
        // level. We pin the wire shape here so any future drop of that
        // mixin (or replacement with a raw ObjectMapper) breaks this
        // test loudly.
        String body = readBody(exchange);
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new AssertionError("problem+json body must parse as JSON; got: " + body, e);
        }
        assertThat(root.path("code").asText(null))
                .as("RFC-9457: `code` MUST be at the top level (not nested under `properties`). body=%s", body)
                .isEqualTo(code);
        assertThat(root.path("status").asInt(-1))
                .as("RFC-9457: `status` MUST be at the top level. body=%s", body)
                .isEqualTo(status.value());
        assertThat(root.path("type").asText(""))
                .as("RFC-9457: `type` URI MUST end with the lowercase wire code. body=%s", body)
                .endsWith("/" + code);
        // Negative pin — the pre-UC11 latent / pre-developer-fix bug
        // shape had `code` under a nested `properties` object. Asserting
        // it is NOT there ensures regressions can't slip back in even if
        // someone happens to set both a top-level `code` AND a nested
        // properties.code.
        assertThat(root.path("properties").path("code").asText(null))
                .as("regression guard: `code` MUST NOT be nested under `properties`. body=%s", body)
                .isNull();
    }

    private static String readBody(MockServerWebExchange exchange) {
        String s = exchange.getResponse().getBodyAsString().block();
        return s == null ? "" : s;
    }

    private void assertProblemDetailsAdviceNeverLoggedUnmappedFor(Throwable ex) {
        assertThat(problemDetailsLogAppender.list)
                .as(
                        "UC11 § AC5 — ProblemDetailsAdvice 'Unmapped exception in REST flow' MUST NOT fire for %s",
                        ex.getClass().getSimpleName())
                .noneSatisfy(evt -> assertThat(evt.getFormattedMessage()).contains("Unmapped exception in REST flow"));
    }

    private void assertProblemDetailsAdviceNeverLoggedUnmapped() {
        assertThat(problemDetailsLogAppender.list)
                .as("UC11 § AC5 — ProblemDetailsAdvice 'Unmapped exception in REST flow' MUST NOT fire")
                .noneSatisfy(evt -> assertThat(evt.getFormattedMessage()).contains("Unmapped exception in REST flow"));
    }

    private static ServerProperties propsWithMaxBytes(int maxBytes) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, maxBytes),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(Path.of("/e"), 10, 1, 60));
    }
}
