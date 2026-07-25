package com.aisandbox.server.mux.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * UC-100 — sealed discriminated union for the {@code control} channel payloads
 * of the {@code /v1/mux} envelope. Its own {@code @JsonTypeInfo} namespace
 * (property {@code type}), disjoint from the data-channel payload hierarchies
 * ({@code ControlMessage}, {@code StreamServerMessage},
 * {@code ConversationServerMessage}/{@code ConversationClientMessage},
 * {@code SessionEventMessage}).
 *
 * <p>Wire flow:
 * <ol>
 *   <li>Client sends {@link Hello} first thing after the socket opens; the
 *       server replies {@link Welcome} echoing the negotiated protocol +
 *       per-channel caps. A protocol mismatch yields an {@link Error} with
 *       {@code code = upgrade_required} and close {@code 4426}.</li>
 *   <li>{@link Subscribe}/{@link Unsubscribe} open and close a logical channel
 *       over the shared socket (no new TCP connection). The server acks with
 *       {@link Subscribed}/{@link Unsubscribed}, or refuses a subscribe with a
 *       {@link SubError} carrying the existing authorization {@code ErrorCode}.</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = MuxControlMessage.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = MuxControlMessage.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = MuxControlMessage.Subscribe.class, name = "subscribe"),
    @JsonSubTypes.Type(value = MuxControlMessage.Unsubscribe.class, name = "unsubscribe"),
    @JsonSubTypes.Type(value = MuxControlMessage.Subscribed.class, name = "subscribed"),
    @JsonSubTypes.Type(value = MuxControlMessage.Unsubscribed.class, name = "unsubscribed"),
    @JsonSubTypes.Type(value = MuxControlMessage.SubError.class, name = "sub-error"),
    @JsonSubTypes.Type(value = MuxControlMessage.Error.class, name = "error")
})
public sealed interface MuxControlMessage
        permits MuxControlMessage.Hello,
                MuxControlMessage.Welcome,
                MuxControlMessage.Subscribe,
                MuxControlMessage.Unsubscribe,
                MuxControlMessage.Subscribed,
                MuxControlMessage.Unsubscribed,
                MuxControlMessage.SubError,
                MuxControlMessage.Error {

    /** Client → server: opening handshake advertising the client's protocol + requested caps. */
    record Hello(String protocol, Map<String, Object> caps) implements MuxControlMessage {}

    /** Server → client: handshake ack with the negotiated protocol + per-channel caps. */
    record Welcome(String protocol, Map<String, Object> caps) implements MuxControlMessage {}

    /** Client → server: open a logical channel. {@code sessionId} required for per-session channels. */
    record Subscribe(String channel, Integer sessionId) implements MuxControlMessage {}

    /** Client → server: close a logical channel. Idempotent (unsubscribing an absent channel is a no-op). */
    record Unsubscribe(String channel, Integer sessionId) implements MuxControlMessage {}

    /** Server → client: subscribe accepted. */
    record Subscribed(String channel, Integer sessionId) implements MuxControlMessage {}

    /** Server → client: unsubscribe completed (emitted after the channel's queued frames are flushed). */
    record Unsubscribed(String channel, Integer sessionId) implements MuxControlMessage {}

    /**
     * Server → client: subscribe refused. {@code code} carries the existing
     * authorization taxonomy ({@code session_not_found}, {@code session_not_running},
     * {@code stream_cap_exceeded}, {@code draining}, …); only that one channel is
     * refused — the socket stays up.
     */
    record SubError(String channel, Integer sessionId, String code, String title, String detail)
            implements MuxControlMessage {}

    /** Server → client: connection-level error (e.g. {@code upgrade_required} before a {@code 4426} close). */
    record Error(String code, String title, String detail) implements MuxControlMessage {}
}
