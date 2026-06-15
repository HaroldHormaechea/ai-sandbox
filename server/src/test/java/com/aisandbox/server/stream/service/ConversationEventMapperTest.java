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

    // ──────────────── UC-41 AC1/AC2/AC3 — primaryText type-aware label value ──────────

    @Test
    void skill_tool_use_primaryText_is_the_skill_name() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"us1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuS\",\"name\":\"Skill\","
                + "\"input\":{\"skill\":\"android-emulator-setup\",\"args\":\"--boot\"}}]}}";
        ConversationServerMessage.ToolUse t =
                (ConversationServerMessage.ToolUse) mapper.map("main", line).get(0);
        // The client formats "Skill loaded <name>"; the server supplies just the name (AC1).
        assertThat(t.primaryText()).isEqualTo("android-emulator-setup");
    }

    @Test
    void skill_tool_use_primaryText_falls_back_to_name_then_command_field() {
        String byName = "{\"type\":\"assistant\",\"uuid\":\"us2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuS2\",\"name\":\"Skill\",\"input\":{\"name\":\"deep-research\"}}]}}";
        assertThat(((ConversationServerMessage.ToolUse)
                                mapper.map("main", byName).get(0))
                        .primaryText())
                .isEqualTo("deep-research");
        String byCommand = "{\"type\":\"assistant\",\"uuid\":\"us3\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuS3\",\"name\":\"Skill\",\"input\":{\"command\":\"verify\"}}]}}";
        assertThat(((ConversationServerMessage.ToolUse)
                                mapper.map("main", byCommand).get(0))
                        .primaryText())
                .isEqualTo("verify");
    }

    @Test
    void bash_tool_use_primaryText_is_the_command() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"ub\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuB\",\"name\":\"Bash\","
                + "\"input\":{\"command\":\"git status --porcelain\",\"description\":\"check\"}}]}}";
        ConversationServerMessage.ToolUse t =
                (ConversationServerMessage.ToolUse) mapper.map("main", line).get(0);
        // The client formats "Command used: <snippet>"; the server supplies the full command (AC2).
        assertThat(t.primaryText()).isEqualTo("git status --porcelain");
    }

    @Test
    void other_tool_use_primaryText_is_the_bounded_summary() {
        String line = "{\"type\":\"assistant\",\"uuid\":\"uo\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuE\",\"name\":\"Edit\","
                + "\"input\":{\"file_path\":\"/x/y.txt\"}}]}}";
        ConversationServerMessage.ToolUse t =
                (ConversationServerMessage.ToolUse) mapper.map("main", line).get(0);
        // AC3 — any other tool falls back to the bounded summary (client renders "<tool>: <snippet>").
        assertThat(t.primaryText()).contains("file_path=").contains("/x/y.txt");
    }

    // ──────────────── UC-41 AC5/AC6/AC9 — renderDetail (untruncated, byte-bounded) ──────

    @Test
    void renderDetail_returns_full_untruncated_input_and_result_beyond_the_600_char_cap() {
        // Input + result each far exceed the 600-char streaming summary cap (AC6).
        String bigInput = "echo " + "A".repeat(1000);
        String bigResult = "B".repeat(1200);
        String useLine = "{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Bash\",\"input\":{\"command\":\"" + bigInput
                + "\"}}]}}";
        String resultLine = "{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"is_error\":false,\"content\":\"" + bigResult
                + "\"}]}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tu1", List.of(useLine, resultLine));

        assertThat(d.available()).isTrue();
        assertThat(d.toolName()).isEqualTo("Bash");
        assertThat(d.isError()).isFalse();
        // Untruncated: well past the 600-char streaming cap, content preserved verbatim.
        assertThat(d.input().length()).isGreaterThan(600);
        assertThat(d.input()).contains(bigInput);
        assertThat(d.result().length()).isGreaterThan(600);
        assertThat(d.result()).contains(bigResult);
    }

    @Test
    void renderDetail_strips_the_source_tab_envelope_before_parsing() {
        // The helper hands lines as `<source>\t<raw-json>`; renderDetail must strip the prefix.
        String useLine = "main\t{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuX\",\"name\":\"Bash\",\"input\":{\"command\":\"ls -la\"}}]}}";
        String resultLine = "subagent:agent-3\t{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tuX\",\"is_error\":true,\"content\":\"boom\"}]}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuX", List.of(useLine, resultLine));

        assertThat(d.available()).isTrue();
        assertThat(d.input()).contains("ls -la");
        assertThat(d.result()).contains("boom");
        assertThat(d.isError()).isTrue();
    }

    @Test
    void renderDetail_byte_caps_a_pathological_result_with_an_ellipsis() {
        // A multi-KB result must be bounded to CONVERSATION_DETAIL_MAX_BYTES (AC6 OOM guard).
        String huge = "C".repeat(ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES + 20000);
        String resultLine = "{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tuBig\",\"content\":\"" + huge + "\"}]}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuBig", List.of(resultLine));

        assertThat(d.available()).isTrue();
        assertThat(d.result()).endsWith("…");
        // Bounded to the byte cap (plus the few bytes of the ellipsis sentinel).
        assertThat(d.result().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES + 4);
    }

    @Test
    void renderDetail_returns_unavailable_when_the_id_is_not_found() {
        // AC9 — a scrolled-out / unresolvable id degrades to available=false (not an exception).
        String useLine = "{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuPresent\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuMissing", List.of(useLine));

        assertThat(d.available()).isFalse();
        assertThat(d.input()).isEmpty();
        assertThat(d.result()).isEmpty();
    }

    @Test
    void renderDetail_is_robust_to_null_blank_id_and_malformed_lines() {
        assertThat(mapper.renderDetail(null, List.of("x")).available()).isFalse();
        assertThat(mapper.renderDetail("  ", List.of("x")).available()).isFalse();
        assertThat(mapper.renderDetail("tu1", null).available()).isFalse();
        // A malformed line must be skipped, not throw.
        assertThat(mapper.renderDetail("tu1", List.of("not-json", "{ broken")).available())
                .isFalse();
    }

    @Test
    void renderDetail_resolves_the_use_even_when_the_result_has_not_arrived() {
        // AC8 — detail is fetchable from the tool_use alone (awaiting-result state).
        String useLine = "{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuOnly\",\"name\":\"Skill\",\"input\":{\"skill\":\"verify\"}}]}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuOnly", List.of(useLine));

        assertThat(d.available()).isTrue();
        assertThat(d.toolName()).isEqualTo("Skill");
        assertThat(d.input()).contains("verify");
        assertThat(d.result()).isEmpty();
    }

    // ════════════════ UC-42 — harness-injected user lines (AC1–AC9) ═══════════
    //
    // mapUser() is a 5-rule stateless structural classifier: (1) top-level
    // sourceToolUseID → FOLD (emit nothing); (2) isMeta → SystemNote; (3)
    // <command-name>…</command-name>+<command-args> → SystemNote "Command: <name>";
    // (4) <local-command-stdout> → SystemNote "Command output"; (5) else TurnStart.

    @Test
    void uc42_skill_body_with_top_level_sourceToolUseID_emits_nothing() {
        // AC1 — the injected SKILL.md body is FOLDED into the existing Skill bubble,
        // never rendered as its own (right-aligned) user message.
        String line = "{\"type\":\"user\",\"uuid\":\"u1\",\"sourceToolUseID\":\"tuSkill\","
                + "\"message\":{\"content\":\"# SKILL.md body that must NOT become a user bubble\"}}";
        assertThat(mapper.map("main", line)).isEmpty();
    }

    @Test
    void uc42_blank_sourceToolUseID_does_not_fold_and_falls_through_to_a_prompt() {
        // A blank id is not a fold marker — the line is still a genuine prompt (AC5/AC7).
        String line = "{\"type\":\"user\",\"uuid\":\"u1b\",\"sourceToolUseID\":\"  \","
                + "\"message\":{\"content\":\"a real question\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.TurnStart.class, ts -> assertThat(ts.text())
                        .isEqualTo("a real question"));
    }

    @Test
    void uc42_isMeta_user_line_maps_to_a_generic_SystemNote() {
        // AC3/AC4 — an isMeta line has no host bubble → collapsed left-aligned note.
        String line = "{\"type\":\"user\",\"uuid\":\"u2\",\"isMeta\":true,"
                + "\"message\":{\"content\":\"Caveat: the messages below were generated by the harness.\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.SystemNote.class, n -> {
                    assertThat(n.label()).isEqualTo("System note");
                    assertThat(n.detail()).contains("Caveat");
                });
    }

    @Test
    void uc42_slash_command_wrapper_maps_to_a_SystemNote_labelled_with_the_command() {
        // AC3 — <command-name>/clear</command-name> + <command-args> → "Command: /clear".
        String line = "{\"type\":\"user\",\"uuid\":\"u3\",\"message\":{\"content\":"
                + "\"<command-name>/clear</command-name>\\n<command-message>clear</command-message>"
                + "\\n<command-args></command-args>\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.SystemNote.class, n -> {
                    assertThat(n.label()).isEqualTo("Command: /clear");
                    assertThat(n.detail()).contains("<command-name>/clear</command-name>");
                });
    }

    @Test
    void uc42_local_command_stdout_wrapper_maps_to_a_command_output_SystemNote() {
        // AC3 — a <local-command-stdout> line → "Command output" system note.
        String line = "{\"type\":\"user\",\"uuid\":\"u4\",\"message\":{\"content\":"
                + "\"<local-command-stdout>cleared the conversation</local-command-stdout>\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.SystemNote.class, n -> {
                    assertThat(n.label()).isEqualTo("Command output");
                    assertThat(n.detail()).contains("cleared the conversation");
                });
    }

    @Test
    void uc42_real_prompt_merely_containing_a_command_name_tag_stays_a_TurnStart() {
        // AC5/AC7 pitfall-1 — the markers require the wrapper at the START of the content;
        // a genuine prompt that merely MENTIONS <command-name> mid-text is the user's own
        // message and MUST stay right-aligned (TurnStart), never a SystemNote.
        String line = "{\"type\":\"user\",\"uuid\":\"u5\",\"message\":{\"content\":"
                + "\"please explain what the <command-name> tag means in a transcript\"}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.TurnStart.class);
    }

    @Test
    void uc42_command_name_open_tag_without_the_full_wrapper_is_not_misclassified() {
        // AC7 — the matcher requires </command-name> AND <command-args>; a lone opening
        // tag is not the harness's structural wrapper, so the line stays a real prompt.
        String line = "{\"type\":\"user\",\"uuid\":\"u6\",\"message\":{\"content\":"
                + "\"<command-name>not actually a wrapper, no closing tag or args here\"}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.TurnStart.class);
    }

    @Test
    void uc42_plain_user_prompt_still_maps_to_TurnStart_unchanged() {
        // AC5 — the common case (no structural markers) is untouched by UC-42.
        String line = "{\"type\":\"user\",\"uuid\":\"u7\",\"message\":{\"content\":\"build me a feature\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.TurnStart.class, ts -> assertThat(ts.text())
                        .isEqualTo("build me a feature"));
    }

    @Test
    void uc42_tool_result_user_line_is_unchanged_and_never_a_SystemNote() {
        // AC6 — UC-41 regression guard: a tool_result line still maps to ToolResult,
        // unaffected by the new injected-line classifier (which runs only after the
        // tool_result short-circuit).
        String line = "{\"type\":\"user\",\"uuid\":\"u8\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu9\",\"is_error\":false,\"content\":\"ok\"}]}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.ToolResult.class);
    }

    @Test
    void uc42_injected_line_classification_is_identical_live_and_on_backfill() {
        // AC8 — the mapper is a pure function of its inputs (no state), so a folded
        // skill load / system note is classified IDENTICALLY whether streamed live or
        // replayed during backfill. Equal records → equal lists.
        String injected = "{\"type\":\"user\",\"uuid\":\"u9\",\"isMeta\":true,\"message\":{\"content\":\"note\"}}";
        assertThat(mapper.map("main", injected)).isEqualTo(mapper.map("main", injected));
    }

    @Test
    void uc42_sidechain_isMeta_line_notes_under_its_subagent_source() {
        // AC9 — a teammate's injected line folds/notes under its OWN source, not the
        // main pane, and carries the isSidechain flag.
        String line = "{\"type\":\"user\",\"uuid\":\"u10\",\"isSidechain\":true,\"isMeta\":true,"
                + "\"message\":{\"content\":\"teammate housekeeping line\"}}";
        ConversationServerMessage.SystemNote n = (ConversationServerMessage.SystemNote)
                mapper.map("subagent:agent-5", line).get(0);
        assertThat(n.isSidechain()).isTrue();
        assertThat(n.source()).isEqualTo("subagent:agent-5");
        assertThat(n.label()).isEqualTo("System note");
        assertThat(n.detail()).contains("teammate housekeeping line");
    }

    @Test
    void uc42_SystemNote_detail_is_byte_bounded_to_the_48kb_cap() {
        // AC4 OOM guard — the inline detail reuses the 48 KB byte cap (same as ToolDetail).
        String huge = "M".repeat(ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES + 5000);
        String line = "{\"type\":\"user\",\"uuid\":\"u11\",\"isMeta\":true,\"message\":{\"content\":\"" + huge + "\"}}";
        ConversationServerMessage.SystemNote n =
                (ConversationServerMessage.SystemNote) mapper.map("main", line).get(0);
        assertThat(n.detail().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES + 4);
        assertThat(n.detail()).endsWith("…");
    }

    // ──────────── UC-42 — renderDetail folds the sourceToolUseID skill body (AC2) ──────

    @Test
    void uc42_renderDetail_folds_the_sourceToolUseID_skill_body_beyond_the_600_char_cap() {
        // AC2 anti-truncation — the FOLDED body (not the tiny "Launching skill…"
        // tool_result) becomes the Skill bubble's tap detail, full and untruncated.
        String skillUse = "{\"type\":\"assistant\",\"uuid\":\"u1\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"tuSkill\",\"name\":\"Skill\",\"input\":{\"skill\":\"deep-research\"}}]}}";
        String bigBody = "S".repeat(1500);
        // The injected SKILL.md body arrives as a user line carrying the host id at the TOP LEVEL.
        String injected = "{\"type\":\"user\",\"uuid\":\"u2\",\"sourceToolUseID\":\"tuSkill\","
                + "\"message\":{\"content\":\"" + bigBody + "\"}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuSkill", List.of(skillUse, injected));

        assertThat(d.available()).isTrue();
        assertThat(d.toolName()).isEqualTo("Skill");
        assertThat(d.isError()).isFalse();
        assertThat(d.input()).contains("deep-research"); // input still resolved from the tool_use
        assertThat(d.result().length()).isGreaterThan(600); // folded body, well past the streaming cap
        assertThat(d.result()).contains(bigBody);
    }

    @Test
    void uc42_renderDetail_folds_a_sidechain_skill_body_under_the_subagent_source() {
        // AC9 — the helper envelopes each line as `<source>\t<raw>`. A subagent's folded
        // skill body resolves by the Skill toolUseId regardless of source (the source
        // prefix is stripped before parsing), so a teammate skill load expands correctly.
        String skillUse = "subagent:agent-3\t{\"type\":\"assistant\",\"uuid\":\"u1\",\"isSidechain\":true,"
                + "\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"tuSub\",\"name\":\"Skill\","
                + "\"input\":{\"skill\":\"verify\"}}]}}";
        String injected = "subagent:agent-3\t{\"type\":\"user\",\"uuid\":\"u2\",\"isSidechain\":true,"
                + "\"sourceToolUseID\":\"tuSub\",\"message\":{\"content\":\"teammate skill body content\"}}";

        ConversationEventMapper.DetailRender d = mapper.renderDetail("tuSub", List.of(skillUse, injected));

        assertThat(d.available()).isTrue();
        assertThat(d.toolName()).isEqualTo("Skill");
        assertThat(d.result()).contains("teammate skill body content");
    }

    // ──────────────────────── UC-50/UC-55 — pane-signal pending prompt ───────
    // mapPendingPrompt maps the helper's `{kind,questions,plan,key}` JSON to a typed
    // PendingPrompt and DECIDES `answerable` server-side. UC-55 replaced the UC-50
    // `questions.size() <= 1` gate with `answerable = plan || EVERY question carries
    // options`: a plan approval is always answerable; an AskUserQuestion (single OR
    // multi) is answerable iff every tab's option list is non-empty. A header-only
    // batch (options not yet recovered) is answerable=false until the handler steps
    // the pane to recover them and re-maps through this same predicate (AC2/AC5/AC10).

    @Test
    void pending_prompt_single_question_is_answerable_with_full_structure() {
        // AC3 — one question with options → answerable=true, structure preserved.
        String json = "{\"kind\":\"questions\",\"key\":\"pane-abc123\",\"plan\":\"\",\"questions\":["
                + "{\"question\":\"Which database should we use?\",\"header\":\"Database\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"PostgreSQL\",\"description\":\"Use PostgreSQL.\"},"
                + "{\"label\":\"MySQL\",\"description\":\"Use MySQL.\"}]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.promptKey()).isEqualTo("pane-abc123");
        assertThat(pp.kind()).isEqualTo("questions");
        assertThat(pp.answerable()).isTrue();
        assertThat(pp.questions()).singleElement().satisfies(item -> {
            assertThat(item.question()).isEqualTo("Which database should we use?");
            assertThat(item.header()).isEqualTo("Database");
            assertThat(item.multiSelect()).isFalse();
            assertThat(item.options()).hasSize(2);
            assertThat(item.options().get(0).label()).isEqualTo("PostgreSQL");
            assertThat(item.options().get(0).description()).isEqualTo("Use PostgreSQL.");
        });
    }

    @Test
    void pending_prompt_multi_question_batch_header_only_is_NOT_answerable() {
        // AC2/AC10 — a multi-question batch arrives header-only (each tab's options empty,
        // because one pane capture only shows the FOCUSED tab). Under the UC-55 rule this is
        // answerable=false ONLY because not every tab carries options yet — NOT because of a
        // size gate. The handler's eager recovery later flips it true (see
        // pending_prompt_multi_question_with_every_tab's_options_is_answerable). The
        // header-only items are preserved so the recovery can pair each with its header.
        String json = "{\"kind\":\"questions\",\"key\":\"pane-multi\",\"questions\":["
                + "{\"question\":\"\",\"header\":\"Color\",\"multiSelect\":false,\"options\":[]},"
                + "{\"question\":\"\",\"header\":\"Size\",\"multiSelect\":false,\"options\":[]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.answerable()).isFalse();
        assertThat(pp.questions()).hasSize(2);
        assertThat(pp.questions())
                .extracting(ConversationServerMessage.QuestionItem::header)
                .containsExactly("Color", "Size");
    }

    @Test
    void pending_prompt_plan_kind_is_answerable_and_carries_plan_text() {
        // AC6 — a plan-approval prompt is answerable in-app (approve / keep planning).
        String json = "{\"kind\":\"plan\",\"key\":\"pane-plan\",\"plan\":\"1. do a\\n2. do b\",\"questions\":[]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.kind()).isEqualTo("plan");
        assertThat(pp.answerable()).isTrue();
        assertThat(pp.plan()).contains("do a").contains("do b");
        assertThat(pp.questions()).isEmpty();
    }

    @Test
    void pending_prompt_zero_questions_is_NOT_answerable_genuinely_unrecoverable_boundary() {
        // AC5 boundary — UC-55 CONTRACT CHANGE vs UC-50: an empty/degraded AskUserQuestion
        // (no parsed items, hence no options to render) is the *genuinely-unrecoverable*
        // residual, so answerable=false (the explicitly-justified tmux exception). Under the
        // old `size() <= 1` gate this was answerable=true, which would have rendered an empty
        // in-app sheet with a submit and no options. The new `allQuestionsHaveOptions` rule
        // (a question with no options cannot be answered in-app) correctly routes it to the
        // narrow fallback. The ordinary single/multi wizard is NOT this case — it carries
        // options (single) or has them recovered (multi).
        String json = "{\"kind\":\"questions\",\"key\":\"pane-empty\",\"questions\":[]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.answerable()).isFalse();
        assertThat(pp.questions()).isEmpty();
    }

    @Test
    void pending_prompt_multi_question_with_every_tab_options_is_answerable() {
        // AC2/AC10 FLAGSHIP — once every tab carries options (the state the handler's pane
        // recovery produces), the SAME mapPendingPrompt predicate flips the multi-question
        // batch to answerable=true. This is the load-bearing rule: a fully-recovered N>1
        // wizard is in-app answerable, never deferred to the tmux fallback.
        String json = "{\"kind\":\"questions\",\"key\":\"pane-multi-full\",\"questions\":["
                + "{\"question\":\"Pick a color\",\"header\":\"Color\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"Red\",\"description\":\"\"},{\"label\":\"Blue\",\"description\":\"\"}]},"
                + "{\"question\":\"Pick a size\",\"header\":\"Size\",\"multiSelect\":true,"
                + "\"options\":[{\"label\":\"S\",\"description\":\"\"},{\"label\":\"L\",\"description\":\"\"}]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.answerable()).isTrue();
        assertThat(pp.questions()).hasSize(2);
        assertThat(pp.questions())
                .extracting(ConversationServerMessage.QuestionItem::header)
                .containsExactly("Color", "Size");
        assertThat(pp.questions().get(1).multiSelect()).isTrue();
    }

    @Test
    void pending_prompt_multi_question_with_one_unrecovered_tab_is_NOT_answerable() {
        // AC5 — a partial recovery (one tab still has empty options) keeps the WHOLE batch
        // answerable=false: rendering an answerable sheet with a tab that has no options
        // would let the user "answer" a question with nothing to pick. The single failing
        // tab governs the whole batch (the narrow genuinely-unrecoverable exception).
        String json = "{\"kind\":\"questions\",\"key\":\"pane-partial\",\"questions\":["
                + "{\"question\":\"Pick a color\",\"header\":\"Color\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"Red\",\"description\":\"\"}]},"
                + "{\"question\":\"\",\"header\":\"Size\",\"multiSelect\":false,\"options\":[]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.answerable()).isFalse();
        assertThat(pp.questions()).hasSize(2);
    }

    @Test
    void answerable_predicate_plan_is_always_answerable_even_with_no_questions() {
        // The server-single-sourced predicate: a plan approval is answerable regardless of
        // any question list, while an AskUserQuestion requires every question to have options.
        assertThat(mapper.answerable("plan", List.of())).isTrue();
        assertThat(mapper.answerable("plan", null)).isTrue();
        assertThat(mapper.answerable("questions", null)).isFalse();
        assertThat(mapper.answerable("questions", List.of())).isFalse();
        assertThat(mapper.answerable(
                        "questions",
                        List.of(new ConversationServerMessage.QuestionItem(
                                "Q", "H", false, List.of(new ConversationServerMessage.Option("A", ""))))))
                .isTrue();
    }

    @Test
    void parseFocusedTab_parses_one_tab_with_options_multiselect_and_stamps_header() {
        // AC6 — the helper's per-tab `--parse-pane` JSON ({question,multiSelect,options})
        // maps to a QuestionItem; the caller-supplied header is stamped (the server owns the
        // tab order, --parse-pane does not derive the header). multiSelect + "Other" round-trip.
        String tabJson = "{\"question\":\"Which features?\",\"multiSelect\":true,\"options\":["
                + "{\"label\":\"Search\",\"description\":\"Full-text search.\"},"
                + "{\"label\":\"Other\",\"description\":\"Type your own.\"}]}";
        ConversationServerMessage.QuestionItem item = mapper.parseFocusedTab(tabJson, "Features");
        assertThat(item).isNotNull();
        assertThat(item.question()).isEqualTo("Which features?");
        assertThat(item.header()).isEqualTo("Features"); // stamped by the caller, not from the pane
        assertThat(item.multiSelect()).isTrue();
        assertThat(item.options()).hasSize(2);
        assertThat(item.options().get(0).label()).isEqualTo("Search");
        assertThat(item.options().get(1).label()).isEqualTo("Other"); // free-text "Other" preserved
    }

    @Test
    void parseFocusedTab_returns_null_for_unrecovered_blank_or_malformed_payloads() {
        // An unrecovered tab ({} or empty options) → null so the caller keeps that tab
        // header-only and the whole batch correctly stays answerable=false. Never throws.
        assertThat(mapper.parseFocusedTab(null, "H")).isNull();
        assertThat(mapper.parseFocusedTab("", "H")).isNull();
        assertThat(mapper.parseFocusedTab("   ", "H")).isNull();
        assertThat(mapper.parseFocusedTab("not-json", "H")).isNull();
        assertThat(mapper.parseFocusedTab("[1,2,3]", "H")).isNull(); // not an object
        assertThat(mapper.parseFocusedTab("{}", "H")).isNull(); // capture miss → no options
        assertThat(mapper.parseFocusedTab("{\"question\":\"Q\",\"options\":[]}", "H"))
                .isNull(); // empty options → unrecovered
    }

    @Test
    void parseFocusedTab_tolerates_a_null_header_and_missing_question_text() {
        // Defensive: a null header becomes "" and a missing question becomes "" (mirrors
        // mapPendingPrompt's never-throw contract) as long as at least one option exists.
        ConversationServerMessage.QuestionItem item =
                mapper.parseFocusedTab("{\"options\":[{\"label\":\"A\",\"description\":\"\"}]}", null);
        assertThat(item).isNotNull();
        assertThat(item.header()).isEmpty();
        assertThat(item.question()).isEmpty();
        assertThat(item.options()).singleElement().satisfies(o -> assertThat(o.label())
                .isEqualTo("A"));
    }

    @Test
    void pending_prompt_defaults_kind_to_questions_when_absent() {
        String json = "{\"key\":\"pane-nokind\",\"questions\":[{\"header\":\"H\",\"options\":[]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        assertThat(pp).isNotNull();
        assertThat(pp.kind()).isEqualTo("questions");
        assertThat(pp.plan()).isEmpty();
    }

    @Test
    void pending_prompt_malformed_or_keyless_payload_returns_null_never_throws() {
        // AC20 parity — a malformed / empty / keyless payload yields null so the handler
        // skips it without crashing the tail pump.
        assertThat(mapper.mapPendingPrompt(null)).isNull();
        assertThat(mapper.mapPendingPrompt("")).isNull();
        assertThat(mapper.mapPendingPrompt("   ")).isNull();
        assertThat(mapper.mapPendingPrompt("not-json")).isNull();
        assertThat(mapper.mapPendingPrompt("[1,2,3]")).isNull();
        assertThat(mapper.mapPendingPrompt("\"a string\"")).isNull();
        // object but no key → null (the key is the dedupe/clear anchor; required).
        assertThat(mapper.mapPendingPrompt("{\"kind\":\"questions\",\"questions\":[]}"))
                .isNull();
        assertThat(mapper.mapPendingPrompt("{\"key\":\"  \"}")).isNull();
    }

    @Test
    void pending_prompt_to_question_keys_uuid_and_toolUseId_to_the_prompt_key() {
        // The synthesized Question the handler caches must key BOTH uuid and toolUseId to
        // promptKey, so an in-app answer that echoes promptKey resolves to this question's
        // option spec (single-writer cache keyed by toolUseId). source defaults to "main".
        String json = "{\"kind\":\"questions\",\"key\":\"pane-xyz\",\"questions\":["
                + "{\"question\":\"Q\",\"header\":\"H\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"A\",\"description\":\"\"}]}]}";
        ConversationServerMessage.PendingPrompt pp = mapper.mapPendingPrompt(json);
        ConversationServerMessage.Question q = mapper.pendingPromptToQuestion(pp);
        assertThat(q.uuid()).isEqualTo("pane-xyz");
        assertThat(q.toolUseId()).isEqualTo("pane-xyz");
        assertThat(q.isSidechain()).isFalse();
        assertThat(q.source()).isEqualTo("main");
        assertThat(q.questions()).hasSize(1);
        assertThat(q.questions().get(0).options().get(0).label()).isEqualTo("A");
    }

    // ════════════════ UC-58 — teammate-message envelope reclassification (AC1–AC7) ═══
    //
    // In a team-lead session the harness delivers an inbound teammate/subagent message to
    // the lead as a `user`-role line whose string content is a
    // `<teammate-message teammate_id="…" color="…">…</teammate-message>` envelope (no
    // isMeta, no sourceToolUseID). mapUser's rule 5 reclassifies a WELL-FORMED envelope
    // (opening tag at the START of content, a `teammate_id`, and a closing tag) to a
    // dedicated TeammateMessage frame so it renders as a distinct, sender-attributed,
    // NON-user bubble — instead of falling through to the rule-6 TurnStart (right-aligned
    // user bubble). A malformed/half envelope, or a genuine prompt that merely mentions the
    // literal text, stays a TurnStart.
    //
    // The fixture lines mirror REAL captured wire shapes: a no-color double-quoted
    // `teammate_id="team-lead"` line, a quoted-`>` summary attribute, and nested-JSON inner
    // bodies (idle_notification / task_assignment) — not synthetic-only forms.

    @Test
    void uc58_teammate_envelope_maps_to_TeammateMessage_with_sender_and_cleaned_text() {
        // AC1/AC2 — a well-formed envelope (faithful wire form: double-quoted attrs) →
        // TeammateMessage carrying the teammate_id + color and the stripped inner text.
        String line = "{\"type\":\"user\",\"uuid\":\"utm\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id=\\\"analyst\\\" color=\\\"blue\\\">"
                + "Proposal looks good.</teammate-message>\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.TeammateMessage.class, tm -> {
                    assertThat(tm.teammateId()).isEqualTo("analyst");
                    assertThat(tm.color()).isEqualTo("blue");
                    assertThat(tm.text()).isEqualTo("Proposal looks good.");
                    assertThat(tm.uuid()).isEqualTo("utm");
                });
    }

    @Test
    void uc58_real_wire_no_color_team_lead_envelope_maps_to_TeammateMessage() {
        // AC1/AC2 — a REAL captured shape: a `teammate_id="team-lead"` line with NO color
        // attribute and double-quoted attrs, exactly as the harness delivers it to the lead.
        String line = "{\"type\":\"user\",\"uuid\":\"utl0\",\"isSidechain\":false,\"message\":{\"content\":"
                + "\"<teammate-message teammate_id=\\\"team-lead\\\">"
                + "you're clear, proceed</teammate-message>\"}}";
        assertThat(mapper.map("main", line))
                .singleElement()
                .isInstanceOfSatisfying(ConversationServerMessage.TeammateMessage.class, tm -> {
                    assertThat(tm.teammateId()).isEqualTo("team-lead");
                    assertThat(tm.color()).isEmpty();
                    assertThat(tm.text()).isEqualTo("you're clear, proceed");
                });
    }

    @Test
    void uc58_teammate_envelope_with_nested_json_inner_is_collapsed_no_markup_leak() {
        // AC6 — an idle-notification (or any nested-JSON) inner body is collapsed to a short
        // "[type] summary" label; raw JSON braces / field names never leak into the bubble.
        String line = "{\"type\":\"user\",\"uuid\":\"utj\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='notifier'>"
                + "{\\\"type\\\":\\\"idle_notification\\\",\\\"summary\\\":\\\"agent went idle\\\"}"
                + "</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("main", line).get(0);
        assertThat(tm.text()).isEqualTo("[idle_notification] agent went idle");
        assertThat(tm.text()).doesNotContain("{").doesNotContain("\"type\"").doesNotContain("summary");
    }

    @Test
    void uc58_teammate_envelope_with_task_assignment_json_is_collapsed_no_markup_leak() {
        // AC6 — a second REAL nested-JSON shape (a task_assignment peer-DM envelope) is
        // likewise collapsed to "[type] summary"; no JSON markup reaches the rendered bubble.
        String line = "{\"type\":\"user\",\"uuid\":\"uta\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id=\\\"developer\\\" color=\\\"green\\\">"
                + "{\\\"type\\\":\\\"task_assignment\\\",\\\"summary\\\":\\\"implement the parser\\\","
                + "\\\"taskId\\\":\\\"42\\\"}</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("main", line).get(0);
        assertThat(tm.teammateId()).isEqualTo("developer");
        assertThat(tm.text()).isEqualTo("[task_assignment] implement the parser");
        assertThat(tm.text()).doesNotContain("{").doesNotContain("taskId");
    }

    @Test
    void uc58_teammate_envelope_preserves_multiline_inner_content() {
        // AC6 — multi-line plain inner content is preserved as-is (renderer handles wrapping).
        String line = "{\"type\":\"user\",\"uuid\":\"utl\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='dev'>line one\\nline two</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("main", line).get(0);
        assertThat(tm.text()).contains("line one").contains("line two");
    }

    @Test
    void uc58_teammate_envelope_without_color_yields_empty_color_but_keeps_sender() {
        // AC2 — color is optional; attribution still works off teammate_id alone.
        String line = "{\"type\":\"user\",\"uuid\":\"utc\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='challenger'>no colour here</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("main", line).get(0);
        assertThat(tm.teammateId()).isEqualTo("challenger");
        assertThat(tm.color()).isEmpty();
        assertThat(tm.text()).isEqualTo("no colour here");
    }

    @Test
    void uc58_attribute_value_containing_a_gt_does_not_truncate_the_inner_body() {
        // Challenger Minor #1 / AC6 — a quoted attribute value that itself contains '>'
        // must NOT be mistaken for the opening tag's end, or the markup would leak. The
        // inner body is sliced from the REAL tag end; the attribute text never appears.
        String line = "{\"type\":\"user\",\"uuid\":\"utg\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='qa' summary='done > next'>"
                + "the actual body</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("main", line).get(0);
        assertThat(tm.text()).isEqualTo("the actual body");
        assertThat(tm.text()).doesNotContain("summary").doesNotContain("next");
    }

    @Test
    void uc58_genuine_prompt_merely_mentioning_the_tag_mid_text_stays_a_TurnStart() {
        // AC3/AC4 pitfall — the marker is STRUCTURAL (content must START with the opening
        // tag). A real prompt that merely mentions the literal text is the user's own
        // message and must stay a (right-aligned) TurnStart, never a TeammateMessage.
        String line = "{\"type\":\"user\",\"uuid\":\"utp\",\"message\":{\"content\":"
                + "\"please render a <teammate-message …> envelope in the docs\"}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.TurnStart.class);
    }

    @Test
    void uc58_envelope_without_a_teammate_id_degrades_to_TurnStart() {
        // Well-formedness — a teammate envelope must NAME its sender. Without a teammate_id
        // it is not reclassified; it degrades to a TurnStart rather than being dropped.
        String line = "{\"type\":\"user\",\"uuid\":\"utn\",\"message\":{\"content\":"
                + "\"<teammate-message color='blue'>orphan envelope</teammate-message>\"}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.TurnStart.class);
    }

    @Test
    void uc58_malformed_or_half_envelope_degrades_to_TurnStart_and_never_throws() {
        // AC20 parity / AC3 (degrade-not-drop) — a half envelope (no closing tag) and an
        // unterminated opening tag (no '>') both degrade to TurnStart; neither drops the line
        // nor throws.
        String noClose = "{\"type\":\"user\",\"uuid\":\"uth1\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='x'>body with no closing tag\"}}";
        assertThat(mapper.map("main", noClose)).singleElement().isInstanceOf(ConversationServerMessage.TurnStart.class);
        String noTagEnd = "{\"type\":\"user\",\"uuid\":\"uth2\",\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='x' dangling attribute with no gt\"}}";
        assertThat(mapper.map("main", noTagEnd))
                .singleElement()
                .isInstanceOf(ConversationServerMessage.TurnStart.class);
        // A lookalike tag name ("<teammate-messageX>") is not the envelope → TurnStart (AC3).
        String lookalike = "{\"type\":\"user\",\"uuid\":\"uth3\",\"message\":{\"content\":"
                + "\"<teammate-messageX>not the envelope</teammate-messageX>\"}}";
        assertThat(mapper.map("main", lookalike))
                .singleElement()
                .isInstanceOf(ConversationServerMessage.TurnStart.class);
    }

    @Test
    void uc58_teammate_frame_stamps_source_and_sidechain() {
        // AC17 parity — the reclassified frame carries the per-line source + isSidechain.
        String line = "{\"type\":\"user\",\"uuid\":\"uts\",\"isSidechain\":true,\"message\":{\"content\":"
                + "\"<teammate-message teammate_id='worker'>delegated result</teammate-message>\"}}";
        ConversationServerMessage.TeammateMessage tm = (ConversationServerMessage.TeammateMessage)
                mapper.map("subagent:agent-9", line).get(0);
        assertThat(tm.source()).isEqualTo("subagent:agent-9");
        assertThat(tm.isSidechain()).isTrue();
    }

    @Test
    void uc58_tool_result_user_line_still_maps_to_ToolResult_unaffected() {
        // AC7 regression — the teammate rule runs AFTER the tool_result short-circuit, so a
        // tool_result-carrying user line is unaffected by UC-58.
        String line = "{\"type\":\"user\",\"uuid\":\"utr\",\"message\":{\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"tu9\",\"is_error\":false,\"content\":\"ok\"}]}}";
        assertThat(mapper.map("main", line)).singleElement().isInstanceOf(ConversationServerMessage.ToolResult.class);
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
