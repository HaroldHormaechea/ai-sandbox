package com.aisandbox.server.stream.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aisandbox.server.stream.service.ConversationEventMapper;
import com.aisandbox.server.stream.service.TranscriptTailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UC-85 (AC-2) — drift-detection contract for the deterministic-gate replay fixtures.
 *
 * <p>The replay gate is only meaningful if a stale or hand-corrupted fixture can never
 * silently produce a green run. {@link ReplayFixtureValidator} is the loud-fail guard that
 * {@link ReplaySessionCatalog} runs over every fixture at boot. This test pins both halves of
 * AC-2:
 *
 * <ol>
 *   <li><b>It fails loud on drift / malformation</b> — a malformed transcript JSON line, a
 *       question fixture with no {@code await-answer} gate, and a question fixture with no
 *       post-answer turn-end frame each abort with a descriptive {@link IllegalStateException}
 *       (schema-version drift is covered by {@link ReplaySessionCatalogTest}). An empty fixture
 *       fails too.</li>
 *   <li><b>The committed fixtures all PASS</b> — every {@code *.tail} named in the repo's
 *       {@code fixtures/replay/manifest.json} validates cleanly, and the manifest's
 *       {@code schemaVersion} matches {@link ReplayFixtureValidator#SCHEMA_VERSION} — so a real
 *       gate run is starting from valid fixtures, not skipping a broken one.</li>
 * </ol>
 *
 * <p>The validator has no Spring dependencies (it is a plain {@code @Component} with a default
 * constructor), so it is exercised directly here — no context boot needed.
 */
class ReplayFixtureValidatorTest {

    /** Test JVM cwd is {@code server/}; the committed fixtures live at {@code <repo>/fixtures/replay}. */
    private static final Path FIXTURES_DIR = Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("fixtures")
            .resolve("replay");

    private final ReplayFixtureValidator validator = new ReplayFixtureValidator();

    private static String tx(String json) {
        return "main\t" + json;
    }

    private static String await() {
        return ReplayFixtureValidator.REPLAY_DIRECTIVE_SOURCE + "\t" + ReplayFixtureValidator.DIRECTIVE_AWAIT_ANSWER;
    }

    private static String questionLine(String toolUseId) {
        return tx("{\"type\":\"assistant\",\"uuid\":\"q1\",\"isSidechain\":false,\"message\":{\"content\":[{"
                + "\"type\":\"tool_use\",\"id\":\"" + toolUseId + "\",\"name\":\""
                + ConversationEventMapper.TOOL_ASK_USER_QUESTION
                + "\",\"input\":{\"questions\":[{\"question\":\"Pick one\",\"header\":\"H\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"A\",\"description\":\"a\"}]}]}}]}}");
    }

    private static String turnEnd() {
        return tx("{\"type\":\"system\",\"subtype\":\"turn_duration\",\"uuid\":\"te1\",\"durationMs\":10,"
                + "\"messageCount\":2}");
    }

    // ──────────────────────── loud-fail on drift / malformation ────────────────────────

    @Test
    void emptyFixtureFailsLoud() {
        assertThatThrownBy(() -> validator.validate("empty.tail", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty.tail")
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> validator.validate("null.tail", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedTranscriptJsonLineFailsLoud() {
        List<String> lines = List.of(
                "__ctrl__\tbackfill-start\t0",
                tx("{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}"),
                tx("{this is not valid json"),
                "__ctrl__\tbackfill-end");
        assertThatThrownBy(() -> validator.validate("bad-json.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad-json.tail")
                .hasMessageContaining("malformed");
    }

    @Test
    void nonObjectTranscriptLineFailsLoud() {
        List<String> lines = List.of(tx("[1,2,3]"));
        assertThatThrownBy(() -> validator.validate("array.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void unknownReplayDirectiveFailsLoud() {
        List<String> lines = List.of(ReplayFixtureValidator.REPLAY_DIRECTIVE_SOURCE + "\tteleport");
        assertThatThrownBy(() -> validator.validate("bad-directive.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown replay directive");
    }

    @Test
    void questionWithoutAwaitGateFailsLoud() {
        // An AskUserQuestion is raised but the fixture never parks at an await-answer gate, so the
        // device's answer would never be awaited — drift that must fail loud.
        List<String> lines = List.of("__ctrl__\tbackfill-start\t0", questionLine("tu-x"), "__ctrl__\tbackfill-end");
        assertThatThrownBy(() -> validator.validate("no-gate.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("await-answer");
    }

    @Test
    void questionWithoutPostAnswerTurnEndFailsLoud() {
        // A gate is present, but there is no turn-end after it — the UC-75 spinner / answer
        // watchdog would never clear once the device answers.
        List<String> lines = List.of(
                "__ctrl__\tbackfill-start\t0",
                questionLine("tu-x"),
                "__ctrl__\tbackfill-end",
                await(),
                tx("{\"type\":\"assistant\",\"uuid\":\"a2\",\"message\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"done\"}]}}"));
        assertThatThrownBy(() -> validator.validate("no-turn-end.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turn-end");
    }

    @Test
    void pendingQuestionCtrlAlsoRequiresGate() {
        // A pane-derived pending-question control signal counts as raising a question too, so it
        // is subject to the same await-answer requirement.
        List<String> lines = List.of(
                TranscriptTailService.CTRL_SOURCE + "\t" + TranscriptTailService.CTRL_PENDING_QUESTION + "\tkey1");
        assertThatThrownBy(() -> validator.validate("pending-no-gate.tail", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("await-answer");
    }

    // ──────────────────────── a well-formed fixture passes ────────────────────────

    @Test
    void wellFormedQuestionFixturePasses() {
        List<String> lines = List.of(
                "__ctrl__\tbackfill-start\t0",
                tx("{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"pick\"}}"),
                questionLine("tu-x"),
                "__ctrl__\tbackfill-end",
                await(),
                tx("{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                        + "\"tool_use_id\":\"tu-x\",\"is_error\":false,\"content\":\"ok\"}]}}"),
                turnEnd());
        assertThatCode(() -> validator.validate("ok.tail", lines)).doesNotThrowAnyException();
    }

    @Test
    void fixtureWithNoQuestionNeedsNoGate() {
        // A pure conversation-transcript fixture (no AskUserQuestion) does not need an await gate.
        List<String> lines = List.of(
                "__ctrl__\tbackfill-start\t0",
                tx("{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hello\"}}"),
                tx("{\"type\":\"assistant\",\"uuid\":\"a1\",\"message\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"hi\"}]}}"),
                "__ctrl__\tbackfill-end");
        assertThatCode(() -> validator.validate("transcript.tail", lines)).doesNotThrowAnyException();
    }

    // ──────────────────────── the committed fixtures all validate ────────────────────────

    @Test
    void committedFixturesAllValidate() throws IOException {
        Path manifestPath = FIXTURES_DIR.resolve("manifest.json");
        assumeTrue(
                Files.isRegularFile(manifestPath),
                "manifest not found at " + manifestPath + " — test must run with cwd=server/");

        JsonNode manifest = new ObjectMapper().readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        assertThat(manifest.path("schemaVersion").asInt(-1))
                .as("AC-2 — committed manifest schemaVersion must match the server's supported version")
                .isEqualTo(ReplayFixtureValidator.SCHEMA_VERSION);

        JsonNode scenarios = manifest.path("scenarios");
        assertThat(scenarios.isArray() && !scenarios.isEmpty())
                .as("manifest must declare at least one scenario")
                .isTrue();

        List<String> validated = new ArrayList<>();
        for (JsonNode s : scenarios) {
            String fixture = s.path("fixture").asText("");
            assertThat(fixture).as("each scenario must name a fixture").isNotBlank();
            Path fixturePath = FIXTURES_DIR.resolve(fixture);
            assertThat(Files.isRegularFile(fixturePath))
                    .as("committed fixture must exist: " + fixturePath)
                    .isTrue();
            List<String> lines = Files.readAllLines(fixturePath, StandardCharsets.UTF_8);
            assertThatCode(() -> validator.validate(fixture, lines))
                    .as("committed fixture must pass validation: " + fixture)
                    .doesNotThrowAnyException();
            validated.add(fixture);
        }
        assertThat(validated)
                .as("every committed *.tail fixture validated")
                .contains(
                        "single-question-single.tail",
                        "single-question-multi.tail",
                        "single-question-other.tail",
                        "multi-question.tail",
                        "conversation-transcript.tail");
    }
}
