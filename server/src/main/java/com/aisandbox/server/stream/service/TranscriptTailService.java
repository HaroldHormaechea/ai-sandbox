package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private final ProcessExecutor exec;

    public TranscriptTailService(ProcessExecutor exec) {
        this.exec = exec;
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
    public record TailTarget(String socket, String session, String window, String pane) {
        public static TailTarget main() {
            return new TailTarget(null, "main", null, null);
        }

        public boolean hasPane() {
            return window != null && !window.isBlank() && pane != null && !pane.isBlank();
        }
    }

    /**
     * Start the helper for {@code target} in session {@code n} with a bounded
     * {@code backfillLines} window. The returned {@link Tail} is live; callers
     * pump {@link Tail#readLine()} on a dedicated thread and {@link Tail#close()}
     * it on teardown / target switch.
     */
    public Tail start(int n, TailTarget target, int backfillLines) throws IOException {
        List<String> argv = buildArgv(n, target, backfillLines);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        // Discard stderr so a chatty helper can never block on a full stderr pipe
        // (we never parse it); helper-side warnings are best-effort diagnostics.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        LOG.debug("started transcript tail helper for n={} target={} (pid stream)", n, target);
        return new Tail(proc);
    }

    /** Build the {@code docker compose … exec -T claude-sandbox aisandbox-conversation-tail …} argv. */
    static List<String> buildArgv(int n, TailTarget target, int backfillLines) {
        String project = "ai-sandbox-" + n;
        TailTarget t = (target == null) ? TailTarget.main() : target;
        List<String> argv =
                new ArrayList<>(List.of("docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", HELPER));
        argv.add("--session");
        argv.add(t.session() == null ? "main" : t.session());
        if (t.hasPane()) {
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

    /** A live transcript-tail handle. Read {@link #readLine()} until null (EOF), then {@link #close()}. */
    public static final class Tail {
        private final Process process;
        private final BufferedReader reader;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Tail(Process process) {
            this.process = process;
            InputStream in = process.getInputStream();
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
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
            return process.isAlive();
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                process.destroy();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                reader.close();
            } catch (IOException ignored) {
                // best-effort
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
