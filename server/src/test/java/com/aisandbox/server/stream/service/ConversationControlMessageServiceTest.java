package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.stream.dto.ConversationClientMessage;
import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.aisandbox.server.stream.dto.StreamServerMessage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-37 AC19/AC21 — the conversation channel's JSON wire vocabulary lives in a
 * SEPARATE {@code @JsonTypeInfo} namespace from the binary stream's
 * {@link com.aisandbox.server.stream.dto.ControlMessage} /
 * {@link StreamServerMessage}. These tests pin the inbound
 * {@link ConversationClientMessage} parse and the outbound
 * {@link ConversationServerMessage} serialize paths, and prove the discriminators
 * never cross even though several names ({@code close}, {@code select-target},
 * {@code error}) exist in more than one hierarchy.
 */
class ConversationControlMessageServiceTest {

    private final StreamControlMessageService svc = new StreamControlMessageService();

    private String ser(ConversationServerMessage m) {
        return new String(svc.serialize(m), StandardCharsets.UTF_8);
    }

    // ──────────────────────── inbound (client → server) ──────────────────────

    @Test
    void parses_composer_input_frame() {
        ConversationClientMessage m = svc.parseConversation("{\"type\":\"composer-input\",\"text\":\"hi\\nthere\"}");
        assertThat(m).isInstanceOfSatisfying(ConversationClientMessage.ComposerInput.class, c -> assertThat(c.text())
                .isEqualTo("hi\nthere"));
    }

    @Test
    void parses_answer_frame_with_selections_and_free_text() {
        ConversationClientMessage m =
                svc.parseConversation("{\"type\":\"answer\",\"questionUuid\":\"tuQ\",\"questionIndex\":0,"
                        + "\"selections\":[0,2],\"freeText\":\"custom\"}");
        assertThat(m).isInstanceOfSatisfying(ConversationClientMessage.Answer.class, a -> {
            assertThat(a.questionUuid()).isEqualTo("tuQ");
            assertThat(a.questionIndex()).isZero();
            assertThat(a.selections()).containsExactly(0, 2);
            assertThat(a.freeText()).isEqualTo("custom");
        });
    }

    @Test
    void parses_select_target_interrupt_enumerate_and_close_frames() {
        assertThat(svc.parseConversation("{\"type\":\"select-target\",\"targetId\":\"swarm:main:0.1\"}"))
                .isInstanceOfSatisfying(ConversationClientMessage.SelectTarget.class, st -> assertThat(st.targetId())
                        .isEqualTo("swarm:main:0.1"));
        assertThat(svc.parseConversation("{\"type\":\"interrupt\"}"))
                .isInstanceOf(ConversationClientMessage.Interrupt.class);
        assertThat(svc.parseConversation("{\"type\":\"enumerate-targets\"}"))
                .isInstanceOf(ConversationClientMessage.EnumerateTargets.class);
        assertThat(svc.parseConversation("{\"type\":\"close\",\"reason\":\"bye\"}"))
                .isInstanceOf(ConversationClientMessage.Close.class);
    }

    @Test
    void parses_fetch_detail_frame_with_tool_use_id_and_uuid() {
        // UC-41 AC5 — the tap-to-expand request frame.
        ConversationClientMessage m =
                svc.parseConversation("{\"type\":\"fetch-detail\",\"toolUseId\":\"tu9\",\"uuid\":\"u-line\"}");
        assertThat(m).isInstanceOfSatisfying(ConversationClientMessage.FetchDetail.class, fd -> {
            assertThat(fd.toolUseId()).isEqualTo("tu9");
            assertThat(fd.uuid()).isEqualTo("u-line");
        });
    }

