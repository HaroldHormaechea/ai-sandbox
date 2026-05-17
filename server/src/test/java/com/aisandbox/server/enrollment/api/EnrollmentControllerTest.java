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

/**
 * UC04 AC33–AC35 — wire shape of {@code POST /v1/enrollment}.
 *
 * <p>Tests use {@link WebTestClient#bindToController} bound to the
 * controller + the enrollment-specific {@link EnrollmentProblemDetailsAdvice}.
 * The body cap (413 {@code payload_too_large}) is handled upstream by
 * {@code RequestSizeLimitFilter} and is covered by its own test — this
 * test focuses on the controller-layer surface.
 */
class EnrollmentControllerTest {

    private static final String FAKE_TOKEN =
            "fake-test-token-not-a-real-key" + "0".repeat(33); // 63+ chars, [A-Za-z0-9._-]

    private static WebTestClient clientFor(EnrollmentFacade facade) {
        return WebTestClient.bindToController(new EnrollmentController(facade))
                .controllerAdvice(new EnrollmentProblemDetailsAdvice())
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
    void malformed_token_pattern_is_rejected_at_validation_boundary() throws Exception {
        // The body validator rejects tokens that don't match
        // [A-Za-z0-9._-]{32,256}. Anything shorter or with bad chars
        // becomes a 400 before the facade is even called — this is the
        // first line of defence against junk being mapped to a token.
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
