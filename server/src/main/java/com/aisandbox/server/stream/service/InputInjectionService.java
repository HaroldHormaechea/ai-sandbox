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
 *       {@code Space} on the selected ones, then advance. With an "Other" answer
 *       the free text is composed in (UC-44): type the literal, {@code Enter} to
 *       commit it as a custom option, {@code Space} to select it, then
 *       {@code Tab}+{@code Enter} to activate the in-pane Next/Submit.</li>
 *   <li><b>Free-text "Other"</b> — single-select: walk to the Other row, type the
 *       free text, {@code Enter} (AC10/AC11). multiSelect: see above. The most
 *       fragile path; documented in {@code CONVERSATION_PROTOCOL.md}.</li>
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
    public static final String PINNED_CLAUDE_VERSION = "2.1.169";

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
     * UC-43 — one question's resolved selection metadata for
     * {@link #injectAnswerBatch}. Mirrors the per-question parameters of
     * {@link #injectAnswer}; the conversation handler derives each spec from the
     * cached {@code AskUserQuestion}'s {@code questions[questionIndex]} plus the
     * client's {@code answer-batch} item.
     */
    public record BatchAnswerSpec(
            int optionCount, boolean multiSelect, List<Integer> selections, int otherIndex, String freeText) {}

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
     * AC11 — translate a single-question structured answer into the
     * option-selection keystrokes for the lone {@code AskUserQuestion} sheet.
     *
     * <p>Resets the option cursor to the top, then delegates to the shared
     * per-question keystroke helper ({@link #selectQuestionAnswer}) with
     * {@code Enter} as the commit key — for a single-question sheet there is no
     * tab to advance to, so {@code Enter} both selects/commits and submits.
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
        resetCursorToTop(n, target);
        selectQuestionAnswer(n, target, optionCount, multiSelect, selections, otherIndex, freeText, "Enter");
    }

    /**
     * UC-43 — inject answers to a <b>multi-question</b> {@code AskUserQuestion}
     * (N&gt;1) as ONE keystroke sequence that walks the live TUI's tabbed wizard.
     *
     * <p><b>Verified live against Claude Code {@value #PINNED_CLAUDE_VERSION}</b>
     * (UC-43 hard gate — the runtime interaction model is unknowable from source
     * and was driven through a real multi-question ask in tmux):
     * <ul>
     *   <li>The sheet opens at the top of the first question's option list, and
     *       the option cursor <b>auto-resets to the top</b> of each subsequent
     *       question when the wizard advances — so NO per-question
     *       {@code resetCursorToTop} is used here (and indeed must not be: in the
     *       wizard, {@code Up}/{@code Down} <b>wrap around</b> the option ring, so a
     *       blind {@code Up}×N reset is not deterministic).</li>
     *   <li><b>single-select</b> question: walk {@code Down} to the option, then
     *       {@code Enter} — which selects it AND advances to the next tab.</li>
     *   <li><b>multiSelect</b> question (no "Other"): walk the options toggling
     *       {@code Space} in place (the cursor stays put), then {@code Tab} to
     *       advance to the next tab — {@code Enter} on a multiSelect option only
     *       toggles it, it does NOT advance.</li>
     *   <li><b>single-select free-text "Other"</b>: walk to the "Type something"
     *       row, type the text INLINE, then {@code Enter} — typing an EMPTY
     *       "Type something" and pressing {@code Enter} DECLINES the whole ask, so
     *       the text is always typed before the {@code Enter}.</li>
     *   <li><b>multiSelect free-text "Other"</b> (UC-44, verified live against
     *       2.1.169): at the "Type something" row type the literal, {@code Enter}
     *       (commits the typed text as a custom option — the on-type checkmark is
     *       only a non-committed preview that is otherwise DROPPED), {@code Space}
     *       (selects it). Because the cursor then sits on that row, {@code Tab}
     *       only FOCUSES the in-pane Next/Submit button, so {@code Enter} ACTIVATES
     *       it to advance. (Plain multiSelect leaves the cursor on a real option,
     *       where {@code Tab} advances directly — hence the asymmetry.)</li>
     *   <li>After the last question advances, the wizard lands on the "Submit" tab
     *       ("Review your answers"); a final {@code Enter} submits the whole batch.</li>
     * </ul>
     *
     * @param answers one spec per question, already sorted by {@code questionIndex} by the caller
     */
    public void injectAnswerBatch(int n, InjectTarget target, List<BatchAnswerSpec> answers) throws IOException {
        if (answers == null || answers.isEmpty()) {
            return;
        }
        for (BatchAnswerSpec a : answers) {
            // multiSelect advances with Tab; single-select advances with Enter (see method javadoc).
            selectQuestionAnswer(
                    n, target, a.optionCount(), a.multiSelect(), a.selections(), a.otherIndex(), a.freeText(), "Tab");
        }
        // Final question's advance lands on the "Submit" tab → one Enter submits the batch.
        sendKeys(n, target, "Enter");
    }

    /**
     * Shared per-question keystroke walk used by BOTH {@link #injectAnswer}
     * (single-question, {@code commitKey == "Enter"}) and
     * {@link #injectAnswerBatch} (per wizard tab, {@code commitKey == "Tab"} for a
     * multiSelect question). Assumes the option cursor is already at the TOP of the
     * current question's option list. Keeping this single helper prevents the two
     * paths from drifting apart on a Claude TUI version bump.
     *
     * <p>{@code commitKey} is the advance key for a <em>plain</em> multiSelect
     * question ({@code Tab} in a batch, {@code Enter} for a single-question ask).
     * single-select and single-select free-text always finish with {@code Enter},
     * which both selects and advances/submits. A multiSelect free-text "Other"
     * answer (UC-44) advances with a fixed {@code Tab}+{@code Enter} regardless of
     * {@code commitKey} (it must focus then activate the in-pane button from the
     * "Type something" row), plus a trailing {@code Enter} when {@code commitKey}
     * is {@code "Enter"} (single-question) to confirm the review screen.
     */
    private void selectQuestionAnswer(
            int n,
            InjectTarget target,
            int optionCount,
            boolean multiSelect,
            List<Integer> selections,
            int otherIndex,
            String freeText,
            String commitKey)
            throws IOException {
        List<Integer> sel = (selections == null) ? List.of() : selections;
        boolean freeTextChosen = otherIndex >= 0 && sel.contains(otherIndex) && freeText != null && !freeText.isBlank();

        if (multiSelect) {
            // Walk every option top→bottom. Toggle Space on each selected real option; at the
            // "Other"/"Type something" row (always the LAST index — the client appends it after the
            // listed options) inject the free text instead of a bare Space (UC-44).
            for (int p = 0; p < Math.max(optionCount, 1); p++) {
                if (freeTextChosen && p == otherIndex) {
                    // UC-44 — multiSelect + "Other", verified live against Claude 2.1.169:
                    //   1. type the literal INLINE — it shows as a checked PREVIEW, but the preview
                    //      is NOT yet a committed selection (the UC-43 silent-drop: typing then
                    //      navigating away dropped the text);
                    //   2. Enter COMMITS the typed text as a real custom option (clears the preview
                    //      check); 3. Space SELECTS that committed option.
                    // The text is always typed before any Enter — Enter on an EMPTY row declines.
                    sendLiteral(n, target, freeText);
                    sendKeys(n, target, "Enter");
                    sendKeys(n, target, "Space");
                } else if (sel.contains(p)) {
                    sendKeys(n, target, "Space");
                }
                if (p < optionCount - 1) {
                    sendKeys(n, target, "Down");
                }
            }
            if (freeTextChosen) {
                // The walk ends on the "Type something" row. From there a single key only FOCUSES
                // the in-pane "Next"/"Submit" button (it does NOT advance, unlike from a real option
                // where commitKey advances the wizard) — so press Tab to focus it then Enter to
                // ACTIVATE it. For a single-question ask (commitKey == "Enter") the wizard then lands
                // on its "Review your answers" screen with no caller-supplied trailing submit, so one
                // more Enter confirms it; in a batch, injectAnswerBatch's trailing Enter does that.
                sendKeys(n, target, "Tab");
                sendKeys(n, target, "Enter");
                if ("Enter".equals(commitKey)) {
                    sendKeys(n, target, "Enter");
                }
            } else {
                sendKeys(n, target, commitKey);
            }
        } else if (freeTextChosen) {
            // Single-select free-text: walk to the "Other"/"Type something" row, type the text
            // INLINE (it replaces the row label), then Enter to commit + advance/submit. The text
            // MUST be typed before the Enter — Enter on an empty "Type something" declines the ask.
            for (int d = 0; d < otherIndex; d++) {
                sendKeys(n, target, "Down");
            }
            sendLiteral(n, target, freeText);
            sendKeys(n, target, "Enter");
        } else {
            int target0 = sel.isEmpty() ? 0 : Math.max(0, sel.get(0));
            for (int d = 0; d < target0; d++) {
                sendKeys(n, target, "Down");
            }
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
