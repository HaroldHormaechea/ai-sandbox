package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.stream.dto.ConversationServerMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-37 — pure unit coverage for {@link ConversationEventMapper}: one recorded
 * Claude Code transcript JSONL line → typed {@link ConversationServerMessage}
 * events. This is the AC3/AC4/AC5/AC10/AC13/AC14/AC15/AC17/AC20 core, fully
 * exercisable in the JVM because the mapper is a pure function of its inputs
 * (no I/O, no process spawn, no state).
 *
 * <p>Fixture lines mirror the shape verified against {@code claude 2.1.159}
 * (RND §10–§11): {@code type} + {@code uuid} + {@code isSidechain} + a
 * {@code message.content} array of blocks, and {@code system:turn_duration}
 * as the explicit turn-end marker.
 *
 * <p>AC→test map (this class):
 * <ul>
 *   <li>AC3 — {@link #assistant_text_block_maps_to_AssistantText()}</li>
 *   <li>AC4 — {@link #assistant_tool_use_maps_to_ToolUse_with_bounded_summary()},
 *       {@link #user_tool_result_block_maps_to_ToolResult()}</li>
 *   <li>AC5 — {@link #assistant_thinking_block_maps_to_ThinkingState()}</li>
 *   <li>AC10 — {@link #ask_user_question_tool_use_maps_to_Question_with_full_structure()}</li>
 *   <li>AC13 — {@link #exit_plan_mode_tool_use_maps_to_PlanApproval()}</li>
 *   <li>AC14/AC6 — {@link #user_prompt_line_maps_to_TurnStart()}</li>
 *   <li>AC15 — {@link #system_turn_duration_maps_to_TurnEnd()}</li>
 *   <li>AC17 — {@link #source_and_sidechain_are_stamped_on_every_frame()}</li>
 *   <li>AC20 — {@link #malformed_line_returns_empty_never_throws()} and friends</li>
 * </ul>
 */
class ConversationEventMapperTest {

    private final ConversationEventMapper mapper = new ConversationEventMapper();

    // ──────────────────────── AC20 — robustness (never throw) ────────────────

    @Test
    void null_and_blank_lines_return_empty() {
        assertThat(mapper.map("main", null)).isEmpty();
        assertThat(mapper.map("main", "")).isEmpty();
        assertThat(mapper.map("main", "   ")).isEmpty();
    }

    @Test
    void malformed_line_returns_empty_never_throws() {
        assertThat(mapper.map("main", "not-json")).isEmpty();
        assertThat(mapper.map("main", "{ broken")).isEmpty();
        // A valid JSON value that is not an object also yields nothing.
        assertThat(mapper.map("main", "\"a string\"")).isEmpty();
        assertThat(mapper.map("main", "[1,2,3]")).isEmpty();
    }

    @Test
    void object_without_type_or_with_unknown_type_returns_empty() {
        assertThat(mapper.map("main", "{\"uuid\":\"u1\"}")).isEmpty();
        assertThat(mapper.map("main", "{\"type\":\"banana\",\"uuid\":\"u1\"}")).isEmpty();
    }

    // ──────────────────────── AC15 — turn lifecycle ──────────────────────────

    @Test
    void system_turn_duration_maps_to_TurnEnd() {
        String line = "{\"type\":\"system\",\"subtype\":\"turn_duration\",\"uuid\":\"u9\","
                + "\"durationMs\":4200,\"messageCount\":7}";
        List<ConversationServerMessage> out = mapper.map("main", line);
        assertThat(out).singleElement().isInstanceOfSatisfying(ConversationServerMessage.TurnEnd.class, te -> {
            assertThat(te.uuid()).isEqualTo("u9");
            assertThat(te.durationMs()).isEqualTo(4200L);
            assertThat(te.messageCount()).isEqualTo(7);
        });
    }

    @Test
    void system_line_with_other_subtype_is_ignored() {
        assertThat(mapper.map("main", "{\"type\":\"system\",\"subtype\":\"something_else\"}"))
                .isEmpty();
    }

    // ──────────────────────── AC5 — thinking ─────────────────────────────────

    @Test
    void assistant_thinking_block_maps_to_ThinkingState() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"u2\",\"message\":{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"let me reason\",\"signature\":\"sig\"}]}}";
        List<ConversationServerMessage> out = mapper.map("main", line);
        assertThat(out).singleElement().isInstanceOfSatisfying(ConversationServerMessage.ThinkingState.class, t -> {
            assertThat(t.text()).isEqualTo("let me reason");
            assertThat(t.uuid()).isEqualTo("u2");
        });
    }

    // ──────────────────────── AC3 — assistant text ───────────────────────────

    @Test
    void assistant_text_block_maps_to_AssistantText() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"u3\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"hello there\"}]}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.AssistantText.class, a -> assertThat(a.text())
                        .isEqualTo("hello there"));
    }

    @Test
    void blank_assistant_text_block_is_skipped() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"u3\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"   \"}]}}";
        assertThat(mapper.map("main", line)).isEmpty();
    }

    @Test
    void multiple_assistant_blocks_in_one_line_map_to_several_events_sharing_uuid() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"uMix\",\"message\":{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hmm\"},"
                + "{\"type\":\"text\",\"text\":\"answer\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";
        List<ConversationServerMessage> out = mapper.map("main", line);
        assertThat(out).hasSize(3);
        assertThat(out.get(0)).isInstanceOf(ConversationServerMessage.ThinkingState.class);
        assertThat(out.get(1)).isInstanceOf(ConversationServerMessage.AssistantText.class);
        assertThat(out.get(2)).isInstanceOf(ConversationServerMessage.ToolUse.class);
        assertThat(out).allSatisfy(m -> assertThat(extractUuid(m)).isEqualTo("uMix"));
    }

    // ──────────────────────── AC4 — tool use / tool result ───────────────────

    @Test
    void assistant_tool_use_maps_to_ToolUse_with_bounded_summary() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"u4\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tu9\",\"name\":\"Edit\","
                + "\"input\":{\"file_path\":\"/x/y.txt\",\"old\":\"a\"}}]}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.ToolUse.class, t -> {
                    assertThat(t.toolName()).isEqualTo("Edit");
                    assertThat(t.toolUseId()).isEqualTo("tu9");
                    assertThat(t.inputSummary()).contains("file_path=").contains("/x/y.txt");
                });
    }

    @Test
    void tool_use_input_summary_is_truncated_with_ellipsis() {
        String big = "x".repeat(2000);
        String line = "{\"type\":\"assistant\",\"uuid\":\"u4b\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuBig\",\"name\":\"Bash\","
                + "\"input\":{\"command\":\"" + big + "\"}}]}}";
        ConversationServerMessage.ToolUse t =
                (ConversationServerMessage.ToolUse) mapper.map("main", line).get(0);
        // 600-char cap (MAX_SUMMARY_LEN) plus the ellipsis sentinel.
        assertThat(t.inputSummary()).endsWith("…");
        assertThat(t.inputSummary().length()).isLessThanOrEqualTo(601);
    }

    @Test
    void user_tool_result_block_maps_to_ToolResult() {
        String line = "{\"type\":\"user\",\"uuid\":\"u5\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu9\",\"is_error\":true,"
                + "\"content\":\"command failed\"}]}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.ToolResult.class, r -> {
                    assertThat(r.toolUseId()).isEqualTo("tu9");
                    assertThat(r.isError()).isTrue();
                    assertThat(r.summary()).isEqualTo("command failed");
                });
    }

    @Test
    void tool_result_with_array_content_is_flattened() {
        String line = "{\"type\":\"user\",\"uuid\":\"u5b\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tuA\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"line one\"},{\"type\":\"text\",\"text\":\"line two\"}]}]}}";
        ConversationServerMessage.ToolResult r =
                (ConversationServerMessage.ToolResult) mapper.map("main", line).get(0);
        assertThat(r.isError()).isFalse();
        assertThat(r.summary()).contains("line one").contains("line two");
    }

    // ──────────────────────── AC10 — AskUserQuestion ─────────────────────────

    @Test
    void ask_user_question_tool_use_maps_to_Question_with_full_structure() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"uq\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuQ\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":["
                + "{\"question\":\"Pick a color\",\"header\":\"Color\",\"multiSelect\":true,\"options\":["
                + "{\"label\":\"Red\",\"description\":\"warm\"},"
                + "{\"label\":\"Blue\",\"description\":\"cool\"}]}]}}]}}";
        ConversationServerMessage.Question q =
                (ConversationServerMessage.Question) mapper.map("main", line).get(0);
        assertThat(q.toolUseId()).isEqualTo("tuQ");
        assertThat(q.questions()).singleElement().satisfies(item -> {
            assertThat(item.question()).isEqualTo("Pick a color");
            assertThat(item.header()).isEqualTo("Color");
            assertThat(item.multiSelect()).isTrue();
            assertThat(item.options()).hasSize(2);
            assertThat(item.options().get(0).label()).isEqualTo("Red");
            assertThat(item.options().get(0).description()).isEqualTo("warm");
            assertThat(item.options().get(1).label()).isEqualTo("Blue");
        });
    }

    @Test
    void ask_user_question_with_missing_options_yields_empty_option_list_not_crash() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"uq2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuQ2\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":["
                + "{\"question\":\"Proceed?\",\"header\":\"Go\"}]}}]}}";
        ConversationServerMessage.Question q =
                (ConversationServerMessage.Question) mapper.map("main", line).get(0);
        assertThat(q.questions()).singleElement().satisfies(item -> {
            assertThat(item.multiSelect()).isFalse();
            assertThat(item.options()).isEmpty();
        });
    }

    // ──────────────────────── AC13 — ExitPlanMode ────────────────────────────

    @Test
    void exit_plan_mode_tool_use_maps_to_PlanApproval() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"up\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuP\",\"name\":\"ExitPlanMode\","
                + "\"input\":{\"plan\":\"1. do a\\n2. do b\"}}]}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.PlanApproval.class, p -> {
                    assertThat(p.toolUseId()).isEqualTo("tuP");
                    assertThat(p.plan()).contains("do a").contains("do b");
                });
    }

    // ──────────────────────── AC14/AC6 — user prompt → TurnStart ─────────────

    @Test
    void user_prompt_line_maps_to_TurnStart() {
        String line = "{\"type\":\"user\",\"uuid\":\"ut\",\"message\":{\"content\":\"hi claude\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.TurnStart.class, ts -> assertThat(ts.text())
                        .isEqualTo("hi claude"));
    }

    @Test
    void user_prompt_as_text_blocks_is_joined() {
        String line = "{\"type\":\"user\",\"uuid\":\"ut2\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]}}";
        ConversationServerMessage.TurnStart ts =
                (ConversationServerMessage.TurnStart) mapper.map("main", line).get(0);
        assertThat(ts.text()).contains("first").contains("second");
    }

    // ──────────────────────── AC17 — source + isSidechain ────────────────────

    @Test
    void source_and_sidechain_are_stamped_on_every_frame() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"us\",\"isSidechain\":true,\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"from a teammate\"}]}}";
        ConversationServerMessage.AssistantText a = (ConversationServerMessage.AssistantText)
                mapper.map("subagent:agent-7", line).get(0);
        assertThat(a.source()).isEqualTo("subagent:agent-7");
        assertThat(a.isSidechain()).isTrue();
    }

    @Test
    void sidechain_defaults_to_false_when_absent() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"us2\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"main agent\"}]}}";
        ConversationServerMessage.AssistantText a = (ConversationServerMessage.AssistantText)
                mapper.map("main", line).get(0);
        assertThat(a.isSidechain()).isFalse();
        assertThat(a.source()).isEqualTo("main");
    }

    // ──────────────────────── helper ─────────────────────────────────────────

    private static String extractUuid(ConversationServerMessage m) {
        return switch (m) {
            case ConversationServerMessage.ThinkingState t -> t.uuid();
            case ConversationServerMessage.AssistantText t -> t.uuid();
            case ConversationServerMessage.ToolUse t -> t.uuid();
            default -> null;
        };
    }
}
