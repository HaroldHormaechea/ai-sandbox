package com.aisandbox.server.stream.service;

import com.aisandbox.server.stream.dto.ConversationServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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

    /** UC-41 — the skill-invocation tool; {@code primaryText} is its skill name (AC1). */
    public static final String TOOL_SKILL = "Skill";

    /** UC-41 — the shell tool; {@code primaryText} is its command (AC2). */
    public static final String TOOL_BASH = "Bash";

    /** Cap on a rendered tool-input summary so internal tool noise is summarized, not dumped (AC4). */
    private static final int MAX_SUMMARY_LEN = 600;

    /**
     * UC-41 (AC6) — byte cap on the on-demand {@link #renderDetail} payload (input +
     * result). Generous compared to the 600-char streaming summary, but bounded so a
     * pathological multi-MB tool result can never OOM the device or flood the socket.
     * 48&nbsp;KB. Mirrored (as a default) by {@code ServerProperties.conversationDetailMaxBytes()}.
     */
    public static final int CONVERSATION_DETAIL_MAX_BYTES = 49152;

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
        return new ConversationServerMessage.ToolUse(
                uuid, sidechain, source, name, toolUseId, summarizeInput(input), primaryText(name, input));
    }

    /**
     * UC-41 (D2, AC1/AC2/AC3) — extract the single type-aware label <em>value</em>
     * server-side; the client formats the surrounding text and applies the ~20-char
     * snippet budget. {@code Skill} → its skill name (the {@code skill}/{@code name}/
     * {@code command} input field, in that fallback order); {@code Bash} → its
     * {@code command}; any other tool → the existing bounded input summary so the
     * generic "{@code <tool>}: {@code <snippet>}" label has a value to show.
     */
    private String primaryText(String name, JsonNode input) {
        if (TOOL_SKILL.equals(name)) {
            String skill = firstNonNull(text(input, "skill"), text(input, "name"), text(input, "command"));
            return bound(skill != null ? skill : summarizeInput(input));
        }
        if (TOOL_BASH.equals(name)) {
            String command = text(input, "command");
            return bound(command != null ? command : summarizeInput(input));
        }
        return summarizeInput(input);
    }

    /**
     * UC-41 (AC5/AC6/AC9) — render the FULL, untruncated input + result for a single
     * tool call from a set of raw transcript lines re-read on demand. Each entry of
     * {@code rawLines} is either a bare transcript JSON object or the helper's
     * {@code <source>\t<raw-json>} envelope (the tab-prefix is stripped). The method
     * scans every line for the {@code tool_use} block whose {@code id} matches
     * {@code toolUseId} (→ full input) and the {@code tool_result} block whose
     * {@code tool_use_id} matches (→ full result + {@code isError}). Output is
     * bounded only to {@link #CONVERSATION_DETAIL_MAX_BYTES}, NOT the 600-char
     * streaming cap. {@code available} is {@code false} when neither block is found
     * (scrolled out / expired / helper miss → AC9). Never throws on malformed input.
     */
    public DetailRender renderDetail(String toolUseId, List<String> rawLines) {
        if (toolUseId == null || toolUseId.isBlank() || rawLines == null) {
            return DetailRender.unavailable();
        }
        String toolName = null;
        String input = null;
        String result = null;
        String foldedBody = null; // UC-42 (D3) — a Skill SKILL.md body folded into this bubble
        boolean isError = false;
        boolean found = false;
        for (String entry : rawLines) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String raw = stripEnvelope(entry);
            JsonNode root;
            try {
                root = json.readTree(raw);
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                continue; // skip a malformed line without crashing
            }
            if (root == null || !root.isObject()) {
                continue;
            }
            JsonNode content = root.path("message").path("content");
            // UC-42 (D3) — a harness-injected line (e.g. a Skill SKILL.md body) carries
            // the host tool's id at the TOP LEVEL as {@code sourceToolUseID}, NOT inside
            // a tool_use/tool_result block. Render its FULL body and PREFER it over the
            // tiny "Launching skill…" tool_result so the Skill bubble's tap detail shows
            // the actual skill body (AC2). Evaluated before the per-block scan.
            String sourceToolUseId = text(root, "sourceToolUseID");
            if (sourceToolUseId != null && toolUseId.equals(sourceToolUseId)) {
                foldedBody = renderContentFull(content);
                found = true;
                continue;
            }
            for (JsonNode block : blocks(content)) {
                String btype = text(block, "type");
                if ("tool_use".equals(btype) && toolUseId.equals(text(block, "id"))) {
                    toolName = firstNonNull(text(block, "name"), toolName);
                    input = renderInputFull(block.path("input"));
                    found = true;
                } else if ("tool_result".equals(btype) && toolUseId.equals(text(block, "tool_use_id"))) {
                    isError = block.path("is_error").asBoolean(false);
                    result = renderContentFull(block.path("content"));
                    found = true;
                }
            }
        }
        if (!found) {
            return DetailRender.unavailable();
        }
        // UC-42 (D3) — the folded injected body wins over the host tool_result and is
        // never an error; toolName/input still come from the Skill tool_use.
        if (foldedBody != null) {
            return new DetailRender(toolName, input == null ? "" : input, foldedBody, false, true);
        }
        return new DetailRender(toolName, input == null ? "" : input, result == null ? "" : result, isError, true);
    }

    /**
     * UC-41 — the rendered on-demand detail of one tool call (pure data; the facade
     * wraps it into its internal view and the handler maps it to a {@code tool-detail}
     * frame). {@code available=false} carries empty input/result.
     */
    public record DetailRender(String toolName, String input, String result, boolean isError, boolean available) {
        static DetailRender unavailable() {
            return new DetailRender(null, "", "", false, false);
        }
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
        // UC-42 (D1) — classify a non-tool_result user line. A genuine prompt → TurnStart;
        // a harness-INJECTED line (Skill body / slash-command wrapper / local-command-stdout
        // / isMeta system note) is NOT the user's own message and must NOT render
        // right-aligned. Structural markers only (AC7) — no content-shape heuristics — in
        // this exact priority order:
        //
        //   1. top-level sourceToolUseID nonblank → FOLD (emit nothing): the host Skill
        //      ToolUse bubble already exists and the body is delivered as its tap detail via
        //      the FetchDetail round-trip (D3). SAFE-BY-DEFAULT Skill-host assumption: every
        //      observed sourceToolUseID line corresponds to a Skill tool_use bubble. If the
        //      host ever weren't present this degrades to "detail unreachable" on tap — never
        //      a spurious right-aligned prompt, which is strictly better than today's dump.
        //   2. else isMeta == true → generic SystemNote.
        //   3. else string content is a <command-name>…</command-name> + <command-args>
        //      wrapper → SystemNote "Command: <name>".
        //   4. else string content is a <local-command-stdout> wrapper → SystemNote
        //      "Command output".
        //   5. else → TurnStart (a genuine prompt), unchanged.
        String sourceToolUseId = text(root, "sourceToolUseID");
        if (sourceToolUseId != null && !sourceToolUseId.isBlank()) {
            return List.of();
        }
        if (root.path("isMeta").asBoolean(false)) {
            return List.of(new ConversationServerMessage.SystemNote(
                    uuid, sidechain, source, "System note", renderContentFull(content)));
        }
        String stringContent = content.isTextual() ? content.asText().strip() : null;
        if (stringContent != null
                && stringContent.startsWith("<command-name>")
                && stringContent.contains("</command-name>")
                && stringContent.contains("<command-args>")) {
            String name = sliceBetween(stringContent, "<command-name>", "</command-name>").strip();
            String label = "Command: " + (name.isBlank() ? "?" : name);
            return List.of(new ConversationServerMessage.SystemNote(
                    uuid, sidechain, source, label, renderContentFull(content)));
        }
        if (stringContent != null && stringContent.startsWith("<local-command-stdout>")) {
            return List.of(new ConversationServerMessage.SystemNote(
                    uuid, sidechain, source, "Command output", renderContentFull(content)));
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

    /**
     * UC-41 — full, byte-bounded rendering of a tool's input object for the detail
     * dialog (AC5/AC6). Unlike {@link #summarizeInput}, fields are NOT individually
     * clamped to 120 chars and the whole is bounded to {@link #CONVERSATION_DETAIL_MAX_BYTES}
     * (48&nbsp;KB) rather than 600 chars. A single-field input renders as the bare value
     * (e.g. a {@code Bash} command); a multi-field input renders {@code field: value} per line.
     */
    private String renderInputFull(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        if (input.isValueNode()) {
            return boundBytes(input.asText(), CONVERSATION_DETAIL_MAX_BYTES);
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> names = input.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            JsonNode v = input.get(field);
            String rendered = v.isValueNode() ? v.asText() : v.toString();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(field).append(": ").append(rendered);
            if (sb.length() >= CONVERSATION_DETAIL_MAX_BYTES) {
                break;
            }
        }
        return boundBytes(sb.toString(), CONVERSATION_DETAIL_MAX_BYTES);
    }

    /**
     * UC-41 — full, byte-bounded rendering of a {@code tool_result} content (AC5/AC6).
     * Mirrors {@link #summarizeContent} but bounded to {@link #CONVERSATION_DETAIL_MAX_BYTES}
     * instead of 600 chars.
     */
    private String renderContentFull(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return boundBytes(content.asText(), CONVERSATION_DETAIL_MAX_BYTES);
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                String t = b.isTextual() ? b.asText() : firstNonNull(text(b, "text"), "");
                if (t != null && !t.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
                if (sb.length() >= CONVERSATION_DETAIL_MAX_BYTES) {
                    break;
                }
            }
            return boundBytes(sb.toString(), CONVERSATION_DETAIL_MAX_BYTES);
        }
        return boundBytes(content.toString(), CONVERSATION_DETAIL_MAX_BYTES);
    }

    /** Strip the helper's {@code <source>\t<raw-json>} envelope, if present, to the raw JSON half. */
    private static String stripEnvelope(String entry) {
        int tab = entry.indexOf('\t');
        return tab < 0 ? entry : entry.substring(tab + 1);
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

    /**
     * UC-42 — slice the substring between the first {@code open} tag and the first
     * {@code close} tag following it. Structural (positional), used to extract the
     * command name from a {@code <command-name>…</command-name>} wrapper — not a
     * substring search for content. Returns {@code ""} when either tag is absent.
     */
    private static String sliceBetween(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) {
            return "";
        }
        a += open.length();
        int b = s.indexOf(close, a);
        return b < 0 ? "" : s.substring(a, b);
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

    /**
     * UC-41 — bound a string to {@code maxBytes} UTF-8 bytes (NOT chars), appending an
     * ellipsis when truncated. Used for the detail payload so a multibyte tool result
     * can never exceed the device-safe cap. Does not {@code strip()} — detail content
     * preserves its internal whitespace/newlines (only the cap is enforced).
     */
    private static String boundBytes(String s, int maxBytes) {
        if (s == null) {
            return "";
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes) {
            return s;
        }
        // Truncate at a char boundary whose UTF-8 encoding fits within maxBytes.
        int end = s.length();
        while (end > 0 && s.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            // Step back proportionally first for large strings, then refine by one.
            int overshoot = s.substring(0, end).getBytes(StandardCharsets.UTF_8).length - maxBytes;
            end -= Math.max(1, overshoot / 4);
        }
        if (end < 0) {
            end = 0;
        }
        return s.substring(0, end) + "…";
    }
}
