package com.aisandbox.server.stream.handshake;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.facade.StreamFacade.AuthorizeResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Runs the stream-cap check before the WebSocket upgrade completes
 * (AC28). Result types map directly to HTTP responses:
 *
 * <ul>
 *   <li>{@link StreamFacade.Allowed} → continue with upgrade</li>
 *   <li>{@link StreamFacade.SessionNotFound} → 404 + Problem-Details</li>
 *   <li>{@link StreamFacade.NotRunning} → 409 + Problem-Details (session_not_running, UC04 AC37)</li>
 *   <li>{@link StreamFacade.CapExceeded} → 503 + Problem-Details (stream_cap_exceeded)</li>
 *   <li>{@link StreamFacade.Draining} → 503 + Problem-Details (draining)</li>
 * </ul>
 */
@Component
public class StreamCapsHandshakeInterceptor {

    private final StreamFacade facade;

    public StreamCapsHandshakeInterceptor(StreamFacade facade) {
        this.facade = facade;
    }

    public AuthorizeResult check(int n, ClientIdentity identity) {
        return facade.authorizeOpen(n, identity);
    }

    public byte[] renderProblem(AuthorizeResult result, ServerHttpResponse resp) {
        switch (result) {
            case StreamFacade.Allowed a -> {
                return new byte[0];
            }
            case StreamFacade.SessionNotFound nf -> {
                resp.setStatusCode(HttpStatus.NOT_FOUND);
                return body(HttpStatus.NOT_FOUND, ErrorCode.SESSION_NOT_FOUND, "n=" + nf.n());
            }
            case StreamFacade.NotRunning nr -> {
                resp.setStatusCode(HttpStatus.CONFLICT);
                return body(
                        HttpStatus.CONFLICT, ErrorCode.SESSION_NOT_RUNNING, "session " + nr.n() + " is " + nr.state());
            }
            case StreamFacade.CapExceeded ce -> {
                resp.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return body(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.STREAM_CAP_EXCEEDED, ce.scope());
            }
            case StreamFacade.Draining d -> {
                resp.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return body(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DRAINING, "server is shutting down");
            }
        }
    }

    private static byte[] body(HttpStatus status, ErrorCode code, String detail) {
        return ProblemDetailsAdvice.renderJson(status, code, detail).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
