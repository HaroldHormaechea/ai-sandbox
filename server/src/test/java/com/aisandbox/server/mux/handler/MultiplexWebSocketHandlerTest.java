package com.aisandbox.server.mux.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mux.FakeMuxSession;
import com.aisandbox.server.mux.service.MuxCodec;
import com.aisandbox.server.mux.service.MuxProtocol;
import com.aisandbox.server.sessionevents.dto.SessionEventMessage;
import com.aisandbox.server.sessionevents.facade.SessionEventFacade;
import com.aisandbox.server.sessionevents.service.SessionEventBroadcaster;
import com.aisandbox.server.stream.facade.ConversationFacade;
import com.aisandbox.server.stream.facade.StreamFacade;
import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import com.aisandbox.server.stream.service.TranscriptTailService;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * UC-100 — the {@code /v1/mux} handler transport contract. Drives
 * {@code handler.handle(session)} with a {@link FakeMuxSession} (records
 * outbound, feeds inbound via a sink) and pins:
 *
 * <ul>
 *   <li>AC3 — the {@code hello}/{@code welcome} version + caps handshake.</li>
 *   <li>AC8 — a version mismatch → {@code error{upgrade_required}} + close 4426.</li>
 *   <li>AC2/AC4 — the per-subscribe {@code authorizeOpen} → {@code sub-error}
 *       taxonomy (the four codes) mapped from the existing authorization union.</li>
 *   <li>AC4 — subscribe/unsubscribe lifecycle (open on subscribe, ack, snapshot).</li>
 *   <li>AC5 — unsubscribe tears down the channel-session tail (no leaked tail).</li>
 *   <li>AC6 — idempotent re-subscribe (no duplicate channel) + idempotent
 *       unsubscribe of an absent channel.</li>
 *   <li>anonymous identity → close 1008.</li>
 * </ul>
 */
class MultiplexWebSocketHandlerTest {

