package com.aisandbox.server.stream.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.stream.service.InputInjectionService.InjectTarget;
import java.io.IOException;
import java.time.Duration;
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

        List<List<String>> calls = capturedArgvs(/* 20 Up + 2 Down + Enter + literal + Enter */ 25);
        // The free-text literal is sent verbatim as a single argv element.
        assertThat(calls).anySatisfy(c -> assertThat(c).containsSequence("-l", "--", "my custom answer"));
        // …and the very last key submits.
        assertThat(tail(calls.get(calls.size() - 1), 1)).isEqualTo("Enter");
    }

    @Test
    void answer_other_blank_free_text_is_not_typed() throws Exception {
        // Other "selected" but freeText blank → freeText path is skipped.
        svc.injectAnswer(3, InjectTarget.main(), 3, false, List.of(2), 2, "   ");
        List<List<String>> calls = capturedArgvs(/* 20 Up + 2 Down + Enter */ 23);
        assertThat(calls).noneSatisfy(c -> assertThat(c).contains("-l"));
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
}
