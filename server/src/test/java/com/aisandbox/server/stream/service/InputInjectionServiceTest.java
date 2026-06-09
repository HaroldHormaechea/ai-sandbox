package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.InputInjectionService.BatchAnswerSpec;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-37 — {@link InputInjectionService} translates conversation client frames
 * into {@code tmux send-keys} argv. These tests assert the centralized,
 * version-pinned keystroke mapping (AC8 prompt submit, AC9 multiline, AC11
 * answer selection incl. free-text, interrupt) by capturing the argv handed to
 * a mocked {@link ProcessExecutor} — proving the mapping is argv-only (no shell,
 * no string interpolation: a hostile label can never smuggle a second command).
 *
 * <p>AC→test map:
 * <ul>
 *   <li>AC8 — {@link #composer_single_line_sends_literal_then_Enter()}</li>
 *   <li>AC9 — {@link #composer_multiline_inserts_Cj_between_lines_and_final_Enter()}</li>
 *   <li>AC11 single — {@link #answer_single_select_walks_down_to_index_then_Enter()}</li>
 *   <li>AC11 multi — {@link #answer_multi_select_toggles_Space_on_each_selected()}</li>
 *   <li>AC11 free-text — {@link #answer_free_text_other_types_literal_then_Enter()}</li>
 *   <li>interrupt — {@link #interrupt_sends_Escape()}</li>
 *   <li>safety — {@link #literal_text_is_passed_verbatim_as_a_single_argv_element()},
 *       {@link #non_zero_exit_raises_IOException()}</li>
 * </ul>
 *
 * <p><b>UC-44</b> — the multiSelect + "Other" free-text injection (the real bug) is
 * now TYPED, not silently dropped. The keystroke model below is the one the
 * developer live-verified on Claude Code {@value InputInjectionService#PINNED_CLAUDE_VERSION}:
 * <ul>
 *   <li>AC2 batch multiSelect+Other — {@link #answer_batch_multiSelect_other_free_text_is_typed_and_committed()}</li>
 *   <li>AC1/AC5 non-last single-select free-text in a batch —
 *       {@link #answer_batch_non_last_single_select_free_text_advances_with_Enter_and_next_question_still_answered()}</li>
 *   <li>AC6 single-question multiSelect+Other (the {@code injectAnswer} review-confirm path) —
 *       {@link #answer_single_multiSelect_other_types_commits_selects_then_confirms_review()}</li>
 * </ul>
 */
class InputInjectionServiceTest {

    private ProcessExecutor exec;
    private InputInjectionService svc;

    @BeforeEach
    void setUp() throws Exception {
        exec = mock(ProcessExecutor.class);
        // Default: every send-keys succeeds.
        when(exec.run(any(), any(), any(Duration.class))).thenReturn(new ProcessExecutor.Result(0, "", ""));
        svc = new InputInjectionService(exec);
    }

    /** Capture every argv list run() handed the executor, in call order. */
    @SuppressWarnings("unchecked")
    private List<List<String>> capturedArgvs(int expectedCalls) throws Exception {
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(exec, times(expectedCalls)).run(cap.capture(), any(), any(Duration.class));
        return cap.getAllValues();
    }

    private static String tail(List<String> argv, int fromEnd) {
        return argv.get(argv.size() - fromEnd);
    }

    /**
     * Reduce each captured argv to ONE readable token in call order: a literal
     * send ({@code -l -- <text>}) becomes {@code "LIT:<text>"}; a named key send
     * becomes the key name (the last argv element, e.g. {@code Down}, {@code
     * Enter}, {@code Space}, {@code Tab}). Lets a test assert the EXACT keystroke
     * sequence — essential for UC-43 where the executor is mocked and a WRONG
     * order would still "pass" a loose count-only assertion.
     */
    private static List<String> keySeq(List<List<String>> calls) {
        List<String> seq = new ArrayList<>();
        for (List<String> c : calls) {
            if (c.contains("-l")) {
                seq.add("LIT:" + c.get(c.size() - 1));
            } else {
                seq.add(c.get(c.size() - 1));
            }
        }
        return seq;
    }

    // ──────────────────────── AC8 — prompt submit ────────────────────────────

    @Test
    void composer_single_line_sends_literal_then_Enter() throws Exception {
        svc.injectComposer(3, InjectTarget.main(), "hello world");

        List<List<String>> calls = capturedArgvs(2);
        // First call: literal send (…-l -- "hello world")
        assertThat(calls.get(0)).containsSubsequence("send-keys", "-t", "main");
        assertThat(calls.get(0)).containsSequence("-l", "--", "hello world");
        // Second call: Enter submits the turn.
        assertThat(tail(calls.get(1), 1)).isEqualTo("Enter");
    }

    @Test
    void empty_composer_text_sends_nothing() throws Exception {
        svc.injectComposer(3, InjectTarget.main(), "");
        verify(exec, times(0)).run(any(), any(), any(Duration.class));
    }

    // ──────────────────────── AC9 — multiline ────────────────────────────────

    @Test
    void composer_multiline_inserts_Cj_between_lines_and_final_Enter() throws Exception {
        svc.injectComposer(3, InjectTarget.main(), "line a\nline b");

        // literal(a), C-j, literal(b), Enter
        List<List<String>> calls = capturedArgvs(4);
        assertThat(calls.get(0)).containsSequence("-l", "--", "line a");
        assertThat(tail(calls.get(1), 1)).isEqualTo("C-j"); // newline-insert, NOT submit
        assertThat(calls.get(2)).containsSequence("-l", "--", "line b");
        assertThat(tail(calls.get(3), 1)).isEqualTo("Enter"); // final CR submits
    }

    // ──────────────────────── AC11 — single-select answer ────────────────────

    @Test
    void answer_single_select_walks_down_to_index_then_Enter() throws Exception {
        // optionCount=4, choose index 2, no free-text.
        svc.injectAnswer(3, InjectTarget.main(), 4, false, List.of(2), -1, null);

        List<List<String>> calls = capturedArgvs(/* 20 Up + 2 Down + Enter */ 23);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        long downs = calls.stream().filter(c -> "Down".equals(tail(c, 1))).count();
        assertThat(ups).isEqualTo(20); // deterministic cursor reset to top
        assertThat(downs).isEqualTo(2); // walk to index 2
        assertThat(tail(calls.get(calls.size() - 1), 1)).isEqualTo("Enter");
    }

    // ──────────────────────── AC11 — multi-select answer ─────────────────────

    @Test
    void answer_multi_select_toggles_Space_on_each_selected() throws Exception {
        // optionCount=3, multiSelect, choose 0 and 2.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(0, 2), -1, null);

        List<List<String>> calls = capturedArgvs(/* 20 Up + walk(2 Space + 2 Down) + Enter */ 25);
        long spaces = calls.stream().filter(c -> "Space".equals(tail(c, 1))).count();
        assertThat(spaces).isEqualTo(2); // one toggle per selected option
        assertThat(tail(calls.get(calls.size() - 1), 1)).isEqualTo("Enter");
    }

    // ──────────────────────── AC11 — free-text "Other" ───────────────────────

    @Test
    void answer_free_text_other_types_literal_then_Enter() throws Exception {
        // otherIndex=2 is selected; free text supplied.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "my custom answer");

        // UC-43 bugfix (live-verified on 2.1.169): the free-text path now TYPES BEFORE Enter —
        // walk Down to the "Type something" row, type the text INLINE, THEN a single Enter. The
        // OLD order (Enter-on-empty, then type) DECLINED the ask. So the sequence is
        // 20 Up (reset) + 2 Down (to otherIndex 2) + literal + Enter = 24 calls, with NO
        // intermediate Enter between the Downs and the literal.
        List<List<String>> calls = capturedArgvs(24);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        long downs = calls.stream().filter(c -> "Down".equals(tail(c, 1))).count();
        long enters = calls.stream().filter(c -> "Enter".equals(tail(c, 1))).count();
        assertThat(ups).isEqualTo(20);
        assertThat(downs).isEqualTo(2);
        assertThat(enters)
                .as("exactly one Enter — typed text is committed once, not after an empty row")
                .isEqualTo(1);
        // The literal is sent verbatim as a single argv element …
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "my custom answer"));
        // … and it is the LAST thing before the final Enter (type-before-Enter, the corrected order).
        List<String> seq = keySeq(calls);
        assertThat(seq.subList(seq.size() - 3, seq.size())).containsExactly("Down", "LIT:my custom answer", "Enter");
    }

    @Test
    void answer_other_blank_free_text_is_not_typed() throws Exception {
        // Other "selected" but freeText blank → freeText path is skipped.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "   ");
        List<List<String>> calls = capturedArgvs(/* 20 Up + 2 Down + Enter */ 23);
        assertThat(calls).noneSatisfy(c -> assertThat(c).contains("-l"));
    }

    // ──────────────── UC-44 AC6 — single-question multiSelect + "Other" (injectAnswer) ────────────────

    @Test
    void answer_single_multiSelect_other_types_commits_selects_then_confirms_review() throws Exception {
        // AC6 — the SAME root-cause fix in the single-question `injectAnswer` path: a lone
        // multiSelect question answered with a real option (index 0) PLUS an "Other" free text.
        // Mirrors the handler's deriveAnswerSpec output for 2 listed options [X,Y] + "Other":
        //   optionCount = 2 listed + 1 (the Other row, because freeText is non-blank) = 3
        //   otherIndex  = 2 (the Other row is always last); selections = [0, 2] (client appends Other).
        // The implemented multiSelect+Other walk (verified live on 2.1.169):
        //   reset cursor (20×Up); Space on option 0; Down; Down to the "Type something" row; type the
        //   literal; Enter (COMMIT the typed text as a custom option — NOT a silent preview drop);
        //   Space (SELECT it); then from that row Tab FOCUSES + Enter ACTIVATES the in-pane Submit;
        //   and because this is the single-question path (commitKey == "Enter") one final Enter
        //   confirms the "Review your answers" screen.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(0, 2), 2, "custom");

        List<List<String>> calls = capturedArgvs(/* 20 Up + 9 */ 29);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        assertThat(ups)
                .as("single-question injectAnswer resets the cursor to the top")
                .isEqualTo(20);
        // The literal MUST be sent — this is the UC-44 fix (the prior code toggled Space but never typed).
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "custom"));
        // Pin the EXACT post-reset sequence (drop the 20 leading Ups).
        List<String> seq = keySeq(calls);
        assertThat(seq.subList(20, seq.size()))
                .containsExactly("Space", "Down", "Down", "LIT:custom", "Enter", "Space", "Tab", "Enter", "Enter");
    }

    // ──────────────────────── interrupt ──────────────────────────────────────

    @Test
    void interrupt_sends_Escape() throws Exception {
        svc.interrupt(3, InjectTarget.main());
        List<List<String>> calls = capturedArgvs(1);
        assertThat(tail(calls.get(0), 1)).isEqualTo("Escape");
    }

    // ──────────────────────── safety / argv discipline ───────────────────────

    @Test
    void literal_text_is_passed_verbatim_as_a_single_argv_element() throws Exception {
        // A hostile string with shell metacharacters and an embedded tmux verb.
        String hostile = "; tmux kill-server # $(rm -rf /)";
        svc.injectComposer(3, InjectTarget.main(), hostile);
        List<List<String>> calls = capturedArgvs(2);
        // The whole hostile string is ONE argv token after "-l --"; never split,
        // never interpreted as a second command.
        assertThat(calls.get(0)).containsSequence("-l", "--", hostile);
    }

    @Test
    void argv_targets_a_pane_spec_when_target_has_window_and_pane() throws Exception {
        InjectTarget pane = new InjectTarget(null, "main", "0", "1");
        svc.interrupt(3, pane);
        List<List<String>> calls = capturedArgvs(1);
        // session:window.pane spec.
        assertThat(calls.get(0)).containsSequence("-t", "main:0.1");
    }

    @Test
    void argv_includes_socket_flag_when_target_has_socket() throws Exception {
        InjectTarget sock = new InjectTarget("/tmp/tmux-997/claude-swarm-1", "claude-swarm", null, null);
        svc.interrupt(3, sock);
        List<List<String>> calls = capturedArgvs(1);
        assertThat(calls.get(0)).containsSequence("-S", "/tmp/tmux-997/claude-swarm-1");
    }

    @Test
    void non_zero_exit_raises_IOException() throws Exception {
        when(exec.run(any(), any(), any(Duration.class)))
                .thenReturn(new ProcessExecutor.Result(1, "", "tmux: no server"));
        assertThatThrownBy(() -> svc.injectComposer(3, InjectTarget.main(), "x"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void inject_target_uses_compose_project_for_session_n() throws Exception {
        svc.interrupt(9, InjectTarget.main());
        List<List<String>> calls = capturedArgvs(1);
        assertThat(calls.get(0)).containsSubsequence("docker", "compose", "-p", "ai-sandbox-9", "exec");
    }

    @Test
    void interrupt_with_null_target_defaults_to_main() throws Exception {
        svc.interrupt(1, null);
        List<List<String>> calls = capturedArgvs(1);
        assertThat(calls.get(0)).containsSequence("-t", "main");
        assertThat(tail(calls.get(0), 1)).isEqualTo("Escape");
    }

    // ──────────────── UC-43 — multi-question answer-batch (verified wizard model) ────────────────
    //
    // The executor is MOCKED, so a wrong keystroke ORDER would still pass a loose
    // count-only assertion. Every test below pins the EXACT ordered sequence
    // against the model the developer live-verified on Claude Code 2.1.169:
    //   • single-select question : Down×k then Enter   (Enter selects AND advances the tab)
    //   • multiSelect question   : Space toggles in place, Down between options, then Tab advances
    //   • free-text "Other"      : Down to the row, type the text INLINE, then Enter
    //   • after the last question advances → final Enter submits the whole batch
    // and: NO 20×Up cursor reset (the wizard auto-resets each tab's option cursor;
    // a blind Up reset is non-deterministic because Up/Down wrap the option ring).

    @Test
    void answer_batch_of_one_single_select_walks_down_then_Enter_then_submit_Enter() throws Exception {
        // AC3 — a batch with a single single-select question (optionCount=3, choose index 2).
        // Selection walk (Down×2, Enter) is shared with the single-question path; the trailing
        // Enter submits the one-tab form. Crucially: NO Up reset (batch relies on auto-reset).
        svc.injectAnswerBatch(3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, false, List.of(2), 3, null)));

        List<List<String>> calls = capturedArgvs(4);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Enter", "Enter");
        assertThat(calls.stream().filter(c -> "Up".equals(tail(c, 1))).count())
                .as("batch path must NOT emit the 20x Up cursor reset")
                .isZero();
    }

    @Test
    void answer_batch_multi_question_sequence_is_exact_per_the_verified_model() throws Exception {
        // AC1/AC2/AC3 — two questions in one batch:
        //   Q0 single-select, optionCount=3, choose index 1 → Down, Enter (Enter advances the tab)
        //   Q1 multiSelect,   optionCount=2, choose index 0 → Space, Down, Tab (Tab advances the tab)
        // then a final Enter submits the whole sheet.
        svc.injectAnswerBatch(
                3,
                InjectTarget.main(),
                List.of(
                        new BatchAnswerSpec(3, false, List.of(1), 3, null),
                        new BatchAnswerSpec(2, true, List.of(0), 2, null)));

        List<List<String>> calls = capturedArgvs(6);
        assertThat(keySeq(calls)).containsExactly("Down", "Enter", "Space", "Down", "Tab", "Enter");
    }

    @Test
    void answer_batch_single_select_free_text_types_before_Enter() throws Exception {
        // AC3 — a single-select question whose "Other" free-text is chosen. The Other row sits at
        // otherIndex=2 (after the 2 listed options); the text is typed INLINE *before* the Enter
        // (Enter on an empty "Type something" declines the ask — the bug the developer fixed).
        svc.injectAnswerBatch(3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, false, List.of(2), 2, "hi")));

        List<List<String>> calls = capturedArgvs(5);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:hi", "Enter", "Enter");
    }

    @Test
    void answer_batch_multiSelect_other_free_text_is_typed_and_committed() throws Exception {
        // UC-44 AC2 — THE bug. A multiSelect question's custom "Other" free-text is now TYPED into
        // the wizard (no longer toggled-but-dropped). This positive assertion replaces the prior
        // `…_NOT_typed_documented_limitation` test. Spec mirrors the handler's deriveAnswerSpec for
        // 2 listed options [X,Y] + "Other": optionCount = 2 + 1 (Other row) = 3, otherIndex = 2,
        // selections = [0, 2] (option 0 plus the client-appended Other index).
        // Verified live on 2.1.169: walk Space on option 0, Down, Down to the "Type something" row,
        // type the literal, Enter (COMMIT — the on-type checkmark is only a non-committed preview),
        // Space (SELECT the committed option); then Tab FOCUSES + Enter ACTIVATES the in-pane Next
        // button (a single key from that row does NOT advance, unlike from a real option). A batch
        // appends ONE final Enter to submit the whole sheet.
        svc.injectAnswerBatch(
                3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, true, List.of(0, 2), 2, "custom answer")));

        List<List<String>> calls = capturedArgvs(9);
        assertThat(keySeq(calls))
                .containsExactly(
                        "Space", "Down", "Down", "LIT:custom answer", "Enter", "Space", "Tab", "Enter", "Enter");
        // The literal IS sent verbatim as a single argv element (the regression this fixes).
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "custom answer"));
        // Still NO 20×Up cursor reset in the batch path (the wizard auto-resets each tab).
        assertThat(calls.stream().filter(c -> "Up".equals(tail(c, 1))).count())
                .as("batch path must NOT emit the 20x Up cursor reset")
                .isZero();
    }

    @Test
    void answer_batch_non_last_single_select_free_text_advances_with_Enter_and_next_question_still_answered()
            throws Exception {
        // UC-44 AC1/AC4/AC5 — a single-select "Other" free text on a NON-LAST question must be
        // delivered verbatim, advance the wizard with a single Enter (NOT submit the batch early),
        // and leave the next question correctly answered on its own tab. Two-question batch:
        //   Q0 single-select "Other" "hi"  → optionCount = 1+1=… here 2 listed +1 = 3, otherIndex 2,
        //                                      selections=[2]: Down, Down (to the Other row), type, Enter (advance)
        //   Q1 plain single-select index 1 → Down (to index 1), Enter (advance to Submit tab)
        // then ONE final Enter submits the whole sheet.
        svc.injectAnswerBatch(
                3,
                InjectTarget.main(),
                List.of(
                        new BatchAnswerSpec(3, false, List.of(2), 2, "hi"),
                        new BatchAnswerSpec(2, false, List.of(1), 2, null)));

        List<List<String>> calls = capturedArgvs(7);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:hi", "Enter", "Down", "Enter", "Enter");
        // Exactly THREE Enters: Q0 advance, Q1 advance, final submit — no extra (early) submit.
        assertThat(calls.stream().filter(c -> "Enter".equals(tail(c, 1))).count())
                .as("no early submit: one Enter per question advance + one final submit")
                .isEqualTo(3);
        // The non-last free-text is typed BEFORE its advancing Enter (Enter on an empty row declines).
        List<String> seq = keySeq(calls);
        assertThat(seq.subList(0, 4)).containsExactly("Down", "Down", "LIT:hi", "Enter");
    }

    @Test
    void empty_or_null_answer_batch_sends_nothing() throws Exception {
        svc.injectAnswerBatch(3, InjectTarget.main(), List.of());
        svc.injectAnswerBatch(3, InjectTarget.main(), null);
        verify(exec, times(0)).run(any(), any(), any(Duration.class));
    }

    @Test
    void answer_batch_three_questions_submit_only_once_after_the_last() throws Exception {
        // AC4 — the whole sheet resolves in ONE keystroke sequence with exactly ONE final
        // submit Enter after the last question advances (not one submit per question). Three
        // single-select questions choosing index 0 each → Enter advances each, last Enter submits.
        svc.injectAnswerBatch(
                3,
                InjectTarget.main(),
                List.of(
                        new BatchAnswerSpec(2, false, List.of(0), 2, null),
                        new BatchAnswerSpec(2, false, List.of(0), 2, null),
                        new BatchAnswerSpec(2, false, List.of(0), 2, null)));

        List<List<String>> calls = capturedArgvs(4);
        // Q0 Enter (advance), Q1 Enter (advance), Q2 Enter (advance to Submit tab), final Enter (submit).
        assertThat(keySeq(calls)).containsExactly("Enter", "Enter", "Enter", "Enter");
    }
}
