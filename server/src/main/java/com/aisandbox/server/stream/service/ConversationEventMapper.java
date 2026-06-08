package com.aisandbox.server.stream.service;

import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * UC-37 — pure mapper from a single Claude Code transcript JSONL line to typed
 * {@link ConversationServerMessage} events (the AC3/AC4/AC5/AC10/AC13/AC14/AC15
 * core). No I/O, no process spawning, no state — every method is a pure function
 * of its arguments, so it is fully unit-testable against recorded transcript
 * lines.
 *
 * <p>Transcript shape (verified against {@code claude 2.1.159}, RND §10–§11):
 * one JSON object per content block, written as the block completes. Each line
 * carries {@code type} ({@code user} | {@code assistant} | {@code system}),
 * {@code uuid}, {@code isSidechain}, {@code timestamp}, and (for user/assistant)
 * a {@code message} object whose {@code content} is a string or an array of
 * blocks ({@code thinking} / {@code text} / {@code tool_use} / {@code
 * tool_result}). {@code system} lines carry {@code subtype} ({@code
 * turn_duration} is the explicit turn-end marker).
 *
 * <p>{@code source} ({@code main} or {@code subagent:<agentId>}) is supplied by
 * the in-container tail helper (the transcript line itself does not name its
 * file); it is stamped onto every emitted frame alongside the per-line {@code
 * isSidechain} flag (AC17).
 *
 * <p>Robustness (AC20): a line is mapped to zero, one, or several events. An
 * empty list is returned for an unmappable / malformed / unknown line — the
 * mapper never throws on bad input, so one corrupt line can never crash the
 * channel.
 */
@Service
public class ConversationEventMapper {

    /** Claude Code's interactive question tool — rendered as a structured sheet (AC10). */
    public static final String TOOL_ASK_USER_QUESTION = "AskUserQuestion";

    /** Claude Code's plan-mode approval tool (AC13 — see RISK 1 in the proposal). */
    public static final String TOOL_EXIT_PLAN_MODE = "ExitPlanMode";

