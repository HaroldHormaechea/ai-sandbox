package com.aisandbox.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.stream.dto.ControlMessage;
import com.aisandbox.server.stream.service.StreamControlMessageService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * AC31 — text frame JSON shapes carry resize / mouse / close / error
 * control messages. The xterm-SGR rendering powers AC36 (mouse forwarding
 * through to tmux).
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
        assertThatThrownBy(() -> svc.parse("{\"type\":\"nope\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_json() {
        assertThatThrownBy(() -> svc.parse("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void xterm_sgr_remaps_modifier_bits_to_xterm_layout() {
        // input modifier 1 (shift) → xterm 4
        byte[] bytes = svc.toXtermSgr(new ControlMessage.MouseControl(5, 6, 0, 1, "press"));
        String s = new String(bytes, StandardCharsets.UTF_8);
        // button | xterm-shift(4) = 4 ; with leading ESC
        assertThat(s).isEqualTo("[<4;5;6M");
    }
}
