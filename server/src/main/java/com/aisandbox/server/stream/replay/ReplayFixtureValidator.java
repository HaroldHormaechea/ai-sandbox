package com.aisandbox.server.stream.replay;

import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.TranscriptTailService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 (AC-2) — validates recorded replay fixtures at load time and <b>fails LOUD</b> on any
 * drift or malformation, so a stale or hand-corrupted fixture can never silently produce a
 * green gate. Validation is structural, run once per fixture by {@link ReplaySessionCatalog}
 * during boot; any failure throws and aborts server startup (the gate launcher then reports
 * the bad fixture rather than running against it).
 *
 * <p>Rules enforced per fixture:
 *
 * <ol>
 *   <li>The fixture is non-empty.</li>
 *   <li>Every line is a well-formed envelope: either a {@code __ctrl__\t<kind>…} helper control
 *       line, a {@code __replay__\t<directive>} replay directive (consumed by the reader, never
 *       sent to the pump), or a {@code <source>\t<raw-json>} transcript line whose JSON half
 *       parses. A malformed JSON transcript line fails loud.</li>
 *   <li>If the fixture raises an {@code AskUserQuestion} (a {@code tool_use} with that name, or a
 *       {@code __ctrl__\tpending-question} pane signal), it MUST contain at least one
 *       {@code __replay__\tawait-answer} gate, and after the LAST such gate there MUST be at
 *       least one {@code turn-end} ({@code system}/{@code turn_duration}) line — guaranteeing the
 *       UC-75 spinner / answer-watchdog clears once the device answers.</li>
 * </ol>
 *
 * <p>The manifest's own {@code schemaVersion} is checked separately against {@link
 * #SCHEMA_VERSION} by {@link ReplaySessionCatalog}.
 */
@Component
@Profile("replay")
public class ReplayFixtureValidator {

    /**
     * The fixture-format schema version this server understands. A {@code manifest.json}
     * declaring a different {@code schemaVersion} is rejected at boot (AC-2 drift detection).
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Reserved envelope source for replay-only directives the {@code ReplayEnvelopeReader}
     * consumes itself (never handed to the conversation pump). Distinct from the helper's
     * {@code __ctrl__} control source so a directive can never be confused with a real frame.
     */
    public static final String REPLAY_DIRECTIVE_SOURCE = "__replay__";

    /**
     * Replay directive: park the tail here until the device's answer is recorded for this
     * session (then continue replaying the recorded post-answer frames). See {@link
     * ReplayEnvelopeReader}.
     */
    public static final String DIRECTIVE_AWAIT_ANSWER = "await-answer";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Validate one fixture. Throws {@link IllegalStateException} (loud, boot-aborting) on any
     * violation, naming the offending fixture and line.
     */
    public void validate(String fixtureName, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalStateException("replay fixture '" + fixtureName + "' is empty");
        }
        boolean hasQuestion = false;
        int lastAwaitIdx = -1;
        boolean turnEndAfterAwait = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isEmpty()) {
                continue; // tolerate blank separator lines
            }
            int tab = line.indexOf('\t');
            String source = tab < 0 ? line : line.substring(0, tab);
            String payload = tab < 0 ? "" : line.substring(tab + 1);

            if (REPLAY_DIRECTIVE_SOURCE.equals(source)) {
                String directive = firstField(payload);
                if (!DIRECTIVE_AWAIT_ANSWER.equals(directive)) {
                    throw new IllegalStateException("replay fixture '" + fixtureName + "' line " + (i + 1)
                            + ": unknown replay directive '" + directive + "'");
                }
                lastAwaitIdx = i;
                turnEndAfterAwait = false;
                continue;
            }
            if (TranscriptTailService.CTRL_SOURCE.equals(source)) {
                String kind = firstField(payload);
                if (TranscriptTailService.CTRL_PENDING_QUESTION.equals(kind)) {
                    hasQuestion = true;
                }
                continue; // control lines carry no transcript JSON to parse
            }
            // A transcript envelope line — its JSON half MUST parse (AC-2 malformed detection).
            JsonNode root;
            try {
                root = json.readTree(payload);
            } catch (JsonProcessingException jpe) {
                throw new IllegalStateException("replay fixture '" + fixtureName + "' line " + (i + 1)
                        + ": malformed transcript JSON: " + jpe.getOriginalMessage());
            }
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("replay fixture '" + fixtureName + "' line " + (i + 1)
                        + ": transcript line is not a JSON object");
            }
            if (raisesQuestion(root)) {
                hasQuestion = true;
            }
            if (lastAwaitIdx >= 0 && isTurnEnd(root)) {
                turnEndAfterAwait = true;
            }
        }
        if (hasQuestion) {
            if (lastAwaitIdx < 0) {
                throw new IllegalStateException("replay fixture '" + fixtureName
                        + "' raises an AskUserQuestion but has no '__replay__\\tawait-answer' gate "
                        + "(the device's answer would never be awaited)");
            }
            if (!turnEndAfterAwait) {
                throw new IllegalStateException("replay fixture '" + fixtureName
                        + "' has no post-answer turn-end frame after its await-answer gate "
                        + "(the UC-75 spinner / answer-watchdog would never clear)");
            }
        }
    }

    /** True iff this assistant transcript line carries an {@code AskUserQuestion} tool_use block. */
    private boolean raisesQuestion(JsonNode root) {
        if (!"assistant".equals(text(root, "type"))) {
            return false;
        }
        JsonNode content = root.path("message").path("content");
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(text(block, "type"))
                    && ConversationEventMapper.TOOL_ASK_USER_QUESTION.equals(text(block, "name"))) {
                return true;
            }
        }
        return false;
    }

    /** True iff this line is the explicit end-of-turn marker (a {@code system}/{@code turn_duration} line). */
    private boolean isTurnEnd(JsonNode root) {
        return "system".equals(text(root, "type")) && "turn_duration".equals(text(root, "subtype"));
    }

    private static String firstField(String payload) {
        int tab = payload.indexOf('\t');
        return (tab < 0 ? payload : payload.substring(0, tab)).trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v != null && v.isValueNode()) ? v.asText() : null;
    }
}
