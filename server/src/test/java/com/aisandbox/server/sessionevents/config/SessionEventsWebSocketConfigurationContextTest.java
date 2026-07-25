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
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * UC-32 context-load regression guard for {@link SessionEventsWebSocketConfiguration},
 * updated for the UC-100 hard cut (AC8).
 *
 * <p><b>What UC-100 changed.</b> The legacy {@code /v1/sessions/events}
 * {@code HandlerMapping} ({@code sessionEventsHandlerMapping}) was REMOVED — the
 * sessions-events feed is now the {@code events} channel of the single
 * {@code /v1/mux} multiplex, and an old client's upgrade to
 * {@code /v1/sessions/events} falls through to the 426 HTTP route
 * ({@code api.LegacyWebSocketGoneController}, covered by
 * {@code LegacyWebSocketGoneControllerTest}). So this slice must NO LONGER
 * contribute a URL mapping for that path.
 *
 * <p><b>What still matters.</b> The original wiring regression this test was
 * written for — the slice must self-wire without autowiring a context-level
 * {@code ObjectMapper} or {@code WebSocketHandlerAdapter} bean (the WebFlux
 * context exposes no {@code ObjectMapper} bean and two adapters) — is unchanged
 * and still guarded here: the retained {@link SessionEventWebSocketHandler} bean
 * (the reference source of the events-channel logic) must still build with only
 * its genuine collaborators present.
 */
class SessionEventsWebSocketConfigurationContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SessionEventFacade.class, () -> mock(SessionEventFacade.class))
            .withBean(SessionEventBroadcaster.class, () -> mock(SessionEventBroadcaster.class))
            .withBean(ActiveConnectionRegistry.class, () -> mock(ActiveConnectionRegistry.class))
            .withBean(ActiveStreamRegistry.class, () -> mock(ActiveStreamRegistry.class))
            .withUserConfiguration(SessionEventsWebSocketConfiguration.class);

    @Test
    void slice_wires_handler_with_no_objectmapper_or_adapter_bean_present() {
        runner.run(context -> {
            // The context refreshes even though no ObjectMapper and no
            // WebSocketHandlerAdapter bean are present — the slice is
            // self-contained (the missing-bean regression that broke ~30 context
            // loads would fail this refresh).
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
            assertThat(context).doesNotHaveBean(WebSocketHandlerAdapter.class);

            // The events-channel handler bean is still wired (retained as the
            // reference source of the logic now lifted into the mux channel).
            assertThat(context).hasSingleBean(SessionEventWebSocketHandler.class);
        });
    }

    @Test
    void legacy_events_handler_mapping_is_removed_by_the_hard_cut() {
        runner.run(context -> {
            // AC8 hard cut — no HandlerMapping claims /v1/sessions/events for a WS
            // upgrade anymore; the path is served by the 426 legacy HTTP route and
            // the feed lives on /v1/mux. The old mapping bean must be gone.
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("sessionEventsHandlerMapping");
        });
    }
}
