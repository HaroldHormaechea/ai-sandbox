package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-37 — owns the long-lived in-container transcript-tail helper
 * ({@code container-bin/aisandbox-conversation-tail}) for one conversation
 * target (decision D2). The helper is spawned via
 * {@code docker compose -p ai-sandbox-N exec -T claude-sandbox
 * aisandbox-conversation-tail …} and its stdout is read <b>incrementally</b>,
 * line by line, exactly like the PTY {@link TmuxBridgeService.Bridge} reads its
 * stdout — because {@link com.aisandbox.server.sessions.service.ProcessExecutor#run}
 * BLOCKS until the child exits and is therefore unusable for a {@code tail -F}-style
 * stream.
 *
 * <p><b>Envelope protocol (helper → server).</b> The helper emits one line per
 * transcript line, tab-delimited: {@code <source>\t<raw-json>} where {@code
 * source} is {@code main} or {@code subagent:<agentId>} (AC17). It interleaves
 * a few control lines using the reserved {@code __ctrl__} source:
 * {@code __ctrl__\tbackfill-start}, {@code __ctrl__\tbackfill-end},
 * {@code __ctrl__\trebaseline} (emitted when the entrypoint restart loop spawns
 * a fresh {@code claude} → new transcript file, AC20), and
 * {@code __ctrl__\tno-transcript} (emitted when the helper cannot resolve any
 * active transcript within a bounded grace — it fails LOUD instead of hanging
 * silently). The server splits on the first tab; the {@code raw-json} half is
 * handed to {@link ConversationEventMapper}.
 *
 * <p>The helper itself (NOT this service) owns: resolving the active {@code
 * claude} PID via the {@code /proc/<pid>/task/<pid>/children} walk, then
 * anchoring the transcript by <b>process identity + cwd-slug</b> (NOT by a held
 * fd — the shipping {@code claude} build opens→appends→closes the transcript per
 * write and holds no {@code .jsonl} fd open between writes): a teammate pane
 * anchors by its {@code --agent-name}/{@code --team-name} ↔ transcript fields, a
 * main/orchestrator pane anchors by a teammate's {@code --parent-session-id}
 * (the orchestrator's transcript stem) or, with no team, the newest
 * {@code agentName}-absent transcript in the slug dir. It also owns the bounded
 * backfill window, restart/rotation re-baselining and stable follow, the
 * {@code subagents/agent-*.jsonl} glob, reading only complete lines, and
 * skipping malformed lines. This service is a thin lifecycle owner: start the
 * process, expose {@link Tail#readLine()}, and {@link Tail#close()} it.
 */
@Service
public class TranscriptTailService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptTailService.class);

    /** The in-container helper binary (installed on PATH by {@code SandboxDockerfile}). */
    static final String HELPER = "aisandbox-conversation-tail";

    /** Reserved {@code source} tag for helper control lines (not a transcript line). */
    public static final String CTRL_SOURCE = "__ctrl__";

    public static final String CTRL_BACKFILL_START = "backfill-start";
    public static final String CTRL_BACKFILL_END = "backfill-end";
    public static final String CTRL_REBASELINE = "rebaseline";

    /**
     * Emitted by the helper when it cannot resolve any active transcript within a
     * bounded grace. The helper fails LOUD with this control line instead of
     * hanging silently (the original UC-37 bug class: the channel stayed open
     * while no backfill / transcript / turn-end ever arrived), so the condition is
     * observable end-to-end and surfaced to the client as a non-fatal error frame.
     */
    public static final String CTRL_NO_TRANSCRIPT = "no-transcript";

    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(8);

    /** UC-41 — one-shot {@code --fetch-detail} timeout (AC5/AC9); a miss/slow helper degrades to empty. */
    private static final Duration DETAIL_TIMEOUT = Duration.ofSeconds(8);

    /**
     * UC-55 — one-shot {@code --parse-pane} timeout. The helper only captures the
     * current pane and parses the focused tab (no transcript resolution), so it is fast;
     * a slow/failed call degrades to an unrecovered tab (the batch stays answerable=false
     * rather than rendering wrong options).
     */
    private static final Duration PARSE_PANE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * UC-41 — control line the helper prints (instead of matched transcript lines)
     * when a {@code --fetch-detail} id cannot be found in the retained transcript
     * window. Surfaced to the client as {@code available=false} (AC9).
     */
    public static final String CTRL_DETAIL_NOT_FOUND = "detail-not-found";

    /**
     * UC-79 — leading control line of a {@code --fetch-page} (load-older) emission.
     * Carries TWO extra tab-delimited fields after the kind: the start line index of
     * the slice actually delivered (the new oldest-line cursor) and {@code atStart}
     * (1 when that start is the very beginning of the transcript):
     * {@code __ctrl__\tpage-meta\t<startIdx>\t<atStart(0|1)>}. The {@code backfill-start}
     * control ALSO now carries a single extra field — the primary window's start line
     * index — used to SEED the cursor (the handler parses it at the control switch).
     */
    public static final String CTRL_PAGE_META = "page-meta";

    /**
     * UC-50 — pane-signal pending-prompt control lines (lock-step with the helper's
     * {@code CTRL_PENDING_QUESTION}/{@code CTRL_PENDING_CLEAR}). Unlike the other
     * control lines these carry a THIRD tab-delimited field after the kind:
     * {@code __ctrl__\tpending-question\t<json>} (the structured prompt payload
     * {@code {kind,questions,plan,key}}) and {@code __ctrl__\tpending-clear\t<key>}
     * (the promptKey that just left the pane). The helper emits these from the
     * VISIBLE pane because claude 2.1.169 never writes the blocking assistant turn
     * to the transcript, so the transcript-tail path cannot see a pending
     * {@code AskUserQuestion}/{@code ExitPlanMode} (UC-50 root cause).
     */
    public static final String CTRL_PENDING_QUESTION = "pending-question";

    public static final String CTRL_PENDING_CLEAR = "pending-clear";

    /**
     * UC-60 — one-shot {@code --list-subagents} timeout. The helper resolves the main
     * transcript and stats its {@code subagents/agent-*.jsonl}, so it is fast; a slow /
     * failed call degrades to no subagent pills (the switcher simply shows none).
     */
    private static final Duration LIST_SUBAGENTS_TIMEOUT = Duration.ofSeconds(8);

    /** Lenient parser for the helper's {@code --list-subagents} NDJSON output. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProcessExecutor exec;

    /**
     * UC-85 — the source that {@link #start} delegates to for opening a live tail.
     * The default bean is {@link DockerTailSource} (today's {@code docker compose exec}
     * helper, {@code @Profile("!replay")}); under the deterministic-gate {@code replay}
     * profile a fixture-file-backed {@code ReplayTailSource} bean is injected instead, so
     * the whole pump → {@link ConversationEventMapper} → WS-emit path stays byte-identical
     * while the transcript SOURCE swaps from the live container to committed fixtures.
     * The one-shot helper modes ({@link #scanPending} etc.) still go through {@link #exec}
     * directly — under replay those degrade to their empty/IDLE fallbacks (no docker), which
     * is harmless for the gate (the device opens a session and tails; it does not depend on
     * switcher badging / detail / paging).
     */
    private final TailSource tailSource;

    @org.springframework.beans.factory.annotation.Autowired
    public TranscriptTailService(ProcessExecutor exec, TailSource tailSource) {
        this.exec = exec;
        this.tailSource = tailSource;
    }

    /**
     * Back-compat constructor for direct unit construction (no Spring): uses the
     * docker-backed {@link DockerTailSource} source, so existing tests that build the
     * service with only a {@link ProcessExecutor} keep the pre-UC-85 behaviour.
     */
    public TranscriptTailService(ProcessExecutor exec) {
        this(exec, new DockerTailSource());
    }

    /**
     * UC-85 — seam for opening a live transcript tail. The default ({@link DockerTailSource})
     * spawns the in-container {@code aisandbox-conversation-tail} helper; the {@code replay}
     * profile supplies a fixture-backed implementation. Either way the returned {@link Tail}
     * is pumped line-by-line by {@link com.aisandbox.server.stream.handler.SessionConversationHandler}.
     */
    public interface TailSource {
        Tail open(int n, TailTarget target, int backfillLines) throws IOException;
    }

    /**
     * UC-60 — one LIVE background subagent of a session's lead, as enumerated by the
     * helper's {@code --list-subagents} mode. {@code id} is the BARE agent id (the
     * {@code <id>} of {@code agent-<id>.jsonl}); the conversation facade forms the
     * {@code subagent:<id>} pill/target id from it. {@code working} carries the same
     * working/idle semantics team-agent pills use (UC-59 {@code deriveWorking} over the
     * subagent transcript tail).
     */
    public record SubagentInfo(String id, String label, boolean working) {}

    /**
     * UC-60 — enumerate the lead's LIVE background subagents for session {@code n} via
     * the helper's one-shot {@code --list-subagents} mode (resolve the main transcript,
     * stat each {@code subagents/agent-*.jsonl}, emit one JSON {@code {id,label,working}}
     * per live one). Never throws: any failure (no transcript, race, helper error,
     * timeout) degrades to an empty list, so the switcher simply shows no subagent pills.
     */
    public List<SubagentInfo> listSubagents(int n) {
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, TailTarget.main(), 1));
            argv.add("--list-subagents");
            ProcessExecutor.Result r = exec.run(argv, null, LIST_SUBAGENTS_TIMEOUT);
            if (r.exitCode() != 0 || r.stdout() == null || r.stdout().isBlank()) {
                return List.of();
            }
            List<SubagentInfo> out = new ArrayList<>();
            for (String line : r.stdout().split("\n", -1)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    JsonNode o = MAPPER.readTree(s);
                    String id = o.path("id").asText(null);
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    String label = o.path("label").asText("");
                    boolean working = o.path("working").asBoolean(false);
                    out.add(new SubagentInfo(id, label, working));
                } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                    // Skip a malformed line — never let one bad record drop the rest.
                    LOG.debug("listSubagents skipped malformed line for n={}: {}", n, jpe.toString());
                }
            }
            return out;
        } catch (IOException io) {
            LOG.debug("listSubagents failed for n={}: {}", n, io.toString());
            return List.of();
        }
    }

    /** UC-37 AC18 — the pending state of a (non-selected) target's transcript, for switcher badging. */
    public enum PendingState {
        /** An {@code AskUserQuestion} is the last interactive event — awaiting an answer. */
        PENDING_QUESTION,
        /** The transcript advanced (new activity) but no question is pending. */
        PENDING_ACTIVITY,
        /** Nothing notable since last seen. */
        IDLE
    }

    /**
     * UC-37 AC18 — one-shot, bounded scan of {@code target}'s transcript to badge
     * a NON-selected switcher tile. Runs the same helper in {@code --scan-pending}
     * mode (it resolves the active transcript, inspects only the tail, and exits —
     * so {@link ProcessExecutor#run} is appropriate, unlike the streaming {@link
     * #start} path). Never throws: any failure (no team, race, helper error)
     * degrades to {@link PendingState#IDLE} so the switcher simply shows no badge.
     */
    public PendingState scanPending(int n, TailTarget target) {
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, target, 1));
            argv.add("--scan-pending");
            ProcessExecutor.Result r = exec.run(argv, null, SCAN_TIMEOUT);
            if (r.exitCode() != 0) {
                return PendingState.IDLE;
            }
            String out = r.stdout() == null ? "" : r.stdout().trim();
            if (out.contains(CTRL_BACKFILL_START)) {
                // defensive — should not happen in scan mode
                return PendingState.IDLE;
            }
            if (out.startsWith("pending-question")) {
                return PendingState.PENDING_QUESTION;
            }
            if (out.startsWith("pending-activity")) {
                return PendingState.PENDING_ACTIVITY;
            }
            return PendingState.IDLE;
        } catch (IOException io) {
            LOG.debug("scanPending failed for n={} target={}: {}", n, target, io.toString());
            return PendingState.IDLE;
        }
    }

    /**
     * UC-97 — one-shot, bounded re-derive of {@code target}'s CURRENT pane pending-state, for
     * the client's warm-attach {@code resync-pending} request. Runs the helper in
     * {@code --resync-pending} mode: it captures the VISIBLE pane and, if it shows a blocking
     * {@code AskUserQuestion}/{@code ExitPlanMode}, emits the SAME full
     * {@code {kind,questions,plan,key}} payload the streaming path emits (else a bare
     * pending-clear), then exits — so {@link ProcessExecutor#run} is appropriate, like {@link
     * #scanPending}. Derives from the PANE, not the transcript (the transcript is blind to a
     * live blocking ask — UC-49/UC-50). Returns the raw control payload with the
     * {@code __ctrl__} envelope prefix stripped ({@code "pending-question<TAB><json>"} or
     * {@code "pending-clear"}), or {@code null} on any miss/failure (the caller then re-emits
     * nothing, retaining the client's prior state). Never throws.
     */
    public String resyncPending(int n, TailTarget target) {
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, target, 1));
            argv.add("--resync-pending");
            ProcessExecutor.Result r = exec.run(argv, null, SCAN_TIMEOUT);
            if (r.exitCode() != 0 || r.stdout() == null) {
                return null;
            }
            String out = r.stdout().trim();
            if (out.isEmpty() || out.contains(CTRL_BACKFILL_START)) {
                return null; // empty (capture/parse miss) or a defensive non-scan artifact
            }
            String prefix = CTRL_SOURCE + "\t";
            if (out.startsWith(prefix)) {
                out = out.substring(prefix.length());
            }
            // Only ever a pending-question / pending-clear control line; ignore anything else.
            if (out.startsWith(CTRL_PENDING_QUESTION) || out.startsWith(CTRL_PENDING_CLEAR)) {
                return out;
            }
            return null;
        } catch (IOException io) {
            LOG.debug("resyncPending failed for n={} target={}: {}", n, target, io.toString());
            return null;
        }
    }

    /**
     * UC-41 (AC5/AC9) — one-shot, bounded re-read of {@code target}'s transcript for
     * the raw {@code tool_use} + {@code tool_result} lines of a single {@code toolUseId}.
     * Runs the helper in {@code --fetch-detail} mode (resolve transcript once, scan main +
     * subagent files, print the matched {@code <source>\t<raw>} lines), then exits — so
     * {@link ProcessExecutor#run} is appropriate, like {@link #scanPending}. Returns the
     * matched envelope lines (handed to {@code ConversationEventMapper#renderDetail});
     * an EMPTY list on a miss, the {@code detail-not-found} control line, a non-zero exit,
     * a timeout, or any I/O error — the caller maps an empty result to {@code available=false}.
     * Never throws.
     */
    public List<String> fetchDetailLines(int n, TailTarget target, String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) {
            return List.of();
        }
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, target, 1));
            argv.add("--fetch-detail");
            argv.add("--tool-use-id");
            argv.add(toolUseId);
            ProcessExecutor.Result r = exec.run(argv, null, DETAIL_TIMEOUT);
            if (r.exitCode() != 0 || r.stdout() == null || r.stdout().isBlank()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : r.stdout().split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                // The helper emits `__ctrl__\tdetail-not-found` on a miss → no matched lines.
                if (line.startsWith(CTRL_SOURCE) && line.contains(CTRL_DETAIL_NOT_FOUND)) {
                    return List.of();
                }
                lines.add(line);
            }
            return lines;
        } catch (IOException io) {
            LOG.debug("fetchDetailLines failed for n={} target={} id={}: {}", n, target, toolUseId, io.toString());
            return List.of();
        }
    }

    /**
     * UC-79 (AC2/AC6) — one-shot, bounded re-read of {@code target}'s PRIMARY transcript
     * for the OLDER page ending just below {@code beforeLine}. Runs the helper in
     * {@code --fetch-page --before-line <i> --count <n>} mode (resolve once, read the
     * complete-line slice, print a leading {@code page-meta} control then the older
     * {@code <source>\t<raw>} lines), then exits — so {@link ProcessExecutor#run} fits,
     * exactly like {@link #fetchDetailLines}. Returns a {@link PageLines} carrying the
     * envelope lines plus the parsed new oldest-line cursor and {@code atStart} flag.
     * A {@code beforeLine <= 0}, an unresolvable transcript, a missing page-meta, a
     * non-zero exit, a timeout, or any I/O error degrades to an EMPTY page pinned at the
     * given {@code beforeLine} with {@code atStart=true} — so the cursor never silently
     * advances and the client stops paging (AC4). Never throws.
     */
    public PageLines fetchPageLines(int n, TailTarget target, int beforeLine, int pageSize) {
        if (beforeLine <= 0) {
            return new PageLines(List.of(), Math.max(0, beforeLine), true);
        }
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, target, 1));
            argv.add("--fetch-page");
            argv.add("--before-line");
            argv.add(Integer.toString(beforeLine));
            argv.add("--count");
            argv.add(Integer.toString(Math.max(1, pageSize)));
            ProcessExecutor.Result r = exec.run(argv, null, DETAIL_TIMEOUT);
            if (r.exitCode() != 0 || r.stdout() == null) {
                return new PageLines(List.of(), beforeLine, true);
            }
            List<String> lines = new ArrayList<>();
            int newOldest = beforeLine;
            boolean atStart = true;
            boolean sawMeta = false;
            for (String line : r.stdout().split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith(CTRL_SOURCE)) {
                    // Leading control: __ctrl__\tpage-meta\t<start>\t<atStart(0|1)>.
                    String[] parts = line.split("\t", -1);
                    if (parts.length >= 4 && CTRL_PAGE_META.equals(parts[1].trim())) {
                        sawMeta = true;
                        try {
                            newOldest = Integer.parseInt(parts[2].trim());
                        } catch (NumberFormatException nfe) {
                            newOldest = beforeLine;
                        }
                        atStart = "1".equals(parts[3].trim());
                    }
                    continue; // never hand a control line to the mapper
                }
                lines.add(line);
            }
            if (!sawMeta) {
                // No page-meta → treat as nothing loaded so the cursor never silently advances.
                return new PageLines(List.of(), beforeLine, true);
            }
            return new PageLines(lines, newOldest, atStart);
        } catch (IOException io) {
            LOG.debug("fetchPageLines failed for n={} target={} before={}: {}", n, target, beforeLine, io.toString());
            return new PageLines(List.of(), beforeLine, true);
        }
    }

    /**
     * UC-79 — result of a {@link #fetchPageLines} call: the older transcript envelope
     * {@code lines} ({@code <source>\t<raw>}, control lines stripped), the new oldest-line
     * {@code cursor} (the start index of the slice actually delivered — the handler stores
     * it as the connection's cursor), and {@code atStart} (true once the transcript start
     * is reached, so no further paging is attempted).
     */
    public record PageLines(List<String> lines, int cursor, boolean atStart) {}

    /**
     * UC-55 — one-shot, READ-ONLY capture+parse of the CURRENTLY-FOCUSED wizard tab of
     * {@code target}'s pane (the {@code --parse-pane} helper mode). The helper captures
     * the visible pane (no keystroke — the SERVER owns stepping via {@link
     * InputInjectionService#stepWizardForward}) and prints the focused tab's parsed
     * content as ONE JSON line ({@code {question,multiSelect,options:[...]}}), or {@code
     * {}} when the pane shows no answerable wizard. Returns that raw JSON string for the
     * caller ({@link com.aisandbox.server.stream.facade.ConversationFacade}) to map via
     * {@link ConversationEventMapper#parseFocusedTab}; returns {@code ""} on a non-zero
     * exit / timeout / I/O error (→ an unrecovered tab). Never throws.
     */
    public String captureFocusedTabJson(int n, TailTarget target) {
        try {
            List<String> argv = new ArrayList<>(buildArgv(n, target, 1));
            argv.add("--parse-pane");
            ProcessExecutor.Result r = exec.run(argv, null, PARSE_PANE_TIMEOUT);
            if (r.exitCode() != 0 || r.stdout() == null) {
                return "";
            }
            return r.stdout().trim();
        } catch (IOException io) {
            LOG.debug("captureFocusedTabJson failed for n={} target={}: {}", n, target, io.toString());
            return "";
        }
    }

    /**
     * A tail destination — the tmux coordinates the helper uses to resolve the
     * target {@code claude} PID. {@code window}/{@code pane} are null for the
     * main session (the orchestrator pane); a teammate tile carries them.
     * {@code socket} is null for the container default socket (the current
     * layout) or an absolute path for a legacy {@code claude-swarm-*} socket.
     */
    public record TailTarget(String socket, String session, String window, String pane, String subagentId) {

        /** Back-compat 4-arg form (pane/main targets) — no subagent id. */
        public TailTarget(String socket, String session, String window, String pane) {
            this(socket, session, window, pane, null);
        }

        public static TailTarget main() {
            return new TailTarget(null, "main", null, null, null);
        }

        /**
         * UC-60 — a subagent tail target: stream ONLY the lead's
         * {@code subagents/agent-<id>.jsonl} (the helper's {@code --subagent <id>}
         * mode). Anchored to the {@code main} session (the subagent dir is derived
         * from the main transcript), with no pane coordinates.
         */
        public static TailTarget subagent(String id) {
            return new TailTarget(null, "main", null, null, id);
        }

        public boolean hasPane() {
            return window != null && !window.isBlank() && pane != null && !pane.isBlank();
        }

        public boolean isSubagent() {
            return subagentId != null && !subagentId.isBlank();
        }
    }

    /**
     * Start the helper for {@code target} in session {@code n} with a bounded
     * {@code backfillLines} window. The returned {@link Tail} is live; callers
     * pump {@link Tail#readLine()} on a dedicated thread and {@link Tail#close()}
     * it on teardown / target switch.
     */
    public Tail start(int n, TailTarget target, int backfillLines) throws IOException {
        return tailSource.open(n, target, backfillLines);
    }

    /** Build the {@code docker compose … exec -T claude-sandbox aisandbox-conversation-tail …} argv. */
    static List<String> buildArgv(int n, TailTarget target, int backfillLines) {
        String project = "ai-sandbox-" + n;
        TailTarget t = (target == null) ? TailTarget.main() : target;
        List<String> argv =
                new ArrayList<>(List.of("docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", HELPER));
        argv.add("--session");
        argv.add(t.session() == null ? "main" : t.session());
        if (t.isSubagent()) {
            // UC-60 — a subagent stream tails ONLY the lead's agent-<id>.jsonl; it is
            // anchored to the main session and carries NO pane flags (the subagent dir
            // is derived from the main transcript, not a tmux pane).
            argv.add("--subagent");
            argv.add(t.subagentId());
        } else if (t.hasPane()) {
            argv.add("--window");
            argv.add(t.window());
            argv.add("--pane");
            argv.add(t.pane());
        }
        if (t.socket() != null && !t.socket().isBlank()) {
            argv.add("--socket");
            argv.add(t.socket());
        }
        argv.add("--backfill");
        argv.add(Integer.toString(Math.max(1, backfillLines)));
        return argv;
    }

    /**
     * A live transcript-tail handle. Read {@link #readLine()} until null (EOF), then
     * {@link #close()}. Backed either by an OS {@link Process} (the docker helper — the
     * production path) or, under the {@code replay} profile, by a bare {@link Reader} over
     * recorded fixture envelope lines with no process at all ({@code process == null}).
     */
    public static final class Tail {
        /** The helper process, or {@code null} for a fixture-backed (replay) tail. */
        private final Process process;

        private final BufferedReader reader;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Tail(Process process) {
            this.process = process;
            InputStream in = process.getInputStream();
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        /**
         * UC-85 — a process-less, fixture-backed tail (the {@code replay} profile). The
         * {@code reader} replays a recorded {@code <source>\t<raw-json>} / {@code __ctrl__\t…}
         * envelope stream; closing the {@code Tail} closes the reader (which the replay
         * reader uses to unblock any answer-gate wait), and {@link #isAlive()} tracks the
         * closed flag rather than a process liveness probe.
         */
        public Tail(Reader reader) {
            this.process = null;
            this.reader = (reader instanceof BufferedReader br) ? br : new BufferedReader(reader);
        }

        /**
         * Block until the next envelope line is available; returns it (without the
         * trailing newline), or {@code null} at EOF (helper exited / stream
         * closed). Each line is {@code <source>\t<raw>} — split on the first tab.
         */
        public String readLine() throws IOException {
            return reader.readLine();
        }

        public boolean isAlive() {
            // A fixture-backed (replay) tail has no process — it is "alive" until closed.
            return process == null ? !closed.get() : process.isAlive();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (process != null) {
                try {
                    process.destroy();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
            // Closing the reader is what unblocks a replay tail parked on its answer-gate
            // (the replay reader closes the gate on stream close), and releases the docker
            // helper's stdout pipe in the production path.
            try {
                reader.close();
            } catch (IOException ignored) {
                // best-effort
            }
            if (process == null) {
                return; // fixture-backed tail — no OS process to reap
            }
            // The `docker compose exec` parent dying does not always reap the
            // in-container helper; the helper self-exits when its stdout pipe
            // breaks (write to a closed pipe → SIGPIPE), so no extra kill is
            // needed beyond destroying the local exec process.
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
