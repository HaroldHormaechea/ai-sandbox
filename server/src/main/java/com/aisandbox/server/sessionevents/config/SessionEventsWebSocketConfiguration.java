package com.aisandbox.server.sessionevents.config;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.handler.SessionEventWebSocketHandler;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * UC-32 — wiring for the live sessions-list push channel
 * ({@code /v1/sessions/events}).
 *
 * <p>This is a deliberately separate slice from
 * {@code com.aisandbox.server.stream.config.WebSocketConfiguration}: it has NO
 * import of any {@code stream} package type, so the no-cycles boundary between
 * the events channel and the terminal stream stays clean. It contributes its own
 * {@link SimpleUrlHandlerMapping} for the disjoint {@code /v1/sessions/events}
 * path (the stream config owns the {@code /v1/sessions/{n}/stream} pattern).
 *
 * <p><b>Handler-mapping order.</b> {@code HIGHEST_PRECEDENCE + 10} — just below
 * the stream mapping ({@code HIGHEST_PRECEDENCE}) and well above the REST
 * {@code RequestMappingHandlerMapping}, so the WebSocket upgrade for
 * {@code /v1/sessions/events} is claimed here rather than by the
 * {@code /v1/sessions/{n}} REST controller. The two WS patterns are disjoint
 * ({@code events} is not a session number), so the ordering only matters versus
 * the REST mapping.
 *
 * <p><b>Adapter reuse.</b> The reactive {@link WebSocketHandlerAdapter} is a
 * singleton in the application context; this config obtains it <i>by type</i>
 * (declaring it only as a constructor-injected dependency of the mapping bean to
 * assert its presence) rather than declaring a second one — a duplicate adapter
 * bean would be ambiguous, and importing the {@code stream} config's bean would
 * reintroduce the dependency this slice avoids.
 */
@Configuration
@Profile("!docs-only")
public class SessionEventsWebSocketConfiguration {

    /**
     * Map {@code /v1/sessions/events} to the events handler. {@code adapter} is
     * injected purely to assert the global {@link WebSocketHandlerAdapter} exists
     * (obtained by type, not re-declared here).
     */
    @Bean
    public HandlerMapping sessionEventsHandlerMapping(
            SessionEventWebSocketHandler handler, WebSocketHandlerAdapter adapter) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/v1/sessions/events", handler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return mapping;
    }

    @Bean
    public SessionEventWebSocketHandler sessionEventWebSocketHandler(
            SessionEventFacade facade,
            SessionEventBroadcaster broadcaster,
            ActiveConnectionRegistry connections,
            ActiveStreamRegistry streamRegistry,
            ObjectMapper objectMapper) {
        SessionEventWebSocketHandler handler = new SessionEventWebSocketHandler(facade, broadcaster, objectMapper);
        // Inject the connection registry post-construct so identity resolves
        // from the Netty channel id (without it every upgrade closes
        // POLICY_VIOLATION); the stream registry indexes this feed for the 4401
        // revocation path shared with the terminal stream.
        handler.setActiveConnectionRegistry(connections);
        handler.setActiveStreamRegistry(streamRegistry);
        return handler;
    }
}
