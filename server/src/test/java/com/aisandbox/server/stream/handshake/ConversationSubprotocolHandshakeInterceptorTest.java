package com.aisandbox.server.stream.handshake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-37 AC21 — the conversation channel is version-gated by the mandatory
 * {@code Sec-WebSocket-Protocol: ai-sandbox.conv.v1} subprotocol. The actual
 * gate is applied handler-side (see {@code SessionConversationHandlerTest}); the
 * parsing it relies on lives in {@link ConversationSubprotocolHandshakeInterceptor#advertises}
 * and is exhaustively unit-tested here (case-insensitive, whitespace-tolerant,
 * comma-list aware), mirroring the binary stream's {@code SubprotocolHandshakeInterceptor}.
 */
class ConversationSubprotocolHandshakeInterceptorTest {

    private static final String TOKEN = ConversationSubprotocolHandshakeInterceptor.SUBPROTOCOL;

    @Test
    void subprotocol_token_is_the_conversation_specific_value() {
        // Distinct from the binary stream's ai-sandbox.v1 — pin it so a drift surfaces here.
        assertThat(TOKEN).isEqualTo("ai-sandbox.conv.v1");
    }

    @Test
    void null_or_empty_headers_do_not_advertise() {
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(null, TOKEN))
                .isFalse();
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(List.of(), TOKEN))
                .isFalse();
    }

    @Test
    void exact_single_value_advertises() {
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(List.of(TOKEN), TOKEN))
                .isTrue();
    }

    @Test
    void comma_separated_list_with_whitespace_advertises() {
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(
                        List.of("foo, " + TOKEN + " , bar"), TOKEN))
                .isTrue();
    }

    @Test
    void matching_is_case_insensitive() {
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(
                        List.of("AI-SANDBOX.CONV.V1"), TOKEN))
                .isTrue();
    }

    @Test
    void a_different_subprotocol_does_not_advertise() {
        // The binary stream's token must NOT satisfy the conversation gate.
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(List.of("ai-sandbox.v1"), TOKEN))
                .isFalse();
    }

    @Test
    void null_entries_in_the_header_list_are_skipped() {
        java.util.List<String> headers = new java.util.ArrayList<>();
        headers.add(null);
        headers.add(TOKEN);
        assertThat(ConversationSubprotocolHandshakeInterceptor.advertises(headers, TOKEN))
                .isTrue();
    }
}
