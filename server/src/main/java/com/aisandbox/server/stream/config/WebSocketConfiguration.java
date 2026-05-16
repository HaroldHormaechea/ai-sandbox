package com.aisandbox.server.stream.config;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handler.SessionStreamHandler;
import com.aisandbox.server.stream.handshake.StreamCapsHandshakeInterceptor;
import com.aisandbox.server.stream.handshake.SubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * Reactive {@link WebSocketHandler} wiring. Maps
 * {@code /v1/sessions/{n}/stream} to a {@link SessionStreamHandler}; the
 * subprotocol + cap-check interceptors are referenced here for autowire
 * tracking even though they are consulted on the handler-side path.
 */
@Configuration
@Profile("!docs-only")
public class WebSocketConfiguration {

    @Bean
    public HandlerMapping streamHandlerMapping(SessionStreamHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/v1/sessions/*/stream", handler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public SessionStreamHandler sessionStreamHandler(
            StreamFacade facade,
            StreamControlMessageService controlSvc,
            ServerProperties props,
            SubprotocolHandshakeInterceptor subprotocol,
            StreamCapsHandshakeInterceptor capCheck) {
        // The two interceptors are wired in for forward-compat (a future
        // upgrade hook). Until Reactor-Netty's API exposes a pre-upgrade
        // filter, the handler enforces the subprotocol + caps via the
        // facade directly.
        java.util.Objects.requireNonNull(subprotocol);
        java.util.Objects.requireNonNull(capCheck);
        return new SessionStreamHandler(
                facade,
                controlSvc,
                props.streams().outputRingBytes(),
                props.streams().maxBinaryFrameBytes(),
                props.streams().maxTextFrameBytes());
    }
}
