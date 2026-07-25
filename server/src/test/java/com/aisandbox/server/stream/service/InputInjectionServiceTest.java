package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.InputInjectionService.BatchAnswerSpec;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import com.aisandbox.server.stream.service.InputInjectionService.SpawnInjectOutcome;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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

        // UC-57 — the single-question path no longer emits a blind 20×Up cursor reset (the option
        // ring WRAPS, so Up×N from an unknown position landed on the WRONG option and sent it). The
        // sheet opens at the top, so the walk is just Down×index then Enter — matching the batch path.
        List<List<String>> calls = capturedArgvs(/* 2 Down + Enter */ 3);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        long downs = calls.stream().filter(c -> "Down".equals(tail(c, 1))).count();
        assertThat(ups)
                .as("UC-57: no blind Up cursor reset (the wrapping-ring bug)")
                .isZero();
        assertThat(downs).isEqualTo(2); // walk to index 2
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Enter");
    }

    // ──────────────────────── AC11 — multi-select answer ─────────────────────

    @Test
    void answer_multi_select_toggles_Space_on_each_selected() throws Exception {
        // optionCount=3, multiSelect, choose 0 and 2.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(0, 2), -1, null);

        // UC-57 — no blind 20×Up reset (see single-select test). Walk from the top toggling Space on
        // each selected option: Space(0), Down, Down, Space(2), Enter = 5 calls, ZERO Up.
        List<List<String>> calls = capturedArgvs(/* Space + Down + Down + Space + Enter */ 5);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        long spaces = calls.stream().filter(c -> "Space".equals(tail(c, 1))).count();
        assertThat(ups).as("UC-57: no blind Up cursor reset").isZero();
        assertThat(spaces).isEqualTo(2); // one toggle per selected option
        assertThat(keySeq(calls)).containsExactly("Space", "Down", "Down", "Space", "Enter");
    }

    // ──────────────────────── AC11 — free-text "Other" ───────────────────────

    @Test
    void answer_free_text_other_types_literal_then_Enter() throws Exception {
        // otherIndex=2 is selected; free text supplied.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "my custom answer");

        // UC-43 bugfix (live-verified on 2.1.169): the free-text path now TYPES BEFORE Enter —
        // walk Down to the "Type something" row, type the text INLINE, THEN a single Enter. The
        // OLD order (Enter-on-empty, then type) DECLINED the ask. UC-57: no blind 20×Up reset, so the
        // sequence is just 2 Down (to otherIndex 2) + literal + Enter = 4 calls, with NO intermediate
        // Enter between the Downs and the literal and ZERO Up.
        List<List<String>> calls = capturedArgvs(4);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        long downs = calls.stream().filter(c -> "Down".equals(tail(c, 1))).count();
        long enters = calls.stream().filter(c -> "Enter".equals(tail(c, 1))).count();
        assertThat(ups).as("UC-57: no blind Up cursor reset").isZero();
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
        // Other "selected" but freeText blank → freeText path is skipped; falls back to walking
        // Down to the (blank-Other) index and Enter. UC-57: no blind 20×Up reset → 2 Down + Enter.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "   ");
        List<List<String>> calls = capturedArgvs(/* 2 Down + Enter */ 3);
        assertThat(calls).noneSatisfy(c -> assertThat(c).contains("-l"));
        assertThat(calls.stream().filter(c -> "Up".equals(tail(c, 1))).count())
                .as("UC-57: no blind Up cursor reset")
                .isZero();
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Enter");
    }

    // ──────────────── UC-44 AC6 — single-question multiSelect + "Other" (injectAnswer) ────────────────

    @Test
    void answer_single_multiSelect_other_types_commits_selects_then_confirms_review() throws Exception {
        // AC6 — the SAME root-cause fix in the single-question `injectAnswer` path: a lone
        // multiSelect question answered with a real option (index 0) PLUS an "Other" free text.
        // Mirrors the handler's deriveAnswerSpec output for 2 listed options [X,Y] + "Other":
        //   optionCount = 2 listed + 1 (the Other row, because freeText is non-blank) = 3
        //   otherIndex  = 2 (the Other row is always last); selections = [0, 2] (client appends Other).
        // The implemented multiSelect+Other walk (verified live on 2.1.169), UC-57: NO cursor reset —
        //   Space on option 0; Down; Down to the "Type something" row; type the literal; Enter (COMMIT
        //   the typed text as a custom option — NOT a silent preview drop); Space (SELECT it); then
        //   from that row Tab FOCUSES + Enter ACTIVATES the in-pane Submit; and because this is the
        //   single-question path (commitKey == "Enter") one final Enter confirms the "Review your
        //   answers" screen.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(0, 2), 2, "custom");

        List<List<String>> calls = capturedArgvs(9);
        long ups = calls.stream().filter(c -> "Up".equals(tail(c, 1))).count();
        assertThat(ups)
                .as("UC-57: single-question injectAnswer no longer emits a blind Up cursor reset")
                .isZero();
        // The literal MUST be sent — this is the UC-44 fix (the prior code toggled Space but never typed).
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "custom"));
        // Pin the EXACT sequence from index 0 (UC-57 dropped the leading Up reset).
        assertThat(keySeq(calls))
                .containsExactly("Space", "Down", "Down", "LIT:custom", "Enter", "Space", "Tab", "Enter", "Enter");
    }

    // ──────────────── UC-57 AC6 — wrong-option regression guard (single-question path) ────────────────
    //
    // The reported bug: answering an in-app AskUserQuestion sent the FIRST visible option instead of
    // the one selected. Root cause in the keystroke layer: a blind Up×20 "reset" walked the WRAPPING
    // option ring from an unknown position, landing on a non-deterministic index. The fix removes the
    // reset so the walk is purely Down×selectedIndex from the top-open cursor. These tests pin, for a
    // single-question sheet, that selecting a NON-FIRST option produces exactly Down×index + Enter with
    // ZERO Up — so the off-by-one / first-option regression can never silently return. The MOCKED
    // executor means a wrong ORDER would pass a count-only check, so every case pins the exact sequence.

    @Test
    void uc57_single_select_index1_of_3_sends_exactly_one_Down_then_Enter_zero_Up() throws Exception {
        // 3 options, pick the SECOND (index 1) — the exact shape the user reported (tapped 2nd, got 1st).
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(1), -1, null);
        List<List<String>> calls = capturedArgvs(2);
        assertThat(keySeq(calls)).containsExactly("Down", "Enter");
        assertNoUp(calls);
    }

    @Test
    void uc57_single_select_last_index_of_3_sends_two_Downs_then_Enter_zero_Up() throws Exception {
        // 3 options, pick the LAST (index 2) — boundary of the walk.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), -1, null);
        List<List<String>> calls = capturedArgvs(3);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Enter");
        assertNoUp(calls);
    }

    @Test
    void uc57_two_option_index0_sends_no_Down_no_Up_just_Enter() throws Exception {
        // 2 options, pick the FIRST (index 0): the top-open cursor is already there → just Enter.
        // This is the exact 2-option swap shape from the report; here index 0 must stay index 0.
        svc.injectAnswer(3, InjectTarget.main(), 2, false, List.of(0), -1, null);
        List<List<String>> calls = capturedArgvs(1);
        assertThat(keySeq(calls)).containsExactly("Enter");
        assertNoUp(calls);
    }

    @Test
    void uc57_two_option_index1_sends_one_Down_then_Enter_zero_Up() throws Exception {
        // 2 options, pick the SECOND (index 1): exactly one Down then Enter — never index 0.
        svc.injectAnswer(3, InjectTarget.main(), 2, false, List.of(1), -1, null);
        List<List<String>> calls = capturedArgvs(2);
        assertThat(keySeq(calls)).containsExactly("Down", "Enter");
        assertNoUp(calls);
    }

    @Test
    void uc57_multiSelect_non_first_only_has_zero_leading_Up() throws Exception {
        // 3-option multiSelect picking only index 2 (a non-first option): Down past 0 and 1 (no toggle),
        // Space on 2, Enter — zero Up.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(2), -1, null);
        List<List<String>> calls = capturedArgvs(4);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Space", "Enter");
        assertNoUp(calls);
    }

    @Test
    void uc57_free_text_other_non_first_index_has_zero_leading_Up() throws Exception {
        // 4-option single-select free-text where the "Other" row is the LAST index (3): walk Down×3 to
        // it, type, Enter — zero Up, and the typed text round-trips verbatim (AC4).
        svc.injectAnswer(3, InjectTarget.main(), 4, false, List.of(3), 3, "typed value");
        List<List<String>> calls = capturedArgvs(5);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "Down", "LIT:typed value", "Enter");
        assertNoUp(calls);
    }

    /** UC-57 guard: the single-question answer path must NEVER emit a blind Up cursor reset. */
    private static void assertNoUp(List<List<String>> calls) {
        assertThat(calls.stream().filter(c -> "Up".equals(tail(c, 1))).count())
                .as("UC-57: zero Up — the wrapping-ring reset that sent the first/wrong option is gone")
                .isZero();
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

    // ──────────────── UC-75 — newline-safe "Other" free-text injection ────────────────
    //
    // THE bug: the answer free-text path injected the "Other" text RAW (sendLiteral), so an
    // embedded newline landed as a bare Enter on the "Type something" row, which COMMITS/DECLINES
    // the ask early → blocked prompt → stuck spinner. The fix routes every free-text branch through
    // typeMultiline (the verified composer line-walk): split on '\n', emit each non-empty segment as
    // a LITERAL, insert C-j (LF, NOT submit) BETWEEN segments, and send NO trailing submit (the
    // caller still owns the committing Enter / Enter-Space-Tab choreography). The executor is MOCKED,
    // so every test pins the EXACT ordered keystroke sequence AND asserts no '\n' ever rides inside a
    // single LIT token and no premature Enter sits between segments.

    /** UC-75 guard: no captured literal (`-l -- <text>`) may contain a raw newline. */
    private static void assertNoRawNewlineInLiterals(List<List<String>> calls) {
        assertThat(calls)
                .as("UC-75: a raw '\\n' inside a LIT would land as a bare Enter → commit/decline the ask")
                .allSatisfy(c -> {
                    if (c.contains("-l")) {
                        assertThat(c.get(c.size() - 1)).doesNotContain("\n");
                    }
                });
    }

    @Test
    void uc75_single_select_other_multiline_walks_Cj_between_lines_no_raw_newline_no_premature_Enter()
            throws Exception {
        // AC2/AC3 — single-select free-text "Other" (otherIndex=2) with a two-line answer. The walk
        // is Down×2 to the "Type something" row, then typeMultiline: LIT(line a), C-j, LIT(line b),
        // and ONE final committing Enter. The newline is delivered as C-j (NOT a bare Enter), so the
        // ask is committed exactly once with the full multi-line text.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "line a\nline b");

        List<List<String>> calls = capturedArgvs(6);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:line a", "C-j", "LIT:line b", "Enter");
        assertNoRawNewlineInLiterals(calls);
        // Exactly ONE Enter — the single commit. No premature Enter between the two segments.
        assertThat(calls.stream().filter(c -> "Enter".equals(tail(c, 1))).count())
                .as("AC2: the multi-line answer commits exactly once")
                .isEqualTo(1);
        assertThat(calls.stream().filter(c -> "C-j".equals(tail(c, 1))).count())
                .as("AC2: one C-j inserts the single interior newline without submitting")
                .isEqualTo(1);
    }

    @Test
    void uc75_single_select_other_singleline_unchanged_no_Cj() throws Exception {
        // AC1 — a single-LINE "Other" answer behaves exactly as before: walk + LIT + Enter, with NO
        // C-j (nothing to separate). Regression guard so the newline-safe rewrite did not perturb the
        // common single-line path.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "just one line");

        List<List<String>> calls = capturedArgvs(4);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:just one line", "Enter");
        assertThat(calls.stream().filter(c -> "C-j".equals(tail(c, 1))).count())
                .as("a single-line answer emits no C-j")
                .isZero();
    }

    @Test
    void uc75_single_select_other_blank_interior_line_skips_literal_but_keeps_Cj() throws Exception {
        // Pitfall — a blank/whitespace-only INTERIOR line must be skipped for CONTENT but still
        // advance with C-j (identical to injectComposer). "a\n\nb" → LIT(a), C-j, C-j, LIT(b): the
        // empty middle segment emits NO literal and NO Enter (a stray Enter on an empty segment would
        // decline the ask — the exact failure this UC fixes).
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "a\n\nb");

        List<List<String>> calls = capturedArgvs(7);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:a", "C-j", "C-j", "LIT:b", "Enter");
        assertNoRawNewlineInLiterals(calls);
        assertThat(calls.stream().filter(c -> "Enter".equals(tail(c, 1))).count())
                .as("no stray Enter on the empty interior segment — only the one committing Enter")
                .isEqualTo(1);
    }

    @Test
    void uc75_single_question_multiSelect_other_multiline_types_Cj_then_commit_choreography() throws Exception {
        // AC2/AC3 — single-question multiSelect + "Other" (the injectAnswer review-confirm path) with a
        // multi-line custom answer. Option 0 toggled, then at the "Type something" row (otherIndex=2)
        // the text is typed newline-safe: LIT(line a), C-j, LIT(line b), then the UC-44 commit dance
        // (Enter commits the typed option, Space selects it, Tab focuses + Enter activates the in-pane
        // button, and a final Enter confirms the single-question review screen). The interior newline
        // rides as C-j — never a bare Enter that would decline the ask mid-type.
        svc.injectAnswer(3, InjectTarget.main(), 3, true, List.of(0, 2), 2, "line a\nline b");

        List<List<String>> calls = capturedArgvs(11);
        assertThat(keySeq(calls))
                .containsExactly(
                        "Space",
                        "Down",
                        "Down",
                        "LIT:line a",
                        "C-j",
                        "LIT:line b",
                        "Enter",
                        "Space",
                        "Tab",
                        "Enter",
                        "Enter");
        assertNoRawNewlineInLiterals(calls);
        // The literal segments are sent verbatim, each as a single argv element.
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "line a"));
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "line b"));
    }

    @Test
    void uc75_batch_single_select_other_multiline_is_newline_safe() throws Exception {
        // AC3 — the BATCH path (commitKey == "Tab") single-select "Other" with a multi-line answer.
        // Down×2 to the row, typeMultiline (LIT, C-j, LIT), the per-question committing Enter, then
        // injectAnswerBatch's ONE trailing Enter submits the sheet. No raw newline, no premature Enter.
        svc.injectAnswerBatch(
                3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, false, List.of(2), 2, "line a\nline b")));

        List<List<String>> calls = capturedArgvs(7);
        assertThat(keySeq(calls)).containsExactly("Down", "Down", "LIT:line a", "C-j", "LIT:line b", "Enter", "Enter");
        assertNoRawNewlineInLiterals(calls);
    }

    @Test
    void uc75_batch_multiSelect_other_multiline_is_newline_safe() throws Exception {
        // AC2/AC3 — THE load-bearing cell: the BATCH multiSelect + "Other" UC-44 choreography with a
        // multi-line custom answer. Space on option 0, walk Down to the "Type something" row, type the
        // text newline-safe (LIT, C-j, LIT), Enter (commit the typed option), Space (select it), Tab
        // (focus the in-pane button) + Enter (activate it), then the batch's ONE trailing Enter submits.
        // The interior newline is C-j — never the bare Enter that the raw-inject bug produced.
        svc.injectAnswerBatch(
                3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, true, List.of(0, 2), 2, "line a\nline b")));

        List<List<String>> calls = capturedArgvs(11);
        assertThat(keySeq(calls))
                .containsExactly(
                        "Space",
                        "Down",
                        "Down",
                        "LIT:line a",
                        "C-j",
                        "LIT:line b",
                        "Enter",
                        "Space",
                        "Tab",
                        "Enter",
                        "Enter");
        assertNoRawNewlineInLiterals(calls);
        // The newline must NOT have been injected as a raw multi-line literal in one shot.
        assertThat(calls).noneSatisfy(c -> assertThat(c).containsSequence("-l", "--", "line a\nline b"));
    }

    @Test
    void uc75_batch_multiSelect_other_three_line_answer_emits_two_Cj() throws Exception {
        // AC2 — a THREE-line "Other" answer in the batch multiSelect path inserts exactly TWO C-j
        // (one per interior boundary) and never a bare Enter between segments. Guards the loop bound
        // (C-j between, not after, the last line) at >2 lines.
        svc.injectAnswerBatch(
                3, InjectTarget.main(), List.of(new BatchAnswerSpec(3, true, List.of(0, 2), 2, "a\nb\nc")));

        List<List<String>> calls = capturedArgvs(13);
        assertThat(keySeq(calls))
                .containsExactly(
                        "Space", "Down", "Down", "LIT:a", "C-j", "LIT:b", "C-j", "LIT:c", "Enter", "Space", "Tab",
                        "Enter", "Enter");
        assertThat(calls.stream().filter(c -> "C-j".equals(tail(c, 1))).count())
                .as("two interior newlines → exactly two C-j")
                .isEqualTo(2);
        assertNoRawNewlineInLiterals(calls);
    }

    // ──────────────────────── UC-55 — read-only wizard-tab recovery walk ──────
    // The multi-question option recovery steps the live pane to READ each tab's options.
    // LOCKED CONDITION 3 (the load-bearing safety invariant): the walk may emit ONLY the
    // navigation arrows Right/Left — NEVER a selection/commit key — so the transient
    // flicker it causes is provably non-corrupting (it cannot toggle, type, or submit an
    // answer). These tests pin that the recovery keystroke set is exactly {Right, Left}
    // and that the step helpers emit only those keys.

    @Test
    void WIZARD_NAV_KEYS_is_exactly_Right_and_Left_and_contains_no_selection_or_commit_key() {
        // CONDITION 3 — the recovery walk's allowed keystroke set must be navigation-only.
        assertThat(InputInjectionService.WIZARD_NAV_KEYS)
                .containsExactlyInAnyOrder("Right", "Left")
                .containsExactlyInAnyOrder(
                        InputInjectionService.WIZARD_NEXT_TAB_KEY, InputInjectionService.WIZARD_PREV_TAB_KEY);
        // Not one of the selection/commit keys the answer-injection paths use — a single
        // leaked key here would corrupt the user's unanswered wizard.
        assertThat(InputInjectionService.WIZARD_NAV_KEYS)
                .doesNotContain("Space", "Enter", "Tab", "C-j", "Up", "Down")
                .doesNotContain("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    void stepWizardForward_sends_only_the_Right_arrow() throws Exception {
        svc.stepWizardForward(3, InjectTarget.main());
        List<List<String>> calls = capturedArgvs(1);
        assertThat(calls.get(0)).containsSubsequence("send-keys", "-t", "main");
        assertThat(tail(calls.get(0), 1)).isEqualTo("Right");
        // Provably NOT a literal type and NOT a selection/commit key.
        assertThat(calls.get(0)).doesNotContain("-l");
        assertThat(keySeq(calls)).containsExactly("Right");
    }

    @Test
    void stepWizardBack_sends_only_the_Left_arrow() throws Exception {
        svc.stepWizardBack(3, InjectTarget.main());
        List<List<String>> calls = capturedArgvs(1);
        assertThat(tail(calls.get(0), 1)).isEqualTo("Left");
        assertThat(calls.get(0)).doesNotContain("-l");
        assertThat(keySeq(calls)).containsExactly("Left");
    }

    @Test
    void a_full_recovery_walk_emits_only_navigation_keys_never_a_selection_or_commit_key() throws Exception {
        // Simulate the facade's recovery of a 3-tab wizard: Right ×2 to read tabs 1 and 2,
        // then Left ×2 to restore focus to tab 0. The ENTIRE keystroke trace must be
        // navigation-only — the strongest expression of CONDITION 3 at the service surface.
        svc.stepWizardForward(3, InjectTarget.main()); // → tab 1
        svc.stepWizardForward(3, InjectTarget.main()); // → tab 2
        svc.stepWizardBack(3, InjectTarget.main()); // ← tab 1
        svc.stepWizardBack(3, InjectTarget.main()); // ← tab 0 (restored)

        List<List<String>> calls = capturedArgvs(4);
        assertThat(keySeq(calls)).containsExactly("Right", "Right", "Left", "Left");
        // No literal sends anywhere in the walk.
        assertThat(calls).allSatisfy(c -> assertThat(c).doesNotContain("-l"));
    }

    // ──────────── UC-98 (Bug 1) — spawn-scoped gate→type→confirm→exactly-once Enter ────────────

    /** The fixed workspace-project setup prompt shape the spawn path injects. */
    private static final String SPAWN_TEXT = "We will work in the project cool-folder.";

    /** Capture EVERY argv run() handed the executor, in call order (no fixed-count assertion). */
    @SuppressWarnings("unchecked")
    private List<List<String>> allArgvsInOrder() throws Exception {
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        verify(exec, atLeast(1)).run(cap.capture(), any(), any(Duration.class));
        return cap.getAllValues();
    }

    private static boolean isCapturePane(List<String> argv) {
        return argv.contains("capture-pane");
    }

    private static boolean isLiteralSend(List<String> argv) {
        return argv.contains("-l");
    }

    private static boolean isNamedKey(List<String> argv, String key) {
        return !argv.contains("-l")
                && argv.contains("send-keys")
                && argv.get(argv.size() - 1).equals(key);
    }

    private static int firstIndex(List<List<String>> calls, Predicate<List<String>> p) {
        for (int i = 0; i < calls.size(); i++) {
            if (p.test(calls.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static long count(List<List<String>> calls, Predicate<List<String>> p) {
        return calls.stream().filter(p).count();
    }

    /**
     * Happy path — the choreography is strictly gate → type → confirm → EXACTLY ONE Enter.
     * The fake pane starts showing the ready marker; once the literal is typed the fake echoes it
     * back, so the confirm poll succeeds only AFTER the type. Proves the exactly-once submit
     * contract (AC4): the single Enter is emitted last, after the typed literal is confirmed on the
     * pane.
     */
    @Test
    void spawn_inject_gates_then_types_then_confirms_then_sends_exactly_one_Enter() throws Exception {
        StringBuilder pane = new StringBuilder("Welcome to Claude Code\n  ? for shortcuts");
        when(exec.run(any(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isCapturePane(argv)) {
                return new ProcessExecutor.Result(0, pane.toString(), "");
            }
            if (isLiteralSend(argv)) {
                // The TUI echoes the typed literal onto the input line — model that.
                pane.append('\n').append(argv.get(argv.size() - 1));
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SpawnInjectOutcome outcome = svc.injectSpawnPrompt(7, InjectTarget.main(), SPAWN_TEXT);

        assertThat(outcome).isEqualTo(SpawnInjectOutcome.SUBMITTED);

        List<List<String>> calls = allArgvsInOrder();
        int firstGate = firstIndex(calls, InputInjectionServiceTest::isCapturePane);
        int literalIdx =
                firstIndex(calls, c -> isLiteralSend(c) && c.get(c.size() - 1).equals(SPAWN_TEXT));
        int enterIdx = firstIndex(calls, c -> isNamedKey(c, "Enter"));

        // The literal was typed exactly once and submitted with exactly one Enter (exactly-once).
        assertThat(count(calls, c -> isLiteralSend(c) && c.get(c.size() - 1).equals(SPAWN_TEXT)))
                .isEqualTo(1);
        assertThat(count(calls, c -> isNamedKey(c, "Enter"))).isEqualTo(1);

        // Order: a GATE capture-pane precedes the type; the type precedes the single Enter.
        assertThat(firstGate).isGreaterThanOrEqualTo(0);
        assertThat(firstGate).isLessThan(literalIdx);
        assertThat(literalIdx).isLessThan(enterIdx);

        // A CONFIRM capture-pane sits between the type and the Enter (never a blind submit).
        boolean confirmBetween = false;
        for (int i = literalIdx + 1; i < enterIdx; i++) {
            if (isCapturePane(calls.get(i))) {
                confirmBetween = true;
                break;
            }
        }
        assertThat(confirmBetween)
                .withFailMessage("a confirm capture-pane must precede the Enter — the submit is never blind")
                .isTrue();

        // The Enter is the FINAL send-keys — nothing is typed or submitted after it.
        assertThat(enterIdx).isEqualTo(calls.size() - 1);
    }

    /**
     * Gate timeout — the input prompt never becomes ready, so NOTHING is typed and NO Enter is
     * sent (zero submits). Outcome is {@link SpawnInjectOutcome#PROMPT_NOT_READY}. The fake pane
     * never shows the ready marker and interrupts the poll thread so the bounded gate loop exits
     * promptly (exercising the real {@code pollPaneUntil} exit path — the outcome is identical to a
     * wall-clock timeout) rather than blocking for the full 30s gate window.
     */
    @Test
    void spawn_inject_gate_timeout_types_nothing_and_sends_zero_Enter() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isCapturePane(argv)) {
                Thread.currentThread().interrupt(); // force the bounded poll to exit now
                return new ProcessExecutor.Result(0, "Loading Claude Code…", ""); // no ready marker
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SpawnInjectOutcome outcome = svc.injectSpawnPrompt(7, InjectTarget.main(), SPAWN_TEXT);
        Thread.interrupted(); // clear the interrupt flag so it can't leak into later tests

        assertThat(outcome).isEqualTo(SpawnInjectOutcome.PROMPT_NOT_READY);

        List<List<String>> calls = allArgvsInOrder();
        // Zero typing, zero submit — the exactly-once guarantee's failure branch sends no keys.
        assertThat(count(calls, InputInjectionServiceTest::isLiteralSend)).isZero();
        assertThat(count(calls, c -> isNamedKey(c, "Enter"))).isZero();
        // Only capture-pane (gate probe) calls were made.
        assertThat(calls).allSatisfy(c -> assertThat(isCapturePane(c)).isTrue());
    }

    /**
     * Confirm timeout — the prompt is ready (gate passes) and the literal IS typed, but it never
     * echoes onto the pane, so NO Enter is sent (never a blind submit). Outcome is
     * {@link SpawnInjectOutcome#TYPE_NOT_CONFIRMED}. The fake keeps the ready marker (so the gate
     * passes on its first poll, before any sleep) but never appends the typed literal, and
     * interrupts the poll thread so the confirm loop exits promptly.
     */
    @Test
    void spawn_inject_confirm_timeout_types_but_sends_zero_Enter() throws Exception {
        when(exec.run(any(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isCapturePane(argv)) {
                // Ready marker present (gate passes) but the typed literal is never echoed.
                Thread.currentThread().interrupt();
                return new ProcessExecutor.Result(0, "Welcome to Claude Code\n  ? for shortcuts", "");
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        SpawnInjectOutcome outcome = svc.injectSpawnPrompt(7, InjectTarget.main(), SPAWN_TEXT);
        Thread.interrupted(); // clear the interrupt flag

        assertThat(outcome).isEqualTo(SpawnInjectOutcome.TYPE_NOT_CONFIRMED);

        List<List<String>> calls = allArgvsInOrder();
        // The literal WAS typed (gate passed) …
        assertThat(count(calls, c -> isLiteralSend(c) && c.get(c.size() - 1).equals(SPAWN_TEXT)))
                .isEqualTo(1);
        // … but it was NEVER submitted — zero Enter on the confirm-timeout branch.
        assertThat(count(calls, c -> isNamedKey(c, "Enter"))).isZero();
    }

    /** A blank/empty spawn prompt is a no-op: nothing is captured, typed, or submitted. */
    @Test
    void spawn_inject_empty_text_is_a_noop_submitted() throws Exception {
        SpawnInjectOutcome outcome = svc.injectSpawnPrompt(7, InjectTarget.main(), "");

        assertThat(outcome).isEqualTo(SpawnInjectOutcome.SUBMITTED);
        verify(exec, times(0)).run(any(), any(), any(Duration.class));
    }

    /**
     * The gate uses {@code capture-pane} on the SAME argv-only executor surface as {@code
     * send-keys} — pure argv, no shell — so the probe can never smuggle a second command. Also
     * pins that the ready-marker constant is the one the gate matches.
     */
    @Test
    void spawn_inject_capture_pane_is_argv_only_and_uses_the_pinned_ready_marker() throws Exception {
        StringBuilder pane = new StringBuilder("boot\n" + InputInjectionService.PROMPT_READY_MARKER);
        when(exec.run(any(), any(), any(Duration.class))).thenAnswer(inv -> {
            List<String> argv = inv.getArgument(0);
            if (isCapturePane(argv)) {
                return new ProcessExecutor.Result(0, pane.toString(), "");
            }
            if (isLiteralSend(argv)) {
                pane.append('\n').append(argv.get(argv.size() - 1));
            }
            return new ProcessExecutor.Result(0, "", "");
        });

        svc.injectSpawnPrompt(9, InjectTarget.main(), SPAWN_TEXT);

        List<List<String>> calls = allArgvsInOrder();
        List<String> gate = calls.get(firstIndex(calls, InputInjectionServiceTest::isCapturePane));
        // argv-only capture-pane: no shell wrapper, targets the resolved spec.
        assertThat(gate).containsSubsequence("capture-pane", "-p", "-t", "main");
        assertThat(gate).doesNotContain("bash", "sh", "-c", ";", "&&");
        // The marker the production gate polls for is the pinned footer hint.
        assertThat(InputInjectionService.PROMPT_READY_MARKER).isEqualTo("for shortcuts");
    }
}
