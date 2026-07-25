package com.aisandbox.server.mux.dto;

/**
 * UC-100 — the four logical channels multiplexed over the single
 * {@code /v1/mux} WebSocket. Each envelope carries one of these in its
 * {@code channel} field; the compact binary frame carries the
 * {@link #wireByte()} in its first header byte.
 *
 * <ul>
 *   <li>{@link #CONTROL} — the handshake + subscription lifecycle
 *       ({@code hello}/{@code welcome}/{@code subscribe}/{@code unsubscribe}/
 *       {@code subscribed}/{@code unsubscribed}/{@code sub-error}/{@code error}).</li>
 *   <li>{@link #STREAM} — per-session terminal PTY (the only channel that uses
 *       binary frames for stdout/stdin).</li>
 *   <li>{@link #CONVERSATION} — per-session structured conversation (JSON text).</li>
 *   <li>{@link #EVENTS} — the global sessions-list push feed (JSON text).</li>
 * </ul>
 */
public enum MuxChannel {
    CONTROL("control", (byte) 0),
    STREAM("stream", (byte) 1),
    CONVERSATION("conversation", (byte) 2),
    EVENTS("events", (byte) 3);

    private final String wire;
    private final byte wireByte;

    MuxChannel(String wire, byte wireByte) {
        this.wire = wire;
        this.wireByte = wireByte;
    }

    /** The JSON envelope {@code channel} value. */
    public String wire() {
        return wire;
    }

    /** The first byte of the compact binary frame header. */
    public byte wireByte() {
        return wireByte;
    }

    /** {@code true} for the per-session channels ({@link #STREAM}, {@link #CONVERSATION}). */
    public boolean isPerSession() {
        return this == STREAM || this == CONVERSATION;
    }

    /** Resolve from the JSON {@code channel} value, or {@code null} when unknown. */
    public static MuxChannel fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (MuxChannel c : values()) {
            if (c.wire.equals(wire)) {
                return c;
            }
        }
        return null;
    }

    /** Resolve from the binary header byte, or {@code null} when unknown. */
    public static MuxChannel fromWireByte(byte b) {
        for (MuxChannel c : values()) {
            if (c.wireByte == b) {
                return c;
            }
        }
        return null;
    }
}
