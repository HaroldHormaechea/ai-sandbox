package com.aisandbox.server.mux.channel;

import com.aisandbox.server.mux.dto.MuxChannel;

/**
 * UC-100 — the callback a {@link MuxChannelSession} uses to ask the
 * {@link com.aisandbox.server.mux.handler.MultiplexWebSocketHandler} to tear
 * down its own channel (a client {@code close} control, or a producer reaching
 * EOF — the PTY process exiting, the transcript tail ending). The handler runs
 * the same teardown path as a client-initiated {@code unsubscribe}: stop the
 * session, flush its queued frames, emit {@code unsubscribed}, and release the
 * cap accounting.
 */
public interface ChannelHost {

    /** Request that the given channel be torn down (never the whole socket). */
    void requestChannelClose(MuxChannel channel, Integer sessionId, String reason);
}