    @Test
    void rejects_unknown_or_invalid_conversation_frame() {
        assertThatThrownBy(() -> svc.parseConversation("{\"type\":\"resize\",\"cols\":80}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.parseConversation("not-json")).isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────── outbound (server → client) ─────────────────────

    @Test
    void serializes_transcript_derived_frames_with_their_discriminators() {
        assertThat(ser(new ConversationServerMessage.TurnStart("u1", false, "main", "go")))
                .contains("\"type\":\"turn-start\"")
                .contains("\"text\":\"go\"")
                .contains("\"source\":\"main\"");
        assertThat(ser(new ConversationServerMessage.ThinkingState("u2", false, "main", "hmm")))
                .contains("\"type\":\"thinking\"");
        assertThat(ser(new ConversationServerMessage.AssistantText("u3", false, "main", "hello")))
                .contains("\"type\":\"assistant-text\"");
        assertThat(ser(new ConversationServerMessage.ToolUse("u4", false, "main", "Bash", "tu1", "ls", "ls")))
                .contains("\"type\":\"tool-use\"")
                .contains("\"toolName\":\"Bash\"")
                .contains("\"primaryText\":\"ls\"");
        assertThat(ser(new ConversationServerMessage.ToolResult("u5", false, "main", "tu1", true, "boom")))
                .contains("\"type\":\"tool-result\"")
                .contains("\"isError\":true");
        assertThat(ser(new ConversationServerMessage.TurnEnd("u6", false, "main", 1200L, 4)))
                .contains("\"type\":\"turn-end\"")
                .contains("\"durationMs\":1200");
    }

    @Test
    void serializes_tool_detail_frame_with_full_input_and_result() {
        // UC-41 AC5/AC6 — the on-demand untruncated detail frame.
        String s = ser(new ConversationServerMessage.ToolDetail(
                "tu9", "Bash", "ls -la /workspace", "total 0\ndrwxr-xr-x", false, true));
        assertThat(s)
                .contains("\"type\":\"tool-detail\"")
                .contains("\"toolUseId\":\"tu9\"")
                .contains("\"toolName\":\"Bash\"")
                .contains("\"input\":\"ls -la /workspace\"")
                .contains("\"available\":true");
    }

    @Test
    void serializes_unavailable_tool_detail_frame() {
        // UC-41 AC9 — a miss carries empty input/result and available=false.
        String s = ser(new ConversationServerMessage.ToolDetail("gone", null, "", "", false, false));
        assertThat(s).contains("\"type\":\"tool-detail\"").contains("\"available\":false");
    }

    @Test
    void serializes_question_frame_with_full_structure() {
        ConversationServerMessage.Question q = new ConversationServerMessage.Question(
                "uq",
                false,
                "main",
                "tuQ",
                List.of(new ConversationServerMessage.QuestionItem(
                        "Pick one", "Choice", true, List.of(new ConversationServerMessage.Option("A", "first")))));
        String s = ser(q);
        assertThat(s)
                .contains("\"type\":\"question\"")
                .contains("\"toolUseId\":\"tuQ\"")
                .contains("\"multiSelect\":true")
                .contains("\"label\":\"A\"")
                .contains("\"description\":\"first\"");
    }

    @Test
    void serializes_plan_approval_targets_and_backfill_markers() {
        assertThat(ser(new ConversationServerMessage.PlanApproval("up", false, "main", "tuP", "the plan")))
                .contains("\"type\":\"plan-approval\"")
                .contains("\"plan\":\"the plan\"");
        StreamServerMessage.TargetInfo main = new StreamServerMessage.TargetInfo(
                "main", "main", "main", null, null, null, null, null, "main", null, null);
        assertThat(ser(new ConversationServerMessage.Targets(List.of(main), "main")))
                .contains("\"type\":\"targets\"")
                .contains("\"selectedId\":\"main\"");
        assertThat(ser(new ConversationServerMessage.BackfillStart("main"))).contains("\"type\":\"backfill-start\"");
        assertThat(ser(new ConversationServerMessage.BackfillEnd("main"))).contains("\"type\":\"backfill-end\"");
        assertThat(ser(new ConversationServerMessage.TargetSelected("swarm:main:0.1")))
                .contains("\"type\":\"target-selected\"");
    }

    @Test
    void question_frame_carries_pending_badge_flags_for_targets() {
        StreamServerMessage.TargetInfo badged = new StreamServerMessage.TargetInfo(
                        "swarm:main:0.1", "swarm", "ping", "ping", "general", "blue", "team", null, "main", "0", "1")
                .withPending(true, true);
        String s = ser(new ConversationServerMessage.Targets(List.of(badged), "main"));
        assertThat(s).contains("\"pendingActivity\":true").contains("\"pendingQuestion\":true");
    }

    // ──────────────────────── namespace isolation (AC19) ─────────────────────

    @Test
    void conversation_and_binary_close_frames_do_not_cross_parse() {
        // "close" exists in BOTH ControlMessage and ConversationClientMessage.
        // The conversation parser yields the conversation Close type, never the
        // binary one.
        ConversationClientMessage conv = svc.parseConversation("{\"type\":\"close\",\"reason\":\"x\"}");
        assertThat(conv).isInstanceOf(ConversationClientMessage.Close.class);
    }
}
