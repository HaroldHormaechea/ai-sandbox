package com.aisandbox.server.stream.service;

import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

/**
 * Parses incoming text frames as {@link ControlMessage} subtypes and
 * renders outgoing frames to JSON bytes. The mouse handling here is
 * intentionally JSON-only; we do NOT honour xterm-SGR escapes from
 * arbitrary client input (which would let a hostile cert smuggle escapes
 * past the WebSocket sandbox).
 */
@Service
public class StreamControlMessageService {

    private final ObjectMapper json = new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ControlMessage parse(String textFrame) {
        try {
            return json.readValue(textFrame, ControlMessage.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            throw new IllegalArgumentException("Invalid control message JSON: " + jpe.getOriginalMessage());
        }
    }

    public byte[] serialize(ControlMessage msg) {
        try {
            return json.writeValueAsBytes(msg);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return ("{\"type\":\"error\",\"code\":\"serialize_failed\",\"detail\":\""
                            + jpe.getMessage().replace("\"", "'") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * UC-21 — dedicated serialization path for the server→client
     * {@link StreamServerMessage} hierarchy (challenger guardrail #3:
     * {@link #serialize(ControlMessage)} does not cover it — the two hierarchies
     * carry separate {@code @JsonSubTypes} discriminators).
     */
    public byte[] serialize(StreamServerMessage msg) {
        try {
            return json.writeValueAsBytes(msg);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return ("{\"type\":\"error\",\"code\":\"serialize_failed\",\"detail\":\""
                            + jpe.getMessage().replace("\"", "'") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * UC-37 — parse an incoming conversation channel text frame as a
     * {@link ConversationClientMessage}. Separate {@code @JsonTypeInfo}
     * namespace from {@link #parse(String)} (the binary stream's control
     * frames), so the {@code select-target} / {@code enumerate-targets} /
     * {@code close} discriminators that exist in BOTH hierarchies never cross.
     */
    public ConversationClientMessage parseConversation(String textFrame) {
        try {
            return json.readValue(textFrame, ConversationClientMessage.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            throw new IllegalArgumentException("Invalid conversation message JSON: " + jpe.getOriginalMessage());
        }
    }

    /**
     * UC-37 — dedicated serialization path for the server→client
     * {@link ConversationServerMessage} hierarchy. Distinct from
     * {@link #serialize(StreamServerMessage)} and {@link #serialize(ControlMessage)}
     * (separate {@code @JsonSubTypes} namespaces).
     */
    public byte[] serialize(ConversationServerMessage msg) {
        try {
            return json.writeValueAsBytes(msg);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return ("{\"type\":\"error\",\"code\":\"serialize_failed\",\"detail\":\""
                            + jpe.getMessage().replace("\"", "'") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Translate a {@link ControlMessage.MouseControl} into the xterm SGR
     * mouse escape bytes that tmux's {@code mouse on} mode expects on its
     * stdin: {@code ESC [ < button ; x ; y M|m}. Released events use 'm';
     * presses use 'M'. The same buttons drive scroll-wheel + drag (tmux
     * disambiguates by tracking state).
     */
    public byte[] toXtermSgr(ControlMessage.MouseControl m) {
        char terminator = "release".equalsIgnoreCase(m.action()) ? 'm' : 'M';
        int b = m.button() & 0xFF;
        if (m.modifiers() != 0) {
            // Modifier bits per xterm: shift=4, alt=8, ctrl=16. We were
            // given 1/2/4; remap.
            int xm = 0;
            if ((m.modifiers() & 1) != 0) xm |= 4;
            if ((m.modifiers() & 2) != 0) xm |= 8;
            if ((m.modifiers() & 4) != 0) xm |= 16;
            b |= xm;
        }
        String seq = "[<" + b + ";" + m.x() + ";" + m.y() + terminator;
        return seq.getBytes(StandardCharsets.UTF_8);
    }
}
