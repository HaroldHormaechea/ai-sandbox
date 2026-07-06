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
 * <p>Transcript shape (pinned Claude Code {@code 2.1.169} — see {@link
 * InputInjectionService#PINNED_CLAUDE_VERSION}; shape originally captured on
 * {@code 2.1.159}, RND §10–§11, and reconciled up to the pinned version in UC-97
 * as the pane path — not the transcript — now carries pending questions):
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

    /** UC-58 — the teammate-message envelope tag name. */
    private static final String TEAMMATE_TAG = "teammate-message";

    /** UC-58 — the opening-tag prefix used as the structural reclassification marker (AC3). */
    private static final String TEAMMATE_OPEN_TAG = "<" + TEAMMATE_TAG;

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
            String name = sliceBetween(stringContent, "<command-name>", "</command-name>")
                    .strip();
            String label = "Command: " + (name.isBlank() ? "?" : name);
            return List.of(new ConversationServerMessage.SystemNote(
                    uuid, sidechain, source, label, renderContentFull(content)));
        }
        if (stringContent != null && stringContent.startsWith("<local-command-stdout>")) {
            return List.of(new ConversationServerMessage.SystemNote(
                    uuid, sidechain, source, "Command output", renderContentFull(content)));
        }
        // UC-58 (rule 5) — a teammate/subagent message delivered to a team-lead session
        // as a user-role line whose string content is a <teammate-message …>…</teammate-message>
        // envelope. Reclassify it to a dedicated TeammateMessage frame so it renders as a
        // distinct, sender-attributed, NON-user bubble — never right-aligned as the user's
        // own message. Keyed off the STRUCTURAL marker at the start of content (the opening
        // tag), so a genuine prompt that merely mentions the literal text stays a TurnStart
        // (AC3). A malformed envelope (no closing '>') degrades to TurnStart rather than
        // dropping the line. BEFORE the rule-6 TurnStart fallback.
        if (stringContent != null && stringContent.startsWith(TEAMMATE_OPEN_TAG)) {
            TeammateEnvelope env = parseTeammateEnvelope(stringContent);
            if (env != null) {
                return List.of(new ConversationServerMessage.TeammateMessage(
                        uuid,
                        sidechain,
                        source,
                        env.teammateId(),
                        env.color() == null ? "" : env.color(),
                        cleanTeammateContent(env.inner())));
            }
        }
        String prompt = extractUserText(content);
        return List.of(new ConversationServerMessage.TurnStart(uuid, sidechain, source, prompt));
    }

    /**
     * UC-50 — pure mapper from the helper's pane-signal {@code pending-question}
     * JSON payload ({@code {kind,questions,plan,key}}) to a typed {@link
     * ConversationServerMessage.PendingPrompt}. The {@code answerable} flag is
     * decided HERE (server-side, never inferred client-side): plan approval and a
     * single question are fully in-app answerable; a multi-question batch
     * ({@code questions.size() > 1}) is delivered visible but {@code answerable=false}
     * (the user answers in tmux — only the focused tab's options are recoverable
     * from one pane capture, so full in-app multi-answer is a tracked follow-up).
     * Returns {@code null} on a malformed / empty payload so the handler can skip it
     * without crashing the pump (mirrors {@link #map}'s AC20 robustness).
     */
    public ConversationServerMessage.PendingPrompt mapPendingPrompt(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = json.readTree(rawJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        String key = text(root, "key");
        if (key == null || key.isBlank()) {
            return null;
        }
        String kind = firstNonNull(text(root, "kind"), "questions");
        String plan = firstNonNull(text(root, "plan"), "");
        List<ConversationServerMessage.QuestionItem> questions = parseQuestions(root);
        return new ConversationServerMessage.PendingPrompt(key, kind, questions, plan, answerable(kind, questions));
    }

    /**
     * UC-55 — the server-side, single-sourced decision of whether a pending prompt is
     * in-app answerable. {@code answerable=false} is reserved strictly for genuinely
     * unrecoverable prompts: a plan approval is always answerable; an {@code
     * AskUserQuestion} is answerable iff EVERY question carries a recoverable option
     * set ({@link #allQuestionsHaveOptions}). This replaces the UC-50 {@code
     * questions.size() <= 1} gate that hardcoded a multi-question batch to non-answerable
     * — under UC-55 the handler recovers all tabs' options (stepping the live pane) and
     * re-maps the prompt through this same predicate, so a multi-question wizard whose
     * options have been recovered flips to {@code answerable=true} (AC2/AC5/AC10).
     * Both the initial map and the post-recovery re-map go through here so the rule can
     * never drift between the two call sites.
     */
    public boolean answerable(String kind, List<ConversationServerMessage.QuestionItem> questions) {
        return "plan".equals(kind) || allQuestionsHaveOptions(questions);
    }

    /**
     * UC-55 — true iff there is at least one question and EVERY question carries a
     * non-empty option list. A header-only multi-question batch (UC-50's pane recovery
     * before stepping) has empty option lists → false (so it is NOT shown as in-app
     * answerable until the options are recovered); a fully recovered batch → true.
     */
    private static boolean allQuestionsHaveOptions(List<ConversationServerMessage.QuestionItem> questions) {
        if (questions == null || questions.isEmpty()) {
            return false;
        }
        for (ConversationServerMessage.QuestionItem q : questions) {
            if (q.options() == null || q.options().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * UC-50 — build the {@link ConversationServerMessage.Question} the handler caches
     * for a pane-delivered {@link ConversationServerMessage.PendingPrompt}, keyed by
     * {@code promptKey} (both {@code uuid} and {@code toolUseId}), so an in-app answer
     * that echoes the promptKey resolves to the same per-question option metadata the
     * single/batch answer-spec derivation reads. Pure; not a transcript frame.
     */
    public ConversationServerMessage.Question pendingPromptToQuestion(ConversationServerMessage.PendingPrompt pp) {
        return new ConversationServerMessage.Question(pp.promptKey(), false, "main", pp.promptKey(), pp.questions());
    }

    /**
     * UC-55 — parse ONE wizard tab's recovered content from the helper's {@code
     * --parse-pane} JSON ({@code {question,multiSelect,options:[{label,description}]}})
     * into a {@link ConversationServerMessage.QuestionItem}, stamping the {@code header}
     * the caller already knows (the server owns the tab order from the initial header-only
     * payload, so it pairs each stepped capture with its header — {@code --parse-pane}
     * deliberately does not try to identify the focused tab's header from plain pane text).
     * Returns {@code null} for a blank / malformed payload OR an empty option list (an
     * unrecovered tab, e.g. {@code {}} from a capture miss) so the caller leaves that tab
     * header-only and the whole batch correctly stays {@code answerable=false} rather than
     * rendering a tab with no options. Never throws (mirrors {@link #mapPendingPrompt}).
     */
    public ConversationServerMessage.QuestionItem parseFocusedTab(String tabJson, String header) {
        if (tabJson == null || tabJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = json.readTree(tabJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode opts = root.path("options");
        if (!opts.isArray() || opts.isEmpty()) {
            return null; // unrecovered tab — no options to render
        }
        List<ConversationServerMessage.Option> options = new ArrayList<>();
        for (JsonNode o : opts) {
            options.add(new ConversationServerMessage.Option(
                    firstNonNull(text(o, "label"), ""), firstNonNull(text(o, "description"), "")));
        }
        return new ConversationServerMessage.QuestionItem(
                firstNonNull(text(root, "question"), ""),
                header == null ? "" : header,
                root.path("multiSelect").asBoolean(false),
                options);
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
            // UC-80 — render the full user prompt (no 600-char crop), byte-bounded to
            // CONVERSATION_DETAIL_MAX_BYTES (UTF-8-boundary-safe) like renderContentFull/renderInputFull.
            return boundBytes(content.asText(), CONVERSATION_DETAIL_MAX_BYTES);
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
        // UC-80 — full user text, byte-bounded (not 600-char cropped).
        return boundBytes(sb.toString(), CONVERSATION_DETAIL_MAX_BYTES);
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

    // ──────────────────── UC-58 teammate-message envelope parsing ────────────────────

    /** The parsed pieces of a {@code <teammate-message …>…</teammate-message>} envelope. */
    private record TeammateEnvelope(String teammateId, String color, String inner) {}

    /**
     * UC-58 — parse a {@code <teammate-message teammate_id="…" color="…">…</teammate-message>}
     * envelope (the harness's representation of an inbound teammate/subagent message on a
     * team-lead session). Returns {@code null} when {@code content} is not a <b>well-formed</b>
     * envelope — i.e. it does not begin with the opening tag, the opening tag has no
     * terminating {@code '>'}, it carries no {@code teammate_id} attribute, or it has no
     * closing {@code </teammate-message>} tag — so the caller falls back to a
     * {@link ConversationServerMessage.TurnStart} rather than dropping the line.
     *
     * <p>The opening tag's end is located by scanning for the {@code '>'} that terminates
     * it <b>while respecting quoted attribute values</b> — an attribute such as
     * {@code summary="… > …"} contains a {@code '>'} that must NOT be mistaken for the tag
     * end (challenger Minor #1), or the markup would leak into the rendered bubble. The
     * inner content runs from just after the opening tag to the LAST closing
     * {@code </teammate-message>}.
     */
    private TeammateEnvelope parseTeammateEnvelope(String content) {
        if (content == null) {
            return null;
        }
        String s = content.strip();
        if (!s.startsWith(TEAMMATE_OPEN_TAG)) {
            return null;
        }
        // The char right after the tag NAME must be a tag boundary, so "<teammate-messageX>"
        // is not matched as a teammate envelope.
        if (s.length() > TEAMMATE_OPEN_TAG.length()) {
            char after = s.charAt(TEAMMATE_OPEN_TAG.length());
            if (after != '>' && after != '/' && !Character.isWhitespace(after)) {
                return null;
            }
        }
        // Find the '>' that ends the opening tag, respecting quoted attribute values.
        int i = TEAMMATE_OPEN_TAG.length();
        char quote = 0;
        int tagEnd = -1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                tagEnd = i;
                break;
            }
            i++;
        }
        if (tagEnd < 0) {
            return null; // malformed — no terminating '>' for the opening tag
        }
        String attrs = s.substring(TEAMMATE_OPEN_TAG.length(), tagEnd);
        String closeTag = "</" + TEAMMATE_TAG + ">";
        int close = s.lastIndexOf(closeTag);
        if (close < tagEnd + 1) {
            return null; // malformed / half envelope — no closing tag
        }
        java.util.Map<String, String> attrMap = parseAttributes(attrs);
        String teammateId = attrMap.get("teammate_id");
        if (teammateId == null || teammateId.isBlank()) {
            return null; // not well-formed — a teammate envelope must name its sender
        }
        String inner = s.substring(tagEnd + 1, close);
        return new TeammateEnvelope(teammateId, attrMap.get("color"), inner);
    }

    /**
     * UC-58 — parse XML-ish {@code name="value"} (or {@code name='value'}, or bare
     * {@code name=value}, or valueless {@code name}) attribute pairs from an opening-tag
     * attribute string into a map. Robust to whitespace and to a trailing {@code '/'} on a
     * self-closing tag. Keyed exactly so {@code teammate_id} is never confused with a
     * shorter {@code id} substring.
     */
    private static java.util.Map<String, String> parseAttributes(String attrs) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (attrs == null) {
            return map;
        }
        int i = 0;
        int n = attrs.length();
        while (i < n) {
            while (i < n && !isAttrNameChar(attrs.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && isAttrNameChar(attrs.charAt(i))) {
                i++;
            }
            if (i == start) {
                break;
            }
            String key = attrs.substring(start, i);
            while (i < n && Character.isWhitespace(attrs.charAt(i))) {
                i++;
            }
            if (i < n && attrs.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(attrs.charAt(i))) {
                    i++;
                }
                if (i < n && (attrs.charAt(i) == '"' || attrs.charAt(i) == '\'')) {
                    char q = attrs.charAt(i++);
                    int vs = i;
                    while (i < n && attrs.charAt(i) != q) {
                        i++;
                    }
                    map.put(key, attrs.substring(vs, Math.min(i, n)));
                    if (i < n) {
                        i++; // consume closing quote
                    }
                } else {
                    int vs = i;
                    while (i < n && !Character.isWhitespace(attrs.charAt(i)) && attrs.charAt(i) != '/') {
                        i++;
                    }
                    map.put(key, attrs.substring(vs, i));
                }
            } else {
                map.put(key, "");
            }
        }
        return map;
    }

    private static boolean isAttrNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':';
    }

    /**
     * UC-58 — clean a teammate envelope's inner content for rendering (AC6). Plain inner
     * text is returned as-is (stripped). When the inner content is a nested JSON envelope
     * (e.g. {@code {"type":"idle_notification",…}}), a short readable label is derived from
     * it instead — preferring a {@code summary}/{@code text}/{@code message}/{@code content}
     * field, prefixed by its {@code type} when present — so raw JSON never leaks into the
     * bubble. The JSON-derived label is bounded to {@link #MAX_SUMMARY_LEN}; plain text is
     * returned in full (the renderer handles wrapping), mirroring assistant-text rendering.
     */
    private String cleanTeammateContent(String inner) {
        if (inner == null) {
            return "";
        }
        String t = inner.strip();
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode node = json.readTree(t);
                if (node != null && node.isObject()) {
                    String type = text(node, "type");
                    String summary = firstNonNull(
                            text(node, "summary"), text(node, "text"), text(node, "message"), text(node, "content"));
                    if (summary != null && !summary.isBlank()) {
                        return type != null ? "[" + type + "] " + bound(summary) : bound(summary);
                    }
                    if (type != null) {
                        return "[" + type + "]";
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                // not valid JSON — fall through and render the inner text as-is
            }
        }
        return t;
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
