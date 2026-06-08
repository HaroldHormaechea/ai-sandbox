package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-37 decision D3 — the ONLY structured-input path into the same live {@code
 * claude} session is the PTY (RND §11), so every conversation client frame is
 * translated here into {@code tmux send-keys} into the <b>same</b> tmux session
 * the target resolves to. The structured view never renders the raw PTY, so
 * there is no echo to fight.
 *
 * <p><b>All keystroke mapping is centralized here and version-pinned</b> to the
 * Claude Code build in {@link #PINNED_CLAUDE_VERSION} (proposal RISK 3 — a
 * version bump is a single-file change). Only well-defined cases are mapped
 * (prompt submit, multiline, single/multi-select answer, free-text, ESC /
 * interrupt); everything else relies on long-press→tmux (AC24). The fragile
 * edges are documented in {@code server/CONVERSATION_PROTOCOL.md}.
 *
 * <p>Mapping rationale (pinned conventions):
 * <ul>
 *   <li><b>Submit</b> — {@code send-keys -l -- <line>} for each text segment,
 *       {@code C-j} (LF) between segments to insert a newline WITHOUT submitting
 *       (AC9 multiline), then {@code Enter} (CR) to submit the turn (AC8).</li>
 *   <li><b>Answer (single-select)</b> — reset the option cursor to the top
 *       ({@code Up} ×N), {@code Down} ×k to the chosen index, {@code Enter}
 *       (AC11).</li>
 *   <li><b>Answer (multiSelect)</b> — reset to top, walk every option toggling
 *       {@code Space} on the selected ones, then {@code Enter} (AC11).</li>
 *   <li><b>Free-text "Other"</b> — select the Other option, type the free text,
 *       {@code Enter} (AC10/AC11). The most fragile path; documented as such.</li>
 *   <li><b>Interrupt</b> — {@code Escape} (cancels the active turn).</li>
 * </ul>
 *
 * <p>Mirrors {@link SwarmEnumerationService} / {@link TmuxBridgeService}: pure
 * argv-only {@link ProcessExecutor} calls, no shell, no string interpolation
 * into a command line — so it is unit-testable with a mocked executor and a
 * hostile question label can never smuggle an extra {@code tmux} command.
 */
@Service
public class InputInjectionService {

