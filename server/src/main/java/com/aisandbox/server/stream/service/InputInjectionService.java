package com.aisandbox.server.stream.service;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
 *   <li><b>Answer (single-select)</b> — the sheet opens with the option cursor
 *       at the top (index 0), so {@code Down} ×k walks to the chosen index, then
 *       {@code Enter} (AC11). No blind {@code Up} reset — the option ring wraps,
 *       so a fixed {@code Up} ×N is non-deterministic (UC-57); this mirrors the
 *       verified batch path (see {@link #injectAnswerBatch}).</li>
 *   <li><b>Answer (multiSelect)</b> — from the top-open cursor, walk every option
 *       toggling {@code Space} on the selected ones, then advance. With an "Other" answer
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

    /**
     * UC-98 (Bug 1) — the pane substring that marks Claude's interactive input prompt box as
     * READY to accept a keystroke, used ONLY by the spawn-scoped {@link #injectSpawnPrompt}
     * gate. This is a <b>substring-presence</b> check (NOT a structural TUI parse, so it stays
     * off the Node pane-parser's pin surface); it is pinned to {@link #PINNED_CLAUDE_VERSION}
     * alongside every other keystroke mapping in this class.
     *
     * <p>{@code "for shortcuts"} is the idle-composer footer hint ({@code "? for shortcuts"})
     * Claude Code renders once the input box is interactive and empty — it is absent while the
     * TUI is still booting, which is exactly the spawn race Bug 1 fixes: the container readiness
     * marker ({@code /tmp/aisandbox-ready}) means the tmux session exists, NOT that Claude's
     * prompt is live. The needle is matched case-insensitively on a whitespace-flattened capture
     * so an 80-column wrap cannot hide it. <b>QA must live-verify this marker against the pinned
     * build</b> (top TUI-pin risk); if it proves unreliable at 80 cols the documented fallback is
     * the {@code "Claude Code v" + PINNED_CLAUDE_VERSION} welcome banner. A version bump re-checks
     * this constant together with the other pinned mappings.
     */
    public static final String PROMPT_READY_MARKER = "for shortcuts";

    /** UC-98 (Bug 1) — how long to poll {@code capture-pane} for the ready prompt before degrading. */
    private static final Duration SPAWN_GATE_TIMEOUT = Duration.ofSeconds(30);

    /** UC-98 (Bug 1) — how long to poll for the typed literal to echo before declining to submit. */
    private static final Duration SPAWN_CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    /** UC-98 (Bug 1) — pause between {@code capture-pane} polls in the gate/confirm loops. */
    private static final Duration SPAWN_POLL_INTERVAL = Duration.ofMillis(500);

    /**
     * UC-55 — the wizard-tab NAVIGATION keys, the ONLY keystrokes the read-only
     * option-recovery walk ({@link #stepWizardForward} / {@link #stepWizardBack}) is ever
     * allowed to send. {@code Right} advances the focused tab for reading, {@code Left}
     * steps back to restore it; neither commits or mutates any answer (Phase-0 verified).
     * This set deliberately EXCLUDES every selection/commit key ({@code Space}, {@code
     * Enter}, {@code Tab}, digits, literals) used by the answer-injection paths — so the
     * recovery walk's flicker is provably non-corrupting (LOCKED CONDITION 3: the walk must
     * never emit a selection-or-commit key). QA asserts the recovery keystroke set against
     * this constant.
     */
    public static final String WIZARD_NEXT_TAB_KEY = "Right";

    public static final String WIZARD_PREV_TAB_KEY = "Left";

    public static final java.util.Set<String> WIZARD_NAV_KEYS =
            java.util.Set.of(WIZARD_NEXT_TAB_KEY, WIZARD_PREV_TAB_KEY);

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
        typeMultiline(n, target, text);
        sendKeys(n, target, "Enter"); // CR submits the turn
    }

    /**
     * UC-98 (Bug 1) — outcome of the spawn-scoped {@link #injectSpawnPrompt}, so the caller
     * ({@link com.aisandbox.server.stream.facade.ConversationFacade#inject}) can audit which
     * branch the injection took. Every non-{@link #SUBMITTED} branch sends <b>zero</b> Enter —
     * the exactly-once submit guarantee.
     */
    public enum SpawnInjectOutcome {
        /** Prompt was ready, the typed literal was confirmed on the pane, and exactly one Enter submitted it. */
        SUBMITTED,
        /** Gate timed out — the input prompt never became ready, so NOTHING was typed and no Enter was sent. */
        PROMPT_NOT_READY,
        /** The literal was typed but never echoed onto the pane, so it was NOT submitted (graceful degrade). */
        TYPE_NOT_CONFIRMED
    }

    /**
     * UC-98 (Bug 1) — inject the fixed spawn/setup prompt into a freshly-spawned session with an
     * explicit gate→type→confirm→submit choreography, fixing the submit-timing race the shared
     * {@link #injectComposer} hits on the spawn path. {@code injectComposer} types then fires
     * {@code Enter} back-to-back; on the spawn path that runs the instant the container readiness
     * marker appears — before Claude's TUI input prompt is interactive — so the {@code Enter} is
     * lost and the prompt is staged but never submitted.
     *
     * <p>This variant instead, on the SAME argv-only {@link ProcessExecutor} surface (adding only
     * {@code capture-pane} reads), does:
     * <ol>
     *   <li><b>Gate</b> — poll {@code capture-pane} until {@link #PROMPT_READY_MARKER} is present.
     *       On timeout: log, inject nothing, return {@link SpawnInjectOutcome#PROMPT_NOT_READY}
     *       (0 submits).</li>
     *   <li><b>Type</b> the literal via the shared newline-safe {@link #typeMultiline} (no commit
     *       keystroke).</li>
     *   <li><b>Confirm</b> — poll {@code capture-pane} until the typed literal is echoed onto the
     *       pane (a substring-presence check). On timeout: log, do NOT press Enter, return
     *       {@link SpawnInjectOutcome#TYPE_NOT_CONFIRMED} (never a blind submit).</li>
     *   <li><b>Submit</b> — send <b>exactly one</b> {@code Enter} (the sole submit across all
     *       branches) and return {@link SpawnInjectOutcome#SUBMITTED}.</li>
     * </ol>
     *
     * <p>The shared {@link #injectComposer} is deliberately left untouched — the conversation
     * composer works precisely because by the time a human types, the TUI is long ready; only the
     * spawn path fires pre-prompt.
     */
    public SpawnInjectOutcome injectSpawnPrompt(int n, InjectTarget target, String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return SpawnInjectOutcome.SUBMITTED; // nothing to submit — no-op, mirrors injectComposer's blank guard
        }
        // 1. GATE — never type until the interactive input prompt box is on the pane.
        if (!pollPaneUntil(n, target, SPAWN_GATE_TIMEOUT, InputInjectionService::promptReady)) {
            LOG.warn("UC-98 spawn-inject gate timed out for n={} (prompt-not-ready); injecting nothing", n);
            return SpawnInjectOutcome.PROMPT_NOT_READY;
        }
        // 2. TYPE the literal (newline-safe, no trailing commit keystroke).
        typeMultiline(n, target, text);
        // 3. CONFIRM — never submit until the literal is echoed back onto the pane.
        String needle = confirmNeedle(text);
        if (!pollPaneUntil(n, target, SPAWN_CONFIRM_TIMEOUT, pane -> paneContains(pane, needle))) {
            LOG.warn("UC-98 spawn-inject type not confirmed for n={} (type-not-confirmed); NOT submitting", n);
            return SpawnInjectOutcome.TYPE_NOT_CONFIRMED;
        }
        // 4. Exactly one Enter — the sole submit.
        sendKeys(n, target, "Enter");
        return SpawnInjectOutcome.SUBMITTED;
    }

    /**
     * UC-75 — type {@code text} into the focused input as literal segments,
     * inserting a {@code C-j} (LF) BETWEEN lines so an embedded newline NEVER
     * commits the turn. <b>Invariant: the line separator is always {@code C-j},
     * never a bare {@code Enter} on an empty segment</b> — a bare {@code
     * Enter}/newline on the "Type something" answer row commits or declines the
     * ask. This helper sends <b>no trailing commit keystroke</b>; callers own the
     * commit (the composer's {@code Enter}, or the answer path's Enter/Space/Tab
     * choreography). Extracted from {@link #injectComposer}'s verified line-walk
     * so the answer free-text path becomes newline-safe in exactly the same way
     * (AC2/AC3). A blank/whitespace-only interior segment is skipped for content
     * but still advances with {@code C-j} — identical to the composer.
     */
    private void typeMultiline(int n, InjectTarget target, String text) throws IOException {
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
    }

    /**
     * AC11 — translate a single-question structured answer into the
     * option-selection keystrokes for the lone {@code AskUserQuestion} sheet.
     *
     * <p>Delegates to the shared per-question keystroke helper
     * ({@link #selectQuestionAnswer}) with {@code Enter} as the commit key — for
     * a single-question sheet there is no tab to advance to, so {@code Enter}
     * both selects/commits and submits.
     *
     * <p><b>No blind cursor reset.</b> The sheet opens with the option cursor
     * already at the top (index 0), so the helper's {@code Down}×selectedIndex
     * walk reaches the target directly — exactly as the multi-question
     * {@link #injectAnswerBatch} path does. A prior {@code Up}×N "reset" was
     * <em>removed</em> (UC-57): the TUI option ring <b>wraps</b>, so pressing
     * {@code Up} a fixed number of times from an unknown position landed at a
     * non-deterministic option {@code (currentIndex − N) mod ringSize} and sent
     * the wrong selection.
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
     *       question when the wizard advances — so NO per-question blind cursor
     *       reset is used here (and indeed must not be: in the wizard,
     *       {@code Up}/{@code Down} <b>wrap around</b> the option ring, so a blind
     *       {@code Up}×N reset is not deterministic). The single-question
     *       {@link #injectAnswer} path relies on the same top-of-list assumption
     *       (UC-57 removed its former {@code Up}×N reset, which wrapped the ring
     *       and sent the wrong option).</li>
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
                    // UC-75: type newline-safe (C-j between lines) so an embedded newline cannot
                    // commit/decline early; the Enter/Space choreography below is unchanged.
                    typeMultiline(n, target, freeText);
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
            // UC-75: type newline-safe (C-j between lines, no trailing submit) so an embedded
            // newline can't decline the ask; the committing Enter below is unchanged.
            typeMultiline(n, target, freeText);
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

    /**
     * UC-55 — advance the multi-question {@code AskUserQuestion} wizard to the NEXT tab
     * for READING ONLY (the {@code Right} arrow). Verified live against Claude Code
     * (Phase-0 spike): {@code Right} moves the focused tab and reveals that tab's full
     * option list <b>without committing any selection</b> (the per-tab answer boxes stay
     * unchecked) and without moving the option cursor. The server uses this — paired with
     * a {@code --parse-pane} capture per tab and a matching {@link #stepWizardBack} walk to
     * restore the original tab — to recover every tab's options so the whole batch becomes
     * in-app answerable. {@code Right}/{@code Left} are pure navigation here; they are NOT
     * the {@code Tab}/{@code Enter} commit keys {@link #injectAnswerBatch} uses to answer.
     */
    public void stepWizardForward(int n, InjectTarget target) throws IOException {
        sendKeys(n, target, WIZARD_NEXT_TAB_KEY);
    }

    /**
     * UC-55 — step the wizard BACK one tab (the {@code Left} arrow), used to restore the
     * focused tab to its original position after a read-only per-tab option-recovery walk
     * (Phase-0 verified: {@code Left} returns to the prior tab with its option cursor at the
     * top and no answer state mutated). Restoration is load-bearing: {@link #injectAnswerBatch}
     * assumes the wizard opens at the first question's option list, so recovery must leave the
     * pane exactly as it found it.
     */
    public void stepWizardBack(int n, InjectTarget target) throws IOException {
        sendKeys(n, target, WIZARD_PREV_TAB_KEY);
    }

    // ──────────────────────── internals ────────────────────────

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
        InjectTarget t = (target == null) ? InjectTarget.main() : target;
        List<String> argv = dockerComposeTmux(n, t);
        argv.add("send-keys");
        argv.add("-t");
        argv.add(t.spec());
        return argv;
    }

    /**
     * {@code docker compose -p ai-sandbox-N exec -T claude-sandbox tmux [-S socket]} — the shared
     * argv prefix for every tmux invocation ({@code send-keys} and {@code capture-pane}). Pure
     * argv, no shell, no interpolation — same contract as the rest of this class.
     */
    private List<String> dockerComposeTmux(int n, InjectTarget t) {
        List<String> argv = new ArrayList<>(
                List.of("docker", "compose", "-p", "ai-sandbox-" + n, "exec", "-T", "claude-sandbox", "tmux"));
        if (t.socket() != null && !t.socket().isBlank()) {
            argv.add("-S");
            argv.add(t.socket());
        }
        return argv;
    }

    private void run(List<String> argv) throws IOException {
        ProcessExecutor.Result r = exec.run(argv, null, TIMEOUT);
        if (r.exitCode() != 0) {
            LOG.warn("send-keys failed (exit={}): {}", r.exitCode(), r.stderr());
            throw new IOException("tmux send-keys failed: " + r.stderr());
        }
    }

    // ──────────────────────── UC-98 (Bug 1) spawn-inject gate/confirm ────────────────────────

    /**
     * Poll {@code capture-pane} for {@code target} until {@code condition} holds or {@code timeout}
     * elapses. Returns {@code true} once the condition is met, {@code false} on timeout or thread
     * interruption. A failed/empty capture is treated as "not yet" (keep polling) — never fatal.
     * Intended for the post-spawn daemon thread, where a bounded blocking poll is safe (the spawn
     * readiness wait already blocks there).
     */
    private boolean pollPaneUntil(int n, InjectTarget target, Duration timeout, Predicate<String> condition)
            throws IOException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            String pane = capturePane(n, target);
            if (pane != null && condition.test(pane)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(SPAWN_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * {@code tmux capture-pane -p -t <spec>} — the visible pane text, or {@code null} on any
     * failure (non-zero exit, exec error, empty capture). {@code null} means "unknown, keep
     * polling"; it is never propagated as an error, so a transient capture failure can never turn
     * into a blind submit.
     */
    private String capturePane(int n, InjectTarget target) {
        InjectTarget t = (target == null) ? InjectTarget.main() : target;
        List<String> argv = dockerComposeTmux(n, t);
        argv.add("capture-pane");
        argv.add("-p");
        argv.add("-t");
        argv.add(t.spec());
        try {
            ProcessExecutor.Result r = exec.run(argv, null, TIMEOUT);
            if (r.exitCode() != 0) {
                LOG.debug("capture-pane failed (exit={}): {}", r.exitCode(), r.stderr());
                return null;
            }
            String out = r.stdout();
            return (out == null || out.isEmpty()) ? null : out;
        } catch (IOException e) {
            LOG.debug("capture-pane errored for n={}: {}", n, e.toString());
            return null;
        }
    }

    /**
     * Is Claude's interactive input prompt box present on {@code paneText}? A substring-presence
     * check for {@link #PROMPT_READY_MARKER} on a whitespace-flattened, case-folded capture (so an
     * 80-column wrap of the footer hint cannot hide the marker). NOT a structural TUI parse.
     */
    private static boolean promptReady(String paneText) {
        return paneContains(paneText, PROMPT_READY_MARKER);
    }

    /** Whitespace-flattened, case-insensitive substring-presence test. Null-safe (empty needle → false). */
    private static boolean paneContains(String paneText, String needle) {
        if (paneText == null || needle == null || needle.isBlank()) {
            return false;
        }
        String flatPane = paneText.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        String flatNeedle = needle.replaceAll("\\s+", " ")
                .toLowerCase(java.util.Locale.ROOT)
                .trim();
        return flatPane.contains(flatNeedle);
    }

    /**
     * The confirm-step needle for {@code text}: its first non-blank line, trimmed. The spawn prompt
     * is single-line, so this is the whole prompt; for a hypothetical multiline literal it is the
     * first line, which is enough to prove the input box accepted the typed text before we submit.
     */
    private static String confirmNeedle(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return text.trim();
    }
}
