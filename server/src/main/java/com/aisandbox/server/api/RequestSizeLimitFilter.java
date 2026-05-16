package com.aisandbox.server.api;

import com.aisandbox.server.api.error.ErrorCode;
import com.aisandbox.server.api.error.ProblemDetailsAdvice;
import com.aisandbox.server.config.ServerProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects POST / PUT / PATCH requests whose {@code Content-Length} exceeds
 * the configured max (AC20, default 64 KiB) with a 413 body. The webflux
 * codec also enforces an in-memory cap via
 * {@code spring.codec.max-in-memory-size}, but a Content-Length pre-check
 * lets us fail fast without buffering.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequestSizeLimitFilter implements WebFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(ServerProperties props) {
        this.maxBytes = props.limits().maxRequestBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method != null && (method.matches("POST") || method.matches("PUT") || method.matches("PATCH"))) {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            long cl = headers.getContentLength();
            if (cl > maxBytes) {
                return reject(exchange);
            }
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        resp.getHeaders().setContentType(ProblemDetailsAdvice.PROBLEM_JSON);
        byte[] body = ProblemDetailsAdvice.renderJson(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        ErrorCode.REQUEST_TOO_LARGE,
                        "Request body exceeds " + maxBytes + " bytes")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(body)));
    }
}