    private static final Logger LOG = LoggerFactory.getLogger(InputInjectionService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The Claude Code TUI build these keystroke mappings were verified against. */
    public static final String PINNED_CLAUDE_VERSION = "2.1.159";

    /** Upper bound on {@code Up} presses used to deterministically reset the option cursor to the top. */
    private static final int CURSOR_RESET_PRESSES = 20;

    private final ProcessExecutor exec;

    public InputInjectionService(ProcessExecutor exec) {
        this.exec = exec;
    }

    /**
     * A send-keys destination. {@code window}/{@code pane} null → the bare
     * session (main); {@code socket} null → the container default socket.
     */
    public record InjectTarget(String socket, String session, String window, String pane) {
        public static InjectTarget main() {
            return new InjectTarget(null, "main", null, null);
        }

        public boolean hasPane() {
            return window != null && !window.isBlank() && pane != null && !pane.isBlank();
        }

        /** The {@code -t} target spec tmux expects: {@code session[:window.pane]}. */
        String spec() {
            String s = (session == null || session.isBlank()) ? "main" : session;
            return hasPane() ? s + ":" + window + "." + pane : s;
        }
    }

    /**
     * AC8/AC9 — inject the composer text as a prompt + submit into {@code
     * target}'s session. Multiline is delivered with {@code C-j} between lines
     * and a final {@code Enter}. A blank submit is ignored.
     */
    public void injectComposer(int n, InjectTarget target, String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                sendLiteral(n, target, lines[i]);
            }
            if (i < lines.length - 1) {
                sendKeys(n, target, "C-j"); // newline-insert, NOT submit
            }
        }
        sendKeys(n, target, "Enter"); // CR submits the turn
    }

    /**
     * AC11 — translate a structured answer into the option-selection keystrokes.
     *
     * @param optionCount total options on the question (for cursor bounds)
     * @param multiSelect whether multiple options may be toggled
     * @param selections  chosen option indices (0-based, top = 0)
     * @param otherIndex  index of the free-text "Other" option, or {@code -1} if none
     * @param freeText    the "Other" free-text value (used only when {@code otherIndex} is selected)
     */
    public void injectAnswer(
            int n,
            InjectTarget target,
            int optionCount,
            boolean multiSelect,
            List<Integer> selections,
            int otherIndex,
            String freeText)
            throws IOException {
        List<Integer> sel = (selections == null) ? List.of() : selections;
        boolean freeTextChosen = otherIndex >= 0 && sel.contains(otherIndex) && freeText != null && !freeText.isBlank();

        resetCursorToTop(n, target);

        if (multiSelect) {
            // Walk every option top→bottom, toggling Space on the selected ones.
            for (int p = 0; p < Math.max(optionCount, 1); p++) {
                if (sel.contains(p)) {
                    sendKeys(n, target, "Space");
                }
                if (p < optionCount - 1) {
                    sendKeys(n, target, "Down");
                }
            }
            sendKeys(n, target, "Enter");
        } else {
            int target0 = sel.isEmpty() ? 0 : Math.max(0, sel.get(0));
            for (int d = 0; d < target0; d++) {
                sendKeys(n, target, "Down");
            }
            sendKeys(n, target, "Enter");
        }

        if (freeTextChosen) {
            // After selecting the Other option, type the free text + submit. This
            // is the most version-fragile path (proposal RISK 2) — documented.
            sendLiteral(n, target, freeText);
            sendKeys(n, target, "Enter");
        }
    }

    /** ESC — interrupt/cancel the active turn (AC, proposal RISK 2: verify it cancels cleanly). */
    public void interrupt(int n, InjectTarget target) throws IOException {
        sendKeys(n, target, "Escape");
    }

    // ──────────────────────── internals ────────────────────────

    private void resetCursorToTop(int n, InjectTarget target) throws IOException {
        for (int i = 0; i < CURSOR_RESET_PRESSES; i++) {
            sendKeys(n, target, "Up");
        }
    }

    /** {@code tmux send-keys -t <spec> -l -- <literal>} — sends bytes verbatim (no key interpretation). */
    private void sendLiteral(int n, InjectTarget target, String literal) throws IOException {
        List<String> argv = sendKeysBase(n, target);
        argv.add("-l");
        argv.add("--");
        argv.add(literal);
        run(argv);
    }

    /** {@code tmux send-keys -t <spec> <keyName>} — a named key (Enter, C-j, Up, Down, Space, Escape). */
    private void sendKeys(int n, InjectTarget target, String keyName) throws IOException {
        List<String> argv = sendKeysBase(n, target);
        argv.add(keyName);
        run(argv);
    }

    private List<String> sendKeysBase(int n, InjectTarget target) {
        String project = "ai-sandbox-" + n;
        InjectTarget t = (target == null) ? InjectTarget.main() : target;
        List<String> argv =
                new ArrayList<>(List.of("docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", "tmux"));
        if (t.socket() != null && !t.socket().isBlank()) {
            argv.add("-S");
            argv.add(t.socket());
        }
        argv.add("send-keys");
        argv.add("-t");
        argv.add(t.spec());
        return argv;
    }

    private void run(List<String> argv) throws IOException {
        ProcessExecutor.Result r = exec.run(argv, null, TIMEOUT);
        if (r.exitCode() != 0) {
            LOG.warn("send-keys failed (exit={}): {}", r.exitCode(), r.stderr());
            throw new IOException("tmux send-keys failed: " + r.stderr());
        }
    }
}
