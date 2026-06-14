package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.sessions.dto.LifecycleAction;
import com.aisandbox.server.sessions.facade.SandboxImageWarmingException;
import com.aisandbox.server.sessions.facade.SessionFacade;
import com.aisandbox.server.sessions.service.SandboxImageState;
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

    /**
     * UC-46 AC4 — an out-of-state lifecycle action maps to 409
     * {@code session_state_conflict}, with the offending {@code n} and the
     * {@code currentState} attached so the client can reconcile / re-render.
     */
    @Test
    void invalid_lifecycle_transition_maps_to_409_session_state_conflict() {
        ProblemDetail pd = advice.handleInvalidLifecycleTransition(
                new SessionFacade.InvalidLifecycleTransitionException(5, LifecycleAction.START, "running"));
        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getProperties()).containsEntry("code", "session_state_conflict");
        assertThat(pd.getProperties()).containsEntry("n", 5);
        assertThat(pd.getProperties()).containsEntry("currentState", "running");
        assertThat(pd.getType()).isEqualTo(URI.create("https://ai-sandbox.dev/problems/session_state_conflict"));
        assertThat(pd.getDetail()).contains("start").contains("running");
    }

    /**
     * UC-77 (AC1/AC3) — a stuck {@code spawn.sh} (the build-free spawn timeout
     * tripped) maps to 504 {@code spawn_timeout}, carrying {@code timeoutSeconds},
     * DISTINCT from the 500 {@code spawn_failed} path so a slow/stuck spawn is
     * never read identically to a hard failure.
     */
    @Test
    void spawn_timeout_maps_to_504_spawn_timeout_with_timeout_seconds() {
        ProblemDetail pd =
                advice.handleSpawnTimeout(new SessionFacade.SpawnTimeoutException(60, "exec timeout: spawn.sh"));
        assertThat(pd.getStatus()).isEqualTo(504);
        assertThat(pd.getProperties()).containsEntry("code", "spawn_timeout");
        assertThat(pd.getProperties()).containsEntry("timeoutSeconds", 60L);
        assertThat(pd.getType()).isEqualTo(URI.create("https://ai-sandbox.dev/problems/spawn_timeout"));
        assertThat(pd.getDetail()).contains("60");
    }

    /**
     * UC-77 (AC1/AC3) — a spawn arriving while the sandbox image is still being
     * prepared maps to 503 {@code sandbox_image_warming}, so the client surfaces
     * a transient "preparing image" state and retries rather than the
     * destructive hard-failure / re-enroll path.
     */
    @Test
    void sandbox_image_warming_maps_to_503_sandbox_image_warming() {
        ProblemDetail pd =
                advice.handleSandboxImageWarming(new SandboxImageWarmingException(SandboxImageState.BUILDING));
        assertThat(pd.getStatus()).isEqualTo(503);
        assertThat(pd.getProperties()).containsEntry("code", "sandbox_image_warming");
        assertThat(pd.getType()).isEqualTo(URI.create("https://ai-sandbox.dev/problems/sandbox_image_warming"));
        assertThat(pd.getDetail()).contains("being prepared");
    }

    @Test
    void illegal_argument_maps_to_400_bad_request() {
        ProblemDetail pd = advice.handleBadArg(new IllegalArgumentException("nope"));
        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("code", "bad_request");
    }

    // UC-12 — `unmapped_throwable_falls_back_to_500_internal_error` removed.
    // The catch-all `@ExceptionHandler(Throwable.class)` on
    // `ProblemDetailsAdvice` was removed (option (a) from the UC-12
    // proposal); the unmapped-exception fallback is now a separate
    // `WebExceptionHandler` whose contract is pinned by
    // `com.aisandbox.server.api.error.GenericProblemFallbackHandlerTest`.

    @Test
    void build_factory_emits_type_uri_and_lowercase_code() {
        ProblemDetail pd =
                ProblemDetailsAdvice.build(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.STREAM_CAP_EXCEEDED, "global cap");
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