    private static final String HELLO = env("control", "{\"type\":\"hello\",\"protocol\":\"mux.v1\"}");

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
    }

    private static String env(String channel, String payloadJson) {
        return "{\"channel\":\"" + channel + "\",\"seq\":0,\"payload\":" + payloadJson + "}";
    }

    private static String subscribe(String channel, Integer sessionId) {
        String p = "{\"type\":\"subscribe\",\"channel\":\"" + channel + "\""
                + (sessionId == null ? "" : (",\"sessionId\":" + sessionId)) + "}";
        return env("control", p);
    }

    private static String unsubscribe(String channel, Integer sessionId) {
        String p = "{\"type\":\"unsubscribe\",\"channel\":\"" + channel + "\""
                + (sessionId == null ? "" : (",\"sessionId\":" + sessionId)) + "}";
        return env("control", p);
    }

    /** A wired handler + driven session. */
    private static final class Fixture {
        final MultiplexWebSocketHandler handler;
        final FakeMuxSession session;
        final Sinks.Many<WebSocketMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();
        Disposable sub;

        final StreamFacade streamFacade = mock(StreamFacade.class);
        final ConversationFacade conversationFacade = mock(ConversationFacade.class);
        final ConversationEventMapper mapper = mock(ConversationEventMapper.class);
        final SessionEventFacade eventsFacade = mock(SessionEventFacade.class);
        final SessionEventBroadcaster broadcaster = mock(SessionEventBroadcaster.class);

        Fixture(boolean withIdentity) {
            MuxCodec codec = new MuxCodec();
            ServerProperties props = mock(ServerProperties.class);
            when(props.streams())
                    .thenReturn(new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15));
            MuxProtocol protocol = new MuxProtocol(props);

            handler = new MultiplexWebSocketHandler(
                    streamFacade,
                    conversationFacade,
                    mapper,
                    eventsFacade,
                    broadcaster,
                    new StreamControlMessageService(),
                    codec,
                    protocol,
                    props);

            session = new FakeMuxSession();
            if (withIdentity) {
                session.withAttr(MultiplexWebSocketHandler.IDENTITY_ATTR, identity());
            }
            session.incoming = inbound.asFlux();
        }

        void start() {
            sub = handler.handle(session).subscribe();
        }

        void push(String frame) {
            inbound.tryEmitNext(session.textMessage(frame));
        }

        void stop() {
            if (sub != null) {
                sub.dispose();
            }
            inbound.tryEmitComplete();
        }
    }

    // ──────────────────────── handshake (AC3 / AC8) ────────────────────────

    @Test
    void hello_is_answered_with_welcome_and_negotiated_caps() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(HELLO);

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"welcome\"") && t.contains("\"protocol\":\"mux.v1\""));
        assertThat(f.session.sentText()).anyMatch(t -> t.contains("maxBinaryFrameBytes"));
        f.stop();
    }

    @Test
    void version_mismatch_emits_upgrade_required_and_closes_4426() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(env("control", "{\"type\":\"hello\",\"protocol\":\"mux.v0\"}"));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"error\"") && t.contains("\"code\":\"upgrade_required\""));
        assertThat(f.session.closedWith).isNotNull();
        assertThat(f.session.closedWith.getCode()).isEqualTo(MuxProtocol.CLOSE_UPGRADE_REQUIRED);
        f.stop();
    }

    @Test
    void anonymous_identity_is_closed_with_policy_violation() {
        Fixture f = new Fixture(false); // no identity stashed, no ActiveConnectionRegistry wired
        f.start();

        assertThat(f.session.closedWith).isNotNull();
        assertThat(f.session.closedWith.getCode()).isEqualTo(1008); // POLICY_VIOLATION
        f.stop();
    }

    // ──────────────────────── subscribe validation ────────────────────────

    @Test
    void subscribe_before_hello_is_refused_with_no_handshake() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(subscribe("events", null));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"no_handshake\""));
        f.stop();
    }

    @Test
    void subscribe_to_control_channel_is_refused() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(HELLO);
        f.push(subscribe("control", null));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"bad_channel\""));
        f.stop();
    }

    @Test
    void per_session_subscribe_without_session_id_is_refused() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(HELLO);
        f.push(subscribe("stream", null));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"bad_request\""));
        f.stop();
    }

    // ──────────────────────── authorize → sub-error taxonomy (AC2 / AC4) ────────────────────────

    @Test
    void stream_subscribe_maps_session_not_found() {
        Fixture f = new Fixture(true);
        when(f.streamFacade.authorizeOpen(eq(5), any())).thenReturn(new StreamFacade.SessionNotFound(5));
        f.start();
        f.push(HELLO);
        f.push(subscribe("stream", 5));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"session_not_found\""));
        f.stop();
    }

    @Test
    void stream_subscribe_maps_not_running() {
        Fixture f = new Fixture(true);
        when(f.streamFacade.authorizeOpen(eq(5), any())).thenReturn(new StreamFacade.NotRunning(5, "stopped"));
        f.start();
        f.push(HELLO);
        f.push(subscribe("stream", 5));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"session_not_running\""));
        f.stop();
    }

    @Test
    void stream_subscribe_maps_cap_exceeded() {
        Fixture f = new Fixture(true);
        when(f.streamFacade.authorizeOpen(eq(5), any())).thenReturn(new StreamFacade.CapExceeded("per-client"));
        f.start();
        f.push(HELLO);
        f.push(subscribe("stream", 5));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"stream_cap_exceeded\""));
        f.stop();
    }

    @Test
    void stream_subscribe_maps_draining() {
        Fixture f = new Fixture(true);
        when(f.streamFacade.authorizeOpen(eq(5), any())).thenReturn(new StreamFacade.Draining());
        f.start();
        f.push(HELLO);
        f.push(subscribe("stream", 5));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"draining\""));
        f.stop();
    }

    // ──────────────────────── events lifecycle (AC4 / AC6) ────────────────────────

    @Test
    void events_subscribe_acks_and_sends_snapshot() {
        Fixture f = new Fixture(true);
        when(f.eventsFacade.authorizeSubscribe(any())).thenReturn(SessionEventFacade.SubscribeDecision.ALLOWED);
        when(f.eventsFacade.snapshot()).thenReturn(new SessionEventMessage.Snapshot(List.of()));
        f.start();
        f.push(HELLO);
        f.push(subscribe("events", null));

        assertThat(f.session.sentText()).anyMatch(t -> t.contains("\"type\":\"subscribed\""));
        assertThat(f.session.sentText()).anyMatch(t -> t.contains("\"type\":\"snapshot\""));
        f.stop();
    }

    @Test
    void events_subscribe_refused_when_draining() {
        Fixture f = new Fixture(true);
        when(f.eventsFacade.authorizeSubscribe(any())).thenReturn(SessionEventFacade.SubscribeDecision.DRAINING);
        f.start();
        f.push(HELLO);
        f.push(subscribe("events", null));

        assertThat(f.session.sentText())
                .anyMatch(t -> t.contains("\"type\":\"sub-error\"") && t.contains("\"code\":\"draining\""));
        f.stop();
    }

    @Test
    void re_subscribe_to_a_live_events_channel_is_idempotent() {
        Fixture f = new Fixture(true);
        when(f.eventsFacade.authorizeSubscribe(any())).thenReturn(SessionEventFacade.SubscribeDecision.ALLOWED);
        when(f.eventsFacade.snapshot()).thenReturn(new SessionEventMessage.Snapshot(List.of()));
        f.start();
        f.push(HELLO);
        f.push(subscribe("events", null));
        f.push(subscribe("events", null)); // AC6 — dedupe: re-ack, no second channel session

        long subscribedAcks = f.session.sentText().stream()
                .filter(t -> t.contains("\"type\":\"subscribed\""))
                .count();
        assertThat(subscribedAcks).isEqualTo(2); // re-ack is sent
        // But the events subscriber (broadcaster registration) was created only ONCE.
        verify(f.broadcaster, times(1)).register(any());
        f.stop();
    }

    @Test
    void unsubscribe_of_absent_channel_still_acks() {
        Fixture f = new Fixture(true);
        f.start();
        f.push(HELLO);
        f.push(unsubscribe("events", null));

        assertThat(f.session.sentText()).anyMatch(t -> t.contains("\"type\":\"unsubscribed\""));
        f.stop();
    }

    @Test
    void unsubscribe_of_live_events_channel_unregisters_from_broadcaster() {
        Fixture f = new Fixture(true);
        when(f.eventsFacade.authorizeSubscribe(any())).thenReturn(SessionEventFacade.SubscribeDecision.ALLOWED);
        when(f.eventsFacade.snapshot()).thenReturn(new SessionEventMessage.Snapshot(List.of()));
        f.start();
        f.push(HELLO);
        f.push(subscribe("events", null));
        f.push(unsubscribe("events", null));

        verify(f.broadcaster, timeout(2000)).unregister(any());
        assertThat(f.session.sentText()).anyMatch(t -> t.contains("\"type\":\"unsubscribed\""));
        f.stop();
    }

    // ──────────────────────── AC5 — conversation tail teardown on unsubscribe ────────────────────────

    @Test
    void unsubscribe_tears_down_the_conversation_tail_no_leak() throws Exception {
        Fixture f = new Fixture(true);
        when(f.conversationFacade.authorizeOpen(eq(7), any())).thenReturn(new StreamFacade.Allowed());

        TranscriptTailService.Tail tail = mock(TranscriptTailService.Tail.class);
        CountDownLatch parkRead = new CountDownLatch(1);
        // Park the tail pump so it does not reach EOF on its own; the ONLY teardown
        // path exercised is the client unsubscribe.
        when(tail.readLine()).thenAnswer(inv -> {
            parkRead.await(5, TimeUnit.SECONDS);
            return null;
        });
        when(f.conversationFacade.startTail(eq(7), any())).thenReturn(tail);

        f.start();
        f.push(HELLO);
        f.push(subscribe("conversation", 7));

        // Tail started (channel producing) …
        verify(f.conversationFacade, timeout(2000)).startTail(eq(7), any());

        // … now the client unsubscribes → the channel session must tear the tail down.
        f.push(unsubscribe("conversation", 7));

        verify(tail, timeout(2000)).close();
        verify(f.conversationFacade, timeout(2000)).auditClose(eq(7), any(), eq(1000), eq("unsubscribe"));
        parkRead.countDown();
        f.stop();
    }
}
