package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.sessions.facade.SessionFacade;
import java.net.URI;
import java.security.cert.CertificateException;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * AC21 — REST errors render as application/problem+json (RFC 9457) with a
 * stable {@code code} property carrying the lowercase ErrorCode name and a
 * {@code type} URI under {@code https://ai-sandbox.dev/problems/}.
 */
class ProblemDetailsAdviceTest {

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    @Test
    void no_such_element_maps_to_404_session_not_found() {
        ProblemDetail pd = advice.handleNotFound(new NoSuchElementException("session 9 not found"));
        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getProperties()).containsEntry("code", "session_not_found");
        assertThat(pd.getType()).isEqualTo(URI.create("https://ai-sandbox.dev/problems/session_not_found"));
        assertThat(pd.getDetail()).contains("session 9 not found");
    }

    @Test
    void spawn_failed_carries_exitCode_and_consumedN_properties() {
        ProblemDetail pd = advice.handleSpawnFailed(new SessionFacade.SpawnFailedException(7, "bad", 42));
        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getProperties()).containsEntry("code", "spawn_failed");
        assertThat(pd.getProperties()).containsEntry("exitCode", 7);
        assertThat(pd.getProperties()).containsEntry("consumedN", 42);
        assertThat(pd.getDetail()).isEqualTo("bad");
    }

    @Test
    void certificate_exception_is_invalid_cert_pem() {
        ProblemDetail pd = advice.handleBadCert(new CertificateException("malformed"));
        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("code", "invalid_cert_pem");
    }

    @Test
    void illegal_argument_maps_to_400_bad_request() {
        ProblemDetail pd = advice.handleBadArg(new IllegalArgumentException("nope"));
        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("code", "bad_request");
    }

    @Test
    void unmapped_throwable_falls_back_to_500_internal_error() {
        ProblemDetail pd = advice.handleAny(new RuntimeException("boom"));
        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getProperties()).containsEntry("code", "internal_error");
    }

    @Test
    void build_factory_emits_type_uri_and_lowercase_code() {
        ProblemDetail pd = ProblemDetailsAdvice.build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.STREAM_CAP_EXCEEDED, "global cap");
        assertThat(pd.getStatus()).isEqualTo(503);
        assertThat(pd.getType().toString()).endsWith("/stream_cap_exceeded");
        assertThat(pd.getProperties()).containsEntry("code", "stream_cap_exceeded");
        assertThat(pd.getTitle()).isEqualTo("Service Unavailable");
    }

    @Test
    void renderJson_emits_a_valid_json_string() {
        String json = ProblemDetailsAdvice.renderJson(HttpStatus.NOT_FOUND, ErrorCode.SESSION_NOT_FOUND, "n=9");
        assertThat(json).contains("\"code\":\"session_not_found\"");
        assertThat(json).contains("\"status\":404");
        assertThat(json).contains("\"detail\":\"n=9\"");
    }
}
