package com.aisandbox.server.stream.replay;

import com.aisandbox.server.config.SpecialSessions;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — loads the deterministic-gate fixture catalog once at boot (active only under the
 * {@code replay} profile) and is the single source of truth for: the synthetic session list
 * the device sees, and the recorded envelope stream each scenario replays.
 *
 * <p>It reads {@code <dir>/manifest.json}, checks its {@code schemaVersion} against {@link
 * ReplayFixtureValidator#SCHEMA_VERSION}, loads each scenario's {@code *.tail} fixture, and
 * validates every fixture via {@link ReplayFixtureValidator}. Any problem (missing manifest,
 * schema drift, missing/empty/malformed fixture) throws from the constructor and aborts
 * startup — there is no "degrade and run anyway" path, because a silently-skipped fixture
 * would make a green gate meaningless (AC-2).
 *
 * <p>{@code manifest.json} shape (schemaVersion 1):
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "scenarios": [
 *     { "n": 1, "target": "single-question-single", "title": "Single AskUserQuestion", "fixture": "single-question-single.tail" }
 *   ]
 * }
 * }</pre>
 */
@Component
@Profile("replay")
public class ReplaySessionCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ReplaySessionCatalog.class);

    /** One replay scenario = one synthetic session + its recorded envelope fixture. */
    public record Scenario(int n, String target, String title, String fixture, List<String> lines) {}

    private final List<Scenario> scenarios;
    private final Map<Integer, Scenario> byN;

    public ReplaySessionCatalog(ReplayProperties props, ReplayFixtureValidator validator) {
        Path dir = Path.of(props.dir());
        Path manifestPath = dir.resolve("manifest.json");
        ObjectMapper json = new ObjectMapper();
        JsonNode manifest;
        try {
            manifest = json.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        } catch (IOException io) {
            throw new IllegalStateException(
                    "replay: cannot read fixture manifest at " + manifestPath.toAbsolutePath() + ": " + io.getMessage(),
                    io);
        }
        if (manifest == null || !manifest.isObject()) {
            throw new IllegalStateException(
                    "replay: manifest " + manifestPath.toAbsolutePath() + " is not a JSON object");
        }
        int schemaVersion = manifest.path("schemaVersion").asInt(-1);
        if (schemaVersion != ReplayFixtureValidator.SCHEMA_VERSION) {
            throw new IllegalStateException("replay: manifest schemaVersion " + schemaVersion + " != supported "
                    + ReplayFixtureValidator.SCHEMA_VERSION + " (fixtures are stale — re-capture; AC-2)");
        }
        JsonNode arr = manifest.path("scenarios");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException(
                    "replay: manifest " + manifestPath.toAbsolutePath() + " has no scenarios[]");
        }
        Map<Integer, Scenario> map = new LinkedHashMap<>();
        List<Scenario> list = new ArrayList<>();
        for (JsonNode s : arr) {
            int n = s.path("n").asInt(-1);
            String target = s.path("target").asText("");
            String title = s.path("title").asText("");
            String fixture = s.path("fixture").asText("");
            if (n <= 0 || fixture.isBlank()) {
                throw new IllegalStateException(
                        "replay: manifest scenario must have a positive 'n' and a 'fixture': " + s);
            }
            if (n == SpecialSessions.SERVER_SSH_N) {
                throw new IllegalStateException(
                        "replay: scenario 'n' " + n + " collides with the reserved server-ssh session number");
            }
            if (map.containsKey(n)) {
                throw new IllegalStateException("replay: duplicate scenario session number n=" + n);
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(dir.resolve(fixture), StandardCharsets.UTF_8);
            } catch (IOException io) {
                throw new IllegalStateException(
                        "replay: cannot read fixture '" + fixture + "' for scenario n=" + n + ": " + io.getMessage(),
                        io);
            }
            validator.validate(fixture, lines);
            Scenario scenario = new Scenario(n, target, title, fixture, List.copyOf(lines));
            map.put(n, scenario);
            list.add(scenario);
        }
        this.scenarios = List.copyOf(list);
        this.byN = Map.copyOf(map);
        LOG.info(
                "replay catalog loaded {} scenario(s) from {}: {}",
                scenarios.size(),
                dir.toAbsolutePath(),
                scenarios.stream().map(Scenario::target).toList());
    }

    public List<Scenario> scenarios() {
        return scenarios;
    }

    public Optional<Scenario> byN(int n) {
        return Optional.ofNullable(byN.get(n));
    }

    /**
     * Synthetic {@link SessionRecord}s for {@code GET /v1/sessions} and {@code authorizeOpen}.
     * Every scenario is reported as a {@code running} {@code claude} session so its card
     * renders and the conversation channel opens.
     */
    public List<SessionRecord> syntheticRecords() {
        List<SessionRecord> out = new ArrayList<>(scenarios.size());
        for (Scenario s : scenarios) {
            out.add(new SessionRecord(
                    s.n(),
                    s.title(),
                    s.title(),
                    "running",
                    0L,
                    0,
                    Instant.EPOCH,
                    s.title(),
                    false,
                    false,
                    SpecialSessions.TYPE_CLAUDE,
                    null));
        }
        return out;
    }
}
