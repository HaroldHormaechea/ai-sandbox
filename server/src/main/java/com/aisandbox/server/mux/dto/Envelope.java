package com.aisandbox.server.mux.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * UC-100 — the fresh, typed multiplex envelope
 * {@code {channel, sessionId, type, seq, payload}} carried on every JSON
 * (text) frame of the single {@code /v1/mux} WebSocket. Locked decision (1)
 * of the approved plan.
 *
 * <ul>
 *   <li>{@code channel} — one of {@link MuxChannel#wire()}.</li>
 *   <li>{@code sessionId} — the session number {@code n} for per-session
 *       channels ({@code stream}/{@code conversation}); {@code null} (omitted
 *       from the wire) for {@code events}/{@code control}.</li>
 *   <li>{@code type} — the discriminator <i>within</i> the channel. For control
 *       frames it is {@code hello}/{@code welcome}/…; for data channels it is
 *       the existing payload discriminator ({@code resize}, {@code snapshot},
 *       {@code delta}, {@code answer-batch}, …). It mirrors the {@code type}
 *       property the nested {@code payload} already carries (the existing typed
 *       models are {@code @JsonTypeInfo(property = "type")}), so a client may read
 *       it from either place.</li>
 *   <li>{@code seq} — per-subscription monotonic counter. Advisory <i>within</i>
 *       a channel (a single ordered TCP stream cannot reorder); its real job is
 *       cross-reconnect gap detection so the client can trigger the channel's
 *       authoritative resync on a gap.</li>
 *   <li>{@code payload} — the existing typed model nested verbatim, as a
 *       {@link JsonNode} so the transport layer never needs to know the concrete
 *       type (locked decision (2): payloads carried unchanged).</li>
 * </ul>
 *
 * <p>This lives in {@code mux/dto} (WS-frame DTOs, not REST bodies) — the same
 * precedent as {@code stream/dto} — so it satisfies the
 * {@code profile-java-server-architecture} API-DTO-separation rule.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(String channel, Integer sessionId, String type, long seq, JsonNode payload) {}
