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
 * Enumerates ai-sandbox sessions by mirroring the behaviour of
 * {@code lib.sh:enumerate_ai_sandbox_sessions} (the Bash kit's
 * source-of-truth), but in pure Java with argv-only {@code docker} calls.
 *
 * <h2>UC04 AC37 — stopped containers surface too</h2>
 *
 * The original implementation filtered to running projects only. UC04
 * extends the enumeration so the Android sessions list can show
 * {@code stopped} entries (the "Stopped" filter chip in UC04-2):
 *
 * <ol>
 *   <li>{@code docker compose ls --all --format json} (the {@code --all}
 *       flag — without it, stopped projects are invisible) → projects
 *       whose {@code Name} matches {@code ^ai-sandbox-\d+$}.</li>
 *   <li>{@code docker compose -p <Name> ps -q --all claude-sandbox} →
 *       container id even when the container is exited.</li>
 *   <li><em>Single combined</em> {@code docker inspect} with
 *       {@code --format='{{label}}|{{.State.Status}}|{{.State.Running}}'}
 *       parsed on {@code |} — one process per session instead of one
 *       per attribute.</li>
 *   <li>For {@code running} sessions only:
 *       {@code docker compose -p <Name> exec -T claude-sandbox tmux
 *       display-message ...} → window title. Skipped for {@code
 *       starting}/{@code stopped} since exec on a non-running container
 *       errors and inflates enumeration latency.</li>
 * </ol>
 *
 * <p>State mapping per UC04 § B4:
 * <ul>
 *   <li>{@code running}                  → {@code "running"}</li>
 *   <li>{@code created} / {@code restarting} → {@code "starting"}</li>
 *   <li>{@code exited} / {@code dead} / {@code paused} → {@code "stopped"}</li>
 *   <li>anything else / no container     → {@code "stopped"}</li>
 * </ul>
 *
 * <p>Title normalisation: empty / {@code bash} / {@code sh} / {@code claude}
 * → {@code (idle)}; failure or non-running → {@code (unavailable)}.
 */
@Service
public class DockerEnumerationService {

