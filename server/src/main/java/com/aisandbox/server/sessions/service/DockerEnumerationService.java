package com.aisandbox.server.sessions.service;

import com.aisandbox.server.sessions.dto.SessionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enumerates running ai-sandbox sessions by mirroring the behaviour of
 * {@code lib.sh:enumerate_ai_sandbox_sessions} (the Bash kit's
 * source-of-truth), but in pure Java with argv-only {@code docker} calls.
 *
 * <p>Steps:
 * <ol>
 *   <li>{@code docker compose ls --format json} → projects whose
 *       {@code Name} matches {@code ^ai-sandbox-\d+$}.</li>
 *   <li>For each, {@code docker compose -p <Name> ps -q claude-sandbox}
 *       → container id.</li>
 *   <li>{@code docker inspect --format ...} → label.</li>
 *   <li>{@code docker compose -p <Name> exec -T claude-sandbox tmux
 *       display-message -p -t main '#W'} → window title.</li>
 * </ol>
 *
 * <p>Title normalisation: empty / {@code bash} / {@code sh} / {@code claude}
 * → {@code (idle)}; failure → {@code (unavailable)}.
 */
@Service
public class DockerEnumerationService {

    private static final Logger LOG = LoggerFactory.getLogger(DockerEnumerationService.class);
    private static final Pattern PROJECT_RE = Pattern.compile("^ai-sandbox-(\\d+)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProcessExecutor executor;

    public DockerEnumerationService(ProcessExecutor executor) {
        this.executor = executor;
    }

    public List<SessionRecord> enumerate() throws IOException {
        Duration timeout = Duration.ofSeconds(15);
        ProcessExecutor.Result ls = executor.run(List.of("docker", "compose", "ls", "--format", "json"), null, timeout);
        if (ls.exitCode() != 0) {
            LOG.warn("docker compose ls failed (exit={}): {}", ls.exitCode(), ls.stderr());
            return List.of();
        }

        Map<Integer, String> projectNames = new LinkedHashMap<>();
        try {
            JsonNode root = JSON.readTree(ls.stdout().isBlank() ? "[]" : ls.stdout());
            // Compose may emit either an array or NDJSON.
            if (root.isArray()) {
                for (JsonNode n : root) {
                    addProject(projectNames, n);
                }
            } else if (root.isObject()) {
                addProject(projectNames, root);
                // Continue parsing NDJSON tail manually if present.
                try (var p = JSON.getFactory().createParser(ls.stdout())) {
                    while (p.nextToken() != null) {
                        if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                            JsonNode node = JSON.readTree(p);
                            addProject(projectNames, node);
                        }
                    }
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        } catch (IOException ioe) {
            LOG.warn("Cannot parse docker compose ls output: {}", ioe.toString());
            return List.of();
        }

        List<SessionRecord> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : projectNames.entrySet()) {
            int n = e.getKey();
            String project = e.getValue();
            String cid = containerId(project, timeout);
            String label = (cid == null || cid.isEmpty()) ? "" : inspectLabel(cid, timeout);
            String title = (cid == null || cid.isEmpty()) ? "(unavailable)" : tmuxTitle(project, timeout);
            out.add(new SessionRecord(
                    n,
                    label == null ? "" : label,
                    title,
                    cid == null || cid.isEmpty() ? "exited" : "running",
                    0L,
                    0,
                    Instant.EPOCH));
        }
        out.sort((a, b) -> Integer.compare(a.n(), b.n()));
        return out;
    }

    public boolean exists(int n) throws IOException {
        for (SessionRecord r : enumerate()) {
            if (r.n() == n) {
                return true;
            }
        }
        return false;
    }

    private static void addProject(Map<Integer, String> projects, JsonNode node) {
        JsonNode nameNode = node.get("Name");
        if (nameNode == null || !nameNode.isTextual()) {
            return;
        }
        String name = nameNode.asText();
        Matcher m = PROJECT_RE.matcher(name);
        if (m.matches()) {
            projects.put(Integer.parseInt(m.group(1)), name);
        }
    }

    private String containerId(String project, Duration timeout) {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of("docker", "compose", "-p", project, "ps", "-q", "claude-sandbox"), null, timeout);
            if (r.exitCode() != 0) {
                return null;
            }
            String s = r.stdout().strip();
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        } catch (IOException io) {
            LOG.debug("containerId({}): {}", project, io.toString());
            return null;
        }
    }

    private String inspectLabel(String cid, Duration timeout) {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of(
                            "docker",
                            "inspect",
                            "--format",
                            "{{ index .Config.Labels \"com.ai-sandbox.label\" }}",
                            cid),
                    null,
                    timeout);
            if (r.exitCode() != 0) {
                return "";
            }
            String s = r.stdout().strip();
            return s.equals("<no value>") ? "" : s;
        } catch (IOException io) {
            return "";
        }
    }

    private String tmuxTitle(String project, Duration timeout) {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of(
                            "docker",
                            "compose",
                            "-p",
                            project,
                            "exec",
                            "-T",
                            "claude-sandbox",
                            "tmux",
                            "display-message",
                            "-p",
                            "-t",
                            "main",
                            "#W"),
                    null,
                    timeout);
            if (r.exitCode() != 0) {
                return "(unavailable)";
            }
            String raw = r.stdout().strip();
            return switch (raw) {
                case "", "bash", "sh", "claude" -> "(idle)";
                default -> raw;
            };
        } catch (IOException io) {
            return "(unavailable)";
        }
    }
}
