package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC31 — text frame JSON shapes carry resize / mouse / close / error
 * control messages. The xterm-SGR rendering powers AC36 (mouse forwarding
 * through to tmux).
 *
 * <p>UC-21 AC#13 — extends the surface with the agent-switcher protocol: the
 * new inbound {@code enumerate-targets} / {@code select-target} client frames
 * and the dedicated outbound {@link StreamServerMessage} serialization path
 * ({@code targets} / {@code target-selected} / {@code error}), whose hierarchy
 * carries its OWN {@code @JsonSubTypes} discriminator (challenger guardrail #3).
 */
class StreamControlMessageServiceTest {

    private final StreamControlMessageService svc = new StreamControlMessageService();

    @Test
    void parses_resize_frame() {
        ControlMessage msg = svc.parse("{\"type\":\"resize\",\"cols\":120,\"rows\":40}");
        assertThat(msg).isInstanceOfSatisfying(ControlMessage.Resize.class, r -> {
            assertThat(r.cols()).isEqualTo(120);
            assertThat(r.rows()).isEqualTo(40);
        });
    }

    @Test
    void parses_mouse_frame() {
        ControlMessage msg =
                svc.parse("{\"type\":\"mouse\",\"x\":10,\"y\":20,\"button\":0,\"modifiers\":0,\"action\":\"press\"}");
        assertThat(msg).isInstanceOfSatisfying(ControlMessage.MouseControl.class, m -> {
            assertThat(m.x()).isEqualTo(10);
            assertThat(m.y()).isEqualTo(20);
            assertThat(m.button()).isZero();
            assertThat(m.action()).isEqualTo("press");
        });
    }

    @Test
    void parses_close_and_error_frames() {
        ControlMessage close = svc.parse("{\"type\":\"close\",\"reason\":\"bye\"}");
        assertThat(close).isInstanceOfSatisfying(ControlMessage.CloseControl.class, c -> {
            assertThat(c.reason()).isEqualTo("bye");
        });
        ControlMessage err = svc.parse("{\"type\":\"error\",\"code\":\"bad\",\"title\":\"t\",\"detail\":\"d\"}");
        assertThat(err).isInstanceOf(ControlMessage.ErrorMessage.class);
    }

    @Test
    void rejects_unknown_type() {
        assertThatThrownBy(() -> svc.parse("{\"type\":\"nope\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_json() {
        assertThatThrownBy(() -> svc.parse("not-json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serialize_round_trips_an_error() {
        byte[] body = svc.serialize(new ControlMessage.ErrorMessage("stream_overflow", "Overflow", "details"));
        String s = new String(body, StandardCharsets.UTF_8);
        assertThat(s).contains("\"code\":\"stream_overflow\"");
        assertThat(s).contains("\"title\":\"Overflow\"");
    }

    @Test
    void xterm_sgr_renders_press_with_M_terminator() {
        byte[] bytes = svc.toXtermSgr(new ControlMessage.MouseControl(7, 4, 0, 0, "press"));
        String seq = new String(bytes, StandardCharsets.UTF_8);
        // ESC [ < button ; x ; y M — press uses 'M', release uses 'm'
        assertThat(seq).isEqualTo("[<0;7;4M");
        assertThat(bytes[0]).isEqualTo((byte) 0x1B);
    }

    @Test
    void xterm_sgr_renders_release_with_m_terminator() {
        byte[] bytes = svc.toXtermSgr(new ControlMessage.MouseControl(1, 2, 0, 0, "release"));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).endsWith("m");
        assertThat(bytes[0]).isEqualTo((byte) 0x1B);
    }

    // ── UC-21 AC#13 — inbound switcher frames ───────────────────────────────

    @Test
    void parses_enumerate_targets_frame() {
        ControlMessage msg = svc.parse("{\"type\":\"enumerate-targets\"}");
        assertThat(msg).isInstanceOf(ControlMessage.EnumerateTargets.class);
    }

    @Test
    void parses_select_target_frame_with_target_id() {
        ControlMessage msg = svc.parse("{\"type\":\"select-target\",\"targetId\":\"swarm:claude-swarm-1:0.1\"}");
        assertThat(msg).isInstanceOfSatisfying(ControlMessage.SelectTarget.class, st -> assertThat(st.targetId())
                .isEqualTo("swarm:claude-swarm-1:0.1"));
    }

    // ── UC-21 AC#13 — outbound StreamServerMessage serialization ────────────

    @Test
    void serialize_targets_frame_lists_targets_and_selected_id() {
        StreamServerMessage.TargetInfo main = new StreamServerMessage.TargetInfo(
                "main", "main", "main", null, null, null, null, null, "main", null, null);
        StreamServerMessage.TargetInfo pane = new StreamServerMessage.TargetInfo(
                "swarm:claude-swarm-1:0.0",
                "swarm",
                "agent ping",
                "ping",
                "general-purpose",
                "blue",
                "team",
                "/tmp/tmux-997/claude-swarm-1",
                "claude-swarm",
                "0",
                "0");
        byte[] body = svc.serialize(new StreamServerMessage.Targets(List.of(main, pane), "main"));
        String s = new String(body, StandardCharsets.UTF_8);

        // Own discriminator on the server->client hierarchy.
        assertThat(s).contains("\"type\":\"targets\"");
        assertThat(s).contains("\"selectedId\":\"main\"");
        assertThat(s).contains("\"id\":\"main\"");
        assertThat(s).contains("\"id\":\"swarm:claude-swarm-1:0.0\"");
        assertThat(s).contains("\"agentName\":\"ping\"");
        assertThat(s).contains("\"agentColor\":\"blue\"");
    }

    @Test
    void serialize_target_selected_frame_carries_discriminator_and_id() {
        byte[] body = svc.serialize(new StreamServerMessage.TargetSelected("swarm:claude-swarm-1:0.1"));
        String s = new String(body, StandardCharsets.UTF_8);
        assertThat(s).contains("\"type\":\"target-selected\"");
        assertThat(s).contains("\"targetId\":\"swarm:claude-swarm-1:0.1\"");
    }

    @Test
    void serialize_server_error_frame_uses_error_discriminator() {
        byte[] body = svc.serialize(new StreamServerMessage.ServerError("rebridge_failed", "Switch failed", "boom"));
        String s = new String(body, StandardCharsets.UTF_8);
        assertThat(s).contains("\"type\":\"error\"");
        assertThat(s).contains("\"code\":\"rebridge_failed\"");
        assertThat(s).contains("\"title\":\"Switch failed\"");
        assertThat(s).contains("\"detail\":\"boom\"");
    }

    @Test
    void server_message_and_control_message_discriminators_are_independent() {
        // Both hierarchies own a "type" discriminator. The inbound parser only
        // knows ControlMessage names; the outbound serializer only emits
        // StreamServerMessage names. "error" appears in both but is never
        // cross-parsed.
        ControlMessage inboundError = svc.parse("{\"type\":\"error\",\"code\":\"c\",\"title\":\"t\",\"detail\":\"d\"}");
        assertThat(inboundError).isInstanceOf(ControlMessage.ErrorMessage.class);

        String outbound = new String(
                svc.serialize(new StreamServerMessage.ServerError("c", "t", "d")), StandardCharsets.UTF_8);
        assertThat(outbound).contains("\"type\":\"error\"");
    }

    @Test
    void xterm_sgr_remaps_modifier_bits_to_xterm_layout() {
        // input modifier 1 (shift) → xterm 4
        byte[] bytes = svc.toXtermSgr(new ControlMessage.MouseControl(5, 6, 0, 1, "press"));
        String s = new String(bytes, StandardCharsets.UTF_8);
        // button | xterm-shift(4) = 4 ; with leading ESC
        assertThat(s).isEqualTo("[<4;5;6M");
    }
}
