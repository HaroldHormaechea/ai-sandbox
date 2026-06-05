package com.aisandbox.server.sessionevents.config;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.handler.SessionEventWebSocketHandler;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

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
 * <p><b>Adapter reuse.</b> This config does NOT declare or inject a
 * {@code WebSocketHandlerAdapter}: the context already has one (Spring Boot's
 * autoconfigured {@code webFluxWebSocketHandlerAdapter}, plus the stream
 * config's own), and the reactive {@code DispatcherHandler} discovers every
 * {@link HandlerMapping} and adapter bean on its own. Injecting the adapter by
 * type would be ambiguous (two beans of that type exist), and re-declaring one
 * would conflict — so the mapping bean takes only the handler, exactly like the
 * stream config's mapping bean.
 */
@Configuration
@Profile("!docs-only")
public class SessionEventsWebSocketConfiguration {

    /** Map {@code /v1/sessions/events} to the events handler. */
    @Bean
    public HandlerMapping sessionEventsHandlerMapping(SessionEventWebSocketHandler handler) {
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
            ActiveStreamRegistry streamRegistry) {
        // Build the frame serializer locally rather than autowiring an
        // ObjectMapper bean — this reactive (WebFlux) context exposes no
        // ObjectMapper bean, so an injected dependency fails the whole context
        // at startup. Mirrors how the stream package serializes its WS frames
        // (a self-constructed mapper, no bean dependency) without importing it.
        // JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS disabled ⇒ Row.startedAt
        // serializes as an ISO-8601 string, matching the REST list.
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
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
