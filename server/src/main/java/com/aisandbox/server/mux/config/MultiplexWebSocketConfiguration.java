package com.aisandbox.server.mux.config;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.mux.handler.MultiplexWebSocketHandler;
import com.aisandbox.server.mux.service.MuxCodec;
import com.aisandbox.server.mux.service.MuxProtocol;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamBridgeRegistry;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

/**
 * UC-100 — the single {@link SimpleUrlHandlerMapping} for the multiplexed
 * {@code /v1/mux} WebSocket. This is the ONLY realtime WebSocket mapping now:
 * the legacy stream, conversation, and sessions-events mappings were removed
 * (see {@code WebSocketConfiguration} and {@code SessionEventsWebSocketConfiguration}),
 * so an old client's {@code Upgrade: websocket} to a legacy path is no longer
 * claimed for upgrade and falls through to the 426 HTTP route (AC8).
 *
 * <p>The {@code WebSocketHandlerAdapter} is contributed by the (retained)
 * {@code WebSocketConfiguration}; the reactive {@code DispatcherHandler}
 * discovers every {@link HandlerMapping} and adapter bean on its own.
 */
@Configuration
@Profile("!docs-only")
public class MultiplexWebSocketConfiguration {

    @Bean
    public HandlerMapping muxHandlerMapping(MultiplexWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(MuxProtocol.MUX_PATH, handler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    @Bean
    public MultiplexWebSocketHandler multiplexWebSocketHandler(
            StreamFacade streamFacade,
            ConversationFacade conversationFacade,
            ConversationEventMapper conversationMapper,
            SessionEventFacade eventsFacade,
            SessionEventBroadcaster broadcaster,
            StreamControlMessageService controlSvc,
            MuxCodec codec,
            MuxProtocol protocol,
            ServerProperties props,
            ActiveConnectionRegistry connections,
            ActiveStreamRegistry activeStreams,
            StreamRegistryService streamRegistry,
            StreamBridgeRegistry bridgeRegistry) {
        MultiplexWebSocketHandler handler = new MultiplexWebSocketHandler(
                streamFacade,
                conversationFacade,
                conversationMapper,
                eventsFacade,
                broadcaster,
                controlSvc,
                codec,
                protocol,
                props);
        // Late-bound registries (mirrors the legacy handlers): identity resolution,
        // the 4401 revoke index, the connection-level keepalive, and the UC-74
        // bridge teardown registry.
        handler.setActiveConnectionRegistry(connections);
        handler.setActiveStreamRegistry(activeStreams);
        handler.setStreamRegistryService(streamRegistry);
        handler.setStreamBridgeRegistry(bridgeRegistry);
        return handler;
    }
}
