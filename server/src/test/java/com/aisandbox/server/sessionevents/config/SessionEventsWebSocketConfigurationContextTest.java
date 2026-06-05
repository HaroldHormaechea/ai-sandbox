package com.aisandbox.server.sessionevents.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aisandbox.server.identity.ActiveConnectionRegistry;
import com.aisandbox.server.identity.ActiveStreamRegistry;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.handler.SessionEventWebSocketHandler;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * UC-32 — context-load regression guard for {@link SessionEventsWebSocketConfiguration}.
 *
 * <p><b>Why this exists.</b> A first cut of this config autowired an
 * {@code ObjectMapper} bean (and injected {@code WebSocketHandlerAdapter} by
 * type). The reactive (WebFlux) context exposes no {@code ObjectMapper} bean and
 * has two {@code WebSocketHandlerAdapter}s, so the slice failed to wire and took
 * ~30 {@code @SpringBootTest} context loads down with it — a failure the
 * targeted {@code sessionevents.*} unit run (which never starts a Spring context)
 * did not surface; only the full pre-merge gate did.
 *
 * <p>This test reproduces the wiring at the unit tier with a lightweight
 * {@link ApplicationContextRunner}: it provides ONLY the slice's genuine
 * collaborators ({@link SessionEventFacade}, {@link SessionEventBroadcaster},
 * {@link ActiveConnectionRegistry}, {@link ActiveStreamRegistry}) and pointedly
 * NO {@code ObjectMapper} and NO {@code WebSocketHandlerAdapter}. The
 * configuration must still produce both beans. If a future change re-introduces
 * a dependency on either of those context-level beans, this context fails to
 * refresh and the test goes red here — at the unit tier — instead of only in the
 * full {@code :server:test} gate.
 */
class SessionEventsWebSocketConfigurationContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SessionEventFacade.class, () -> mock(SessionEventFacade.class))
            .withBean(SessionEventBroadcaster.class, () -> mock(SessionEventBroadcaster.class))
            .withBean(ActiveConnectionRegistry.class, () -> mock(ActiveConnectionRegistry.class))
            .withBean(ActiveStreamRegistry.class, () -> mock(ActiveStreamRegistry.class))
            .withUserConfiguration(SessionEventsWebSocketConfiguration.class);

    @Test
    void slice_wires_handler_and_mapping_beans_with_no_objectmapper_or_adapter_bean_present() {
        runner.run(context -> {
            // The whole point: the context refreshes even though no ObjectMapper
            // and no WebSocketHandlerAdapter bean are present — the slice is
            // self-contained (the missing-bean regression that broke ~30 context
            // loads would fail this refresh).
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
            assertThat(context).doesNotHaveBean(WebSocketHandlerAdapter.class);

            // Both beans the channel needs are present.
            assertThat(context).hasSingleBean(SessionEventWebSocketHandler.class);
            assertThat(context).hasBean("sessionEventsHandlerMapping");
        });
    }

    @Test
    void handler_mapping_claims_the_events_path() {
        runner.run(context -> {
            HandlerMapping mapping = (HandlerMapping) context.getBean("sessionEventsHandlerMapping");
            assertThat(mapping).isInstanceOf(SimpleUrlHandlerMapping.class);
            assertThat(((SimpleUrlHandlerMapping) mapping).getUrlMap()).containsKey("/v1/sessions/events");
        });
    }
}
