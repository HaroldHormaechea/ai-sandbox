package com.aisandbox.server.enrollment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.enrollment.dto.MintedBundle;
import com.aisandbox.server.enrollment.facade.EnrollmentFacade;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;

/**
 * UC04 AC33–AC35 + UC11 § AC5 — wire shape of {@code POST /v1/enrollment}.
 *
 * <p>Pre-UC11 the test wired the controller into {@link WebTestClient}
 * via {@code bindToController(controller).controllerAdvice(new
 * EnrollmentProblemDetailsAdvice())}. UC11 § AC4 / S2 deleted the
 * {@code @RestControllerAdvice} (it never fired under WebFlux) and
 * replaced it with {@link EnrollmentWebExceptionHandler} — a
 * reactive-aware {@link WebExceptionHandler} bean.
 *
 * <p>{@code WebExceptionHandler} beans are NOT picked up by
 * {@link WebTestClient.MockServerSpec} the way {@code @ControllerAdvice}
 * was via {@code bindToController(...).controllerAdvice(...)}; that
 * mock-server spec has no {@code exceptionHandler(...)} method. To
 * keep the tests self-contained (no full Spring Boot context boot,
 * matching the lightweight original setup), we adapt the
 * {@link WebExceptionHandler} to a {@link WebFilter} via
 * {@link reactor.core.publisher.Mono#onErrorResume(java.util.function.Function)
 * onErrorResume}. The filter calls the chain (which dispatches into
 * the controller); when the controller / facade throws or returns
 * {@code Mono.error}, the resulting failure flows through
 * {@code onErrorResume} and is handed to the real
 * {@link EnrollmentWebExceptionHandler#handle(
 * org.springframework.web.server.ServerWebExchange, Throwable)}. That
 * exercises the SAME body-rendering, status-mapping, and committed-
 * response defensive logic as the production wiring.
 *
 * <p>The full reactive handler chain (controller advice fall-through,
 * the chain-level dispatcher ordering, the actual bean-scan-driven
 * {@code @Order} resolution) is covered separately by
 * {@link EnrollmentWebExceptionHandlerIntegrationTest}, which boots a
 * real Spring Boot context.
 *
 * <p>The body cap (413 {@code payload_too_large}) is handled upstream
 * by {@code RequestSizeLimitFilter} and is covered by its own test —
 * this test focuses on the controller-layer surface (201 happy path,
 * 400 validation, 401/429/409 from the new exception handler).
 */
class EnrollmentControllerTest {

    private static final String FAKE_TOKEN =
            "fake-test-token-not-a-real-key" + "0".repeat(33); // 63+ chars, [A-Za-z0-9._-]

    /**
     * Adapt the {@link WebExceptionHandler} contract to a {@link
     * WebFilter} so the {@code WebTestClient.bindToController} path
     * routes facade-thrown exceptions through it. The production wiring
     * uses a {@code WebExceptionHandler} bean discovered by
     * {@code WebHttpHandlerBuilder.applicationContext(...)} — but the
     * resulting downstream call ({@code handler.handle(exchange,
     * throwable)}) is identical to what {@code onErrorResume} produces
     * here, so the response-shape assertions pin the exact same
     * behaviour.
     */
    private static WebFilter exceptionHandlerAsFilter(WebExceptionHandler handler) {
        return (exchange, chain) -> chain.filter(exchange).onErrorResume(t -> handler.handle(exchange, t));
    }

    private static WebTestClient clientFor(EnrollmentFacade facade) {
        return WebTestClient.bindToController(new EnrollmentController(facade))
                .webFilter(exceptionHandlerAsFilter(new EnrollmentWebExceptionHandler()))
                .build();
    }

    @Test
    void success_returns_201_octet_stream_with_p12_body_and_content_disposition() throws Exception {
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        byte[] p12 = new byte[] {0x30, 0x01, 0x02, 0x03};
        when(facade.redeem(any(String.class), any())).thenReturn(new MintedBundle("alice-phone", "PEM", p12));

        WebTestClient client = clientFor(facade);

        client.post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectHeader()
                .valueMatches(HttpHeaders.CONTENT_DISPOSITION, ".*filename.*alice-phone\\.p12.*")
                // Empty passphrase surfaced as an out-of-band hint.
                .expectHeader()
                .valueEquals("X-AI-Sandbox-P12-Passphrase", "")
                .expectBody(byte[].class)
                .isEqualTo(p12);
    }

    @Test
    void rate_limited_returns_429_problem_details_with_correct_code() throws Exception {
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        when(facade.redeem(any(String.class), any()))
                .thenThrow(new EnrollmentFacade.RateLimitedException("198.51.100.10"));

        WebTestClient client = clientFor(facade);

        client.post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("enrollment_rate_limited")
                .jsonPath("$.type")
                .value(t -> assertThat((String) t).endsWith("/enrollment_rate_limited"))
                .jsonPath("$.status")
                .isEqualTo(429);
    }

    @Test
    void unknown_token_returns_401_enrollment_token_invalid() throws Exception {
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        when(facade.redeem(any(String.class), any())).thenThrow(new EnrollmentFacade.TokenInvalidException());

        clientFor(facade)
                .post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("enrollment_token_invalid")
                .jsonPath("$.status")
                .isEqualTo(401);
    }

    @Test
    void expired_token_returns_401_enrollment_token_expired() throws Exception {
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        when(facade.redeem(any(String.class), any())).thenThrow(new EnrollmentFacade.TokenExpiredException());

        clientFor(facade)
                .post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("enrollment_token_expired");
    }

    @Test
    void redeemed_token_returns_401_enrollment_token_redeemed() throws Exception {
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        when(facade.redeem(any(String.class), any())).thenThrow(new EnrollmentFacade.TokenRedeemedException());

        clientFor(facade)
                .post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("enrollment_token_redeemed");
    }

    @Test
    void cert_already_exists_returns_409_client_name_conflict() throws Exception {
        // UC11 § AC4 — the new mapping introduced by
        // EnrollmentWebExceptionHandler. The cert-name collision surfaces
        // from EnrollmentFacade.redeem(...) as a CertAlreadyExistsException
        // wrapping a FileAlreadyExistsException; the handler maps it to
        // 409 with the client_name_conflict error code.
        EnrollmentFacade facade = mock(EnrollmentFacade.class);
        when(facade.redeem(any(String.class), any()))
                .thenThrow(new EnrollmentFacade.CertAlreadyExistsException(
                        "alice-phone", new java.nio.file.FileAlreadyExistsException("/clients/alice-phone.crt")));

        clientFor(facade)
                .post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"" + FAKE_TOKEN + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("client_name_conflict")
                .jsonPath("$.status")
                .isEqualTo(409);
    }

    @Test
    void malformed_token_pattern_is_rejected_at_validation_boundary() throws Exception {
        // The body validator rejects tokens that don't match
        // [A-Za-z0-9._-]{32,256}. Anything shorter or with bad chars
        // becomes a 400 before the facade is even called — this is the
        // first line of defence against junk being mapped to a token.
        // The 400 surfaces from Spring's WebExchangeBindException
        // before any controller code runs, so the EnrollmentWeb-
        // ExceptionHandler defers to the framework default chain.
        EnrollmentFacade facade = mock(EnrollmentFacade.class);

        clientFor(facade)
                .post()
                .uri("/v1/enrollment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"token\":\"short\"}")
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }
}
