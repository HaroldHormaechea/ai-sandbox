package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.config.ServerProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * AC20 — POST / PUT / PATCH bodies > 64 KiB return 413 with a
 * Problem-Details body and short-circuit the filter chain.
 */
class RequestSizeLimitFilterTest {

    private static ServerProperties props(int maxBytes) {
        return new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, maxBytes),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
    }

    @Test
    void rejects_post_above_limit_with_413_problem_json() {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(65536));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(70_000);
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/clients").headers(headers).contentType(MediaType.APPLICATION_JSON));
        WebFilterChain chain = e -> {
            throw new AssertionError("filter should have short-circuited before invoking chain");
        };

        filter.filter(ex, chain).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(ex.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void allows_post_under_limit() {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(65536));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(1000);
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/clients").headers(headers).contentType(MediaType.APPLICATION_JSON));
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(ex, chain).block();

        assertThat(ex.getResponse().getStatusCode()).isNull(); // chain didn't set anything
    }

    @Test
    void ignores_get_requests_regardless_of_content_length() {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(props(10));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(99_999);
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/sessions").headers(headers));
        WebFilterChain chain = e -> Mono.empty();

        filter.filter(ex, chain).block();

        assertThat(ex.getResponse().getStatusCode()).isNull();
    }
}