    private static final Logger LOG = LoggerFactory.getLogger(DockerEnumerationService.class);
    private static final Pattern PROJECT_RE = Pattern.compile("^ai-sandbox-(\\d+)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProcessExecutor executor;

    /**
     * UC-15 sticky flag for {@code docker compose ls --all} support on this
     * runner's docker-compose binary.
     *
     * <ul>
     *   <li>{@code null} — unknown; the next {@link #enumerate()} probes the
     *       modern argv and may flip the flag.</li>
     *   <li>{@link Boolean#TRUE} — sticky supported; the modern argv
     *       ({@code docker compose ls --all} / {@code docker compose -p … ps
     *       --all}) is used everywhere.</li>
     *   <li>{@link Boolean#FALSE} — sticky unsupported; enumerate falls back
     *       to {@code docker ps -a --filter label=com.docker.compose.project}
     *       and {@link #containerId(String, Duration)} falls back to
     *       {@code docker ps -a -q --filter label=…}.</li>
     * </ul>
     *
     * <p>Volatile because Spring shares the singleton across request threads;
     * the write happens at most once per binary capability transition, the
     * reads are lock-free.
     */
    private volatile Boolean composeListAllSupported;

    public DockerEnumerationService(ProcessExecutor executor) {
        this.executor = executor;
    }

    public List<SessionRecord> enumerate() throws IOException {
        Duration timeout = Duration.ofSeconds(15);

        Map<Integer, String> projectNames = new LinkedHashMap<>();
        Boolean flag = composeListAllSupported;

        if (Boolean.FALSE.equals(flag)) {
            // UC-15: this runner's docker-compose has already been observed to
            // reject --all. Skip the probe and go straight to the fallback.
            if (!enumerateViaDockerPs(projectNames, timeout)) {
                return List.of();
            }
        } else {
            // null (unknown — probe) or TRUE (sticky supported): modern argv.
            // LC_ALL=C keeps the stderr in English so the --all substring
            // check below stays meaningful regardless of the runner's locale.
            // UC04 § B4 — --all so stopped projects show up too.
            ProcessExecutor.Result ls = executor.run(
                    List.of("docker", "compose", "ls", "--all", "--format", "json"),
                    null,
                    Map.of("LC_ALL", "C"),
                    timeout);
            if (ls.exitCode() == 0) {
                composeListAllSupported = Boolean.TRUE;
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
            } else if (isUnsupportedAllFlag(ls.exitCode(), ls.stderr())) {
                // UC-15: docker compose binary on this runner doesn't know --all.
                // Latch the sticky flag so subsequent enumerate() calls skip the
                // probe, then take the fallback once for THIS call.
                composeListAllSupported = Boolean.FALSE;
                LOG.info(
                        "docker compose ls rejected --all (exit={}); falling back to docker ps. stderr={}",
                        ls.exitCode(),
                        ls.stderr());
                if (!enumerateViaDockerPs(projectNames, timeout)) {
                    return List.of();
                }
            } else {
                // Some other failure (timeout, docker daemon down, transient error).
                // Leave the flag at its current value (null or TRUE) so the next
                // enumerate re-probes.
                LOG.warn("docker compose ls failed (exit={}): {}", ls.exitCode(), ls.stderr());
                return List.of();
            }
        }

        List<SessionRecord> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : projectNames.entrySet()) {
            int n = e.getKey();
            String project = e.getValue();
            String cid = containerId(project, timeout);
            if (cid == null || cid.isEmpty()) {
                // Project enumerated but no container — likely transient
                // mid-compose-up. Mark stopped so the Android UI shows it
                // under the Stopped chip rather than silently dropping it.
                out.add(new SessionRecord(n, "", "(unavailable)", "stopped", 0L, 0, Instant.EPOCH));
                continue;
            }
            InspectResult inspect = inspectCombined(cid, timeout);
            String state = mapState(inspect.status());
            String title = "running".equals(state) ? tmuxTitle(project, timeout) : "(unavailable)";
            out.add(new SessionRecord(
                    n, inspect.label() == null ? "" : inspect.label(), title, state, 0L, 0, Instant.EPOCH));
        }
        out.sort((a, b) -> Integer.compare(a.n(), b.n()));
        return out;
    }

    /**
     * UC-15: detect the literal "docker compose unknown flag: --all" error
     * shape across the docker-compose binaries we have seen reject it.
     * docker-compose v2 returns exit=125; older v1 forks return exit=64.
     * The stderr substring check is case-sensitive — LC_ALL=C on the
     * invocation keeps it stable across runner locales.
     */
    private static boolean isUnsupportedAllFlag(int exitCode, String stderr) {
        return (exitCode == 125 || exitCode == 64) && stderr != null && stderr.contains("--all");
    }

