package com.aisandbox.server.stream.config;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.handler.SessionConversationHandler;
import com.aisandbox.server.stream.handler.SessionStreamHandler;
import com.aisandbox.server.stream.handshake.ConversationSubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.handshake.StreamCapsHandshakeInterceptor;
import com.aisandbox.server.stream.handshake.SubprotocolHandshakeInterceptor;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * Reactive {@link WebSocketHandler} wiring. Maps
 * {@code /v1/sessions/{n}/stream} to a {@link SessionStreamHandler} (binary PTY)
 * and {@code /v1/sessions/{n}/conversation} to a {@link SessionConversationHandler}
 * (UC-37 structured-conversation channel); the subprotocol + cap-check
 * interceptors are referenced here for autowire tracking even though they are
 * consulted on the handler-side path.
 */
@Configuration
@Profile("!docs-only")
public class WebSocketConfiguration {

    // UC-100 — the legacy /v1/sessions/*/stream + /v1/sessions/*/conversation
    // HandlerMappings were REMOVED (hard cut, AC8). All realtime traffic now
    // multiplexes over /v1/mux (mux.config.MultiplexWebSocketConfiguration); an
    // old client's Upgrade: websocket to a legacy path is no longer claimed for
    // upgrade and falls through to the 426 HTTP route
    // (api.LegacyWebSocketGoneController). The SessionStreamHandler /
    // SessionConversationHandler beans below are retained only as the reference
    // source of the per-channel logic now driven by the mux.channel.* sessions;
    // they are no longer bound to any URL.

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public SessionStreamHandler sessionStreamHandler(
            StreamFacade facade,
            StreamControlMessageService controlSvc,
            ActiveConnectionRegistry connections,
            ActiveStreamRegistry streamRegistry,
            StreamBridgeRegistry bridgeRegistry,
            ServerProperties props,
            SubprotocolHandshakeInterceptor subprotocol,
            StreamCapsHandshakeInterceptor capCheck) {
        // The two interceptors are wired in for forward-compat (a future
        // upgrade hook). Until Reactor-Netty's API exposes a pre-upgrade
        // filter, the handler enforces the subprotocol + caps via the
        // facade directly.
        java.util.Objects.requireNonNull(subprotocol);
        java.util.Objects.requireNonNull(capCheck);
        SessionStreamHandler handler = new SessionStreamHandler(
                facade,
                controlSvc,
                props.streams().outputRingBytes(),
                props.streams().maxBinaryFrameBytes(),
                props.streams().maxTextFrameBytes());
        // Inject the connection registry post-construct so the unit-test
        // constructor signature stays stable; without this the handler
        // cannot resolve identity from the Netty channel id and every
        // upgrade closes with POLICY_VIOLATION.
        handler.setActiveConnectionRegistry(connections);
        // UC04 § B2 — index live WS sessions by fingerprint so the
        // ActiveConnectionRegistry.revoke(...) orchestration can issue
        // a graceful close (close code 4401, reason "revoked") before
        // tearing down the underlying TCP channel.
        handler.setActiveStreamRegistry(streamRegistry);
        // UC-74 — register live PTY bridges so GracefulShutdownHandler can
        // destroy any survivors deterministically on shutdown (AC4).
        handler.setStreamBridgeRegistry(bridgeRegistry);
        return handler;
    }

    /**
     * UC-37 — the structured-conversation handler. Mirrors {@link #sessionStreamHandler}:
     * the connection/stream registries are injected post-construct so the
     * unit-test constructor stays stable, and the subprotocol gate is enforced
     * handler-side (D1) since Reactor-Netty exposes no pre-upgrade filter.
     */
    @Bean
    public SessionConversationHandler sessionConversationHandler(
            ConversationFacade conversationFacade,
            StreamControlMessageService controlSvc,
            ConversationEventMapper mapper,
            ConversationSubprotocolHandshakeInterceptor conversationSubprotocol,
            ActiveConnectionRegistry connections,
            ActiveStreamRegistry streamRegistry,
            ServerProperties props) {
        SessionConversationHandler handler = new SessionConversationHandler(
                conversationFacade,
                controlSvc,
                mapper,
                conversationSubprotocol,
                props.streams().maxTextFrameBytes());
        handler.setActiveConnectionRegistry(connections);
        handler.setActiveStreamRegistry(streamRegistry);
        return handler;
    }
}
