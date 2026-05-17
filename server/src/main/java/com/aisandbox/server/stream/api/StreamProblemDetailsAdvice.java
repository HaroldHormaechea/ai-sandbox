package com.aisandbox.server.stream.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.stream.facade.StreamFacade;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * UC04 AC37 — exception → {@code application/problem+json} mapping for
 * {@link StreamFacade.SessionNotRunningException}.
 *
 * <p>Lives in {@code stream.api}, NOT alongside
 * {@link ProblemDetailsAdvice} in {@code api.error}, to keep the
 * {@code stream → api} edge one-way (already established by
 * {@code stream.handshake.StreamCapsHandshakeInterceptor}). If this
 * handler were on {@code ProblemDetailsAdvice} we would form an
 * {@code api ↔ stream} cycle the {@code LayeringTest} ArchUnit rule
 * trips on.
 *
 * <p>The exception itself is a {@link RuntimeException}, so the
 * {@code Throwable} fallback in {@link ProblemDetailsAdvice} would
 * normally turn it into {@code 500 internal_error} — this advice fires
 * first because Spring picks the most-specific @ExceptionHandler.
 */
@RestControllerAdvice
public class StreamProblemDetailsAdvice {

    @ExceptionHandler(StreamFacade.SessionNotRunningException.class)
    @ResponseBody
    public ProblemDetail handleSessionNotRunning(StreamFacade.SessionNotRunningException ex) {
        ProblemDetail pd =
                ProblemDetailsAdvice.build(HttpStatus.CONFLICT, ErrorCode.SESSION_NOT_RUNNING, ex.getMessage());
        pd.setProperty("n", ex.n());
        pd.setProperty("state", ex.state());
        return pd;
    }
}