    /**
     * UC-15 fallback enumeration when {@code docker compose ls --all} is
     * not understood by the runner's docker-compose binary. Lists every
     * container (running or stopped) that carries the
     * {@code com.docker.compose.project} label, dedupes by project name,
     * and filters by {@link #PROJECT_RE} just like the modern path.
     *
     * @return {@code true} on success (even if the result set is empty);
     *     {@code false} if the {@code docker ps} call itself failed (the
     *     caller turns this into an empty enumeration without flipping
     *     the sticky flag).
     */
    private boolean enumerateViaDockerPs(Map<Integer, String> projectNames, Duration timeout) throws IOException {
        ProcessExecutor.Result ps = executor.run(
                List.of(
                        "docker",
                        "ps",
                        "-a",
                        "--format",
                        "json",
                        "--filter",
                        "label=com.docker.compose.project"),
                null,
                Map.of("LC_ALL", "C"),
                timeout);
        if (ps.exitCode() != 0) {
            LOG.warn("docker ps fallback failed (exit={}): {}", ps.exitCode(), ps.stderr());
            return false;
        }
        String stdout = ps.stdout();
        if (stdout == null || stdout.isBlank()) {
            return true;
        }
        // docker ps --format json emits NDJSON (one object per line). Reuse
        // the same streaming parser pattern as the compose ls NDJSON tail.
        try (var p = JSON.getFactory().createParser(stdout)) {
            while (p.nextToken() != null) {
                if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                    JsonNode node = JSON.readTree(p);
                    String project = extractComposeProjectLabel(node.get("Labels"));
                    if (project == null) {
                        continue;
                    }
                    Matcher m = PROJECT_RE.matcher(project);
                    if (m.matches()) {
                        projectNames.putIfAbsent(Integer.parseInt(m.group(1)), project);
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort — partial NDJSON is still useful.
        }
        return true;
    }

    /**
     * Pull the {@code com.docker.compose.project} value from a
     * {@code docker ps --format json} {@code Labels} field. Older docker
     * binaries serialise labels as a comma-joined {@code "k1=v1,k2=v2"}
     * string; newer ones emit a JSON object. Handle both shapes.
     */
    private static String extractComposeProjectLabel(JsonNode labels) {
        if (labels == null) {
            return null;
        }
        if (labels.isObject()) {
            JsonNode v = labels.get("com.docker.compose.project");
            return v == null || !v.isTextual() ? null : v.asText();
        }
        if (labels.isTextual()) {
            for (String kv : labels.asText().split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "com.docker.compose.project".equals(kv.substring(0, eq).trim())) {
                    return kv.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Map raw {@code docker inspect .State.Status} to the UC04 AC37
     * three-state model {@code running | starting | stopped}. Anything
     * unknown becomes {@code stopped} — defensive: if Docker introduces
     * a new state we don't want the Android UI to wedge on an unmapped
     * value.
     */
    static String mapState(String dockerStatus) {
        if (dockerStatus == null || dockerStatus.isEmpty()) {
            return "stopped";
        }
        return switch (dockerStatus) {
            case "running" -> "running";
            case "created", "restarting" -> "starting";
            case "exited", "dead", "paused" -> "stopped";
            default -> "stopped";
        };
    }

    /** Tuple returned by the single combined inspect call. */
    record InspectResult(String label, String status, boolean running) {}

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
            List<String> argv;
            if (Boolean.FALSE.equals(composeListAllSupported)) {
                // UC-15 fallback: this runner's docker-compose doesn't speak
                // --all, so ask docker ps directly for the container that
                // carries the matching compose labels. The compose project
                // label was authoritative when enumerate() picked this
                // project up; using the same label here keeps the two
                // halves of enumeration consistent.
                argv = List.of(
                        "docker",
                        "ps",
                        "-a",
                        "-q",
                        "--filter",
                        "label=com.docker.compose.project=" + project,
                        "--filter",
                        "label=com.docker.compose.service=claude-sandbox");
            } else {
                // TRUE or null (unknown) — modern argv. UC04 § B4 — --all so
                // the container id of a stopped/exited container is also
                // returned (default ps -q hides them).
                argv = List.of("docker", "compose", "-p", project, "ps", "-q", "--all", "claude-sandbox");
            }
            ProcessExecutor.Result r = executor.run(argv, null, Map.of("LC_ALL", "C"), timeout);
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

    /**
     * UC04 § B4 — single combined inspect call that returns
     * {@code label|status|running} on one line. Replaces the prior two
     * round-trips (one for label, one for tmux title) where the title
     * call is now skipped for non-running containers.
     */
    InspectResult inspectCombined(String cid, Duration timeout) {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of(
                            "docker",
                            "inspect",
                            "--format",
                            // Two separate {{...}} runs separated by literal |, exactly
                            // as documented in the proposal.
                            "{{index .Config.Labels \"com.ai-sandbox.label\"}}|{{.State.Status}}|{{.State.Running}}",
                            cid),
                    null,
                    timeout);
            if (r.exitCode() != 0) {
                return new InspectResult("", "", false);
            }
            String raw = r.stdout().strip();
            String[] parts = raw.split("\\|", -1);
            String label = parts.length > 0 ? parts[0] : "";
            if (label.equals("<no value>")) {
                label = "";
            }
            String status = parts.length > 1 ? parts[1] : "";
            boolean running = parts.length > 2 && Boolean.parseBoolean(parts[2]);
            return new InspectResult(label, status, running);
        } catch (IOException io) {
            LOG.debug("inspectCombined({}): {}", cid, io.toString());
            return new InspectResult("", "", false);
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