    /** Cap on a rendered tool-input summary so internal tool noise is summarized, not dumped (AC4). */
    private static final int MAX_SUMMARY_LEN = 600;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Map one transcript line (already split from the tail helper's {@code
     * source\traw} envelope; {@code source} passed separately) to typed events.
     * Returns an empty list when the line is malformed or carries nothing the
     * conversation view renders.
     */
    public List<ConversationServerMessage> map(String source, String rawJsonLine) {
        if (rawJsonLine == null || rawJsonLine.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(rawJsonLine);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return List.of(); // malformed line — skip without crashing (AC20)
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        String type = text(root, "type");
        if (type == null) {
            return List.of();
        }
        String uuid = text(root, "uuid");
        boolean sidechain = root.path("isSidechain").asBoolean(false);

        return switch (type) {
            case "system" -> mapSystem(root, uuid, sidechain, source);
            case "assistant" -> mapAssistant(root, uuid, sidechain, source);
            case "user" -> mapUser(root, uuid, sidechain, source);
            default -> List.of();
        };
    }

    private List<ConversationServerMessage> mapSystem(JsonNode root, String uuid, boolean sidechain, String source) {
        if ("turn_duration".equals(text(root, "subtype"))) {
            long durationMs = root.path("durationMs").asLong(0L);
            int messageCount = root.path("messageCount").asInt(0);
            return List.of(new ConversationServerMessage.TurnEnd(uuid, sidechain, source, durationMs, messageCount));
        }
        return List.of();
    }

    private List<ConversationServerMessage> mapAssistant(JsonNode root, String uuid, boolean sidechain, String source) {
        JsonNode content = root.path("message").path("content");
        List<ConversationServerMessage> out = new ArrayList<>();
        for (JsonNode block : blocks(content)) {
            String btype = text(block, "type");
            if (btype == null) {
                continue;
            }
            switch (btype) {
                case "thinking" -> {
                    String t = firstNonNull(text(block, "thinking"), text(block, "text"), "");
                    out.add(new ConversationServerMessage.ThinkingState(uuid, sidechain, source, t));
                }
                case "text" -> {
                    String t = firstNonNull(text(block, "text"), "");
                    if (!t.isBlank()) {
                        out.add(new ConversationServerMessage.AssistantText(uuid, sidechain, source, t));
                    }
                }
                case "tool_use" -> out.add(mapToolUse(block, uuid, sidechain, source));
                default -> {
                    /* unknown assistant block — skip */
                }
            }
        }
        return out;
    }

    private ConversationServerMessage mapToolUse(JsonNode block, String uuid, boolean sidechain, String source) {
        String name = firstNonNull(text(block, "name"), "tool");
        String toolUseId = text(block, "id");
        JsonNode input = block.path("input");
        if (TOOL_ASK_USER_QUESTION.equals(name)) {
            return new ConversationServerMessage.Question(uuid, sidechain, source, toolUseId, parseQuestions(input));
        }
        if (TOOL_EXIT_PLAN_MODE.equals(name)) {
            String plan = firstNonNull(text(input, "plan"), "");
            return new ConversationServerMessage.PlanApproval(uuid, sidechain, source, toolUseId, plan);
        }
        return new ConversationServerMessage.ToolUse(uuid, sidechain, source, name, toolUseId, summarizeInput(input));
    }

    private List<ConversationServerMessage> mapUser(JsonNode root, String uuid, boolean sidechain, String source) {
        JsonNode content = root.path("message").path("content");
        // A user line is either a real prompt (string / text blocks → TurnStart)
        // or one carrying tool_result block(s) → ToolResult(s).
        List<ConversationServerMessage> out = new ArrayList<>();
        boolean sawToolResult = false;
        for (JsonNode block : blocks(content)) {
            if ("tool_result".equals(text(block, "type"))) {
                sawToolResult = true;
                String toolUseId = text(block, "tool_use_id");
                boolean isError = block.path("is_error").asBoolean(false);
                out.add(new ConversationServerMessage.ToolResult(
                        uuid, sidechain, source, toolUseId, isError, summarizeContent(block.path("content"))));
            }
        }
        if (sawToolResult) {
            return out;
        }
        String prompt = extractUserText(content);
        return List.of(new ConversationServerMessage.TurnStart(uuid, sidechain, source, prompt));
    }

    // ──────────────────────── helpers ────────────────────────

    private List<ConversationServerMessage.QuestionItem> parseQuestions(JsonNode input) {
        List<ConversationServerMessage.QuestionItem> items = new ArrayList<>();
        JsonNode questions = input.path("questions");
        if (questions.isArray()) {
            for (JsonNode q : questions) {
                List<ConversationServerMessage.Option> options = new ArrayList<>();
                JsonNode opts = q.path("options");
                if (opts.isArray()) {
                    for (JsonNode o : opts) {
                        options.add(new ConversationServerMessage.Option(
                                firstNonNull(text(o, "label"), ""), firstNonNull(text(o, "description"), "")));
                    }
                }
                items.add(new ConversationServerMessage.QuestionItem(
                        firstNonNull(text(q, "question"), ""),
                        firstNonNull(text(q, "header"), ""),
                        q.path("multiSelect").asBoolean(false),
                        options));
            }
        }
        return items;
    }

    /** Compact, bounded rendering of a tool's input object (AC4 — summarize, don't dump). */
    private String summarizeInput(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        if (input.isValueNode()) {
            return bound(input.asText());
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> names = input.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode v = input.get(field);
            String rendered = v.isValueNode() ? v.asText() : v.toString();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(field).append('=').append(bound(rendered, 120));
            if (sb.length() >= MAX_SUMMARY_LEN) {
                break;
            }
        }
        return bound(sb.toString());
    }

    private String summarizeContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return bound(content.asText());
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                String t = b.isTextual() ? b.asText() : firstNonNull(text(b, "text"), "");
                if (!t.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return bound(sb.toString());
        }
        return bound(content.toString());
    }

    private String extractUserText(JsonNode content) {
        if (content == null || content.isMissingNode()) {
            return "";
        }
        if (content.isTextual()) {
            return bound(content.asText(), MAX_SUMMARY_LEN);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : blocks(content)) {
            if ("text".equals(text(block, "type"))) {
                String t = firstNonNull(text(block, "text"), "");
                if (!t.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
        }
        return bound(sb.toString(), MAX_SUMMARY_LEN);
    }

    /** Normalize a message {@code content} (string | array | object) to an iterable of block nodes. */
    private static Iterable<JsonNode> blocks(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return List.of();
        }
        if (content.isArray()) {
            return content;
        }
        // A non-array content (string or single object) has no per-block structure.
        return List.of();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : (v != null && v.isValueNode() ? v.asText() : null);
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String bound(String s) {
        return bound(s, MAX_SUMMARY_LEN);
    }

    private static String bound(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "…";
    }
}
