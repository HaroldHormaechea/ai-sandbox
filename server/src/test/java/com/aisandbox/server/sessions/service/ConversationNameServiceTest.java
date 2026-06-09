package com.aisandbox.server.sessions.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * UC-47 — unit coverage for {@link ConversationNameService}, the cached + async
 * conversation-name source that keeps the {@code GET /v1/sessions} enumeration
 * off any per-session blocking derive (AC6).
 *
 * <h2>What is asserted</h2>
 * <ul>
 *   <li>AC6 — {@link ConversationNameService#cachedName(int)} is a non-blocking
 *       O(1) map read; {@link ConversationNameService#refreshAsync(int, String)}
 *       NEVER runs the derive inline (a blocked executor does not block the
 *       caller) and dedups per-{@code n} (a second resubmit while one is in
 *       flight enqueues no second task).</li>
 *   <li>AC1 — a successful derive warms the cache; the next {@code cachedName}
 *       returns it.</li>
 *   <li>AC3 — a derive failure (non-zero exit, IOException) or empty/blank
 *       output never poisons the cache (no blank name is stored) and clears a
 *       previously-warmed stale entry.</li>
 *   <li>AC5 — the server re-caps a pathologically long derived name at 120
 *       codepoints (surrogate-safe).</li>
 *   <li>Hygiene — the derive argv mirrors the other docker-exec call sites
 *       ({@code docker compose -p <project> exec -T claude-sandbox
 *       aisandbox-conversation-tail --conversation-name}); {@code prune} drops
 *       vanished sessions; a blank project is a no-op.</li>
 * </ul>
 *
 * <p>The service owns a real bounded {@link java.util.concurrent.ThreadPoolExecutor},
 * so cache-warming assertions poll via Awaitility rather than sleeping.
 */
class ConversationNameServiceTest {

    private static final Duration POLL = Duration.ofSeconds(3);

    // ── pure helpers ─────────────────────────────────────────────────────────

    @Test
    void firstLine_returns_first_line_or_whole_string_or_null() {
        assertThat(ConversationNameService.firstLine("alpha\nbeta")).isEqualTo("alpha");
        assertThat(ConversationNameService.firstLine("only")).isEqualTo("only");
        assertThat(ConversationNameService.firstLine(null)).isNull();
        assertThat(ConversationNameService.firstLine("")).isEmpty();
    }

    @Test
    void trimToNull_strips_and_nulls_blank() {
        assertThat(ConversationNameService.trimToNull("  kept  ")).isEqualTo("kept");
        assertThat(ConversationNameService.trimToNull("   ")).isNull();
        assertThat(ConversationNameService.trimToNull(null)).isNull();
    }

    @Test
    void capCodepoints_is_surrogate_safe() {
        assertThat(ConversationNameService.capCodepoints("abcdef", 3)).isEqualTo("abc");
        assertThat(ConversationNameService.capCodepoints("abc", 9)).isEqualTo("abc");
        // 4 astral-plane emoji (each 2 UTF-16 units, 1 codepoint) capped at 2 →
        // exactly 2 whole emoji, no lone surrogate.
        String emoji = "😀😁😂😃";
        String capped = ConversationNameService.capCodepoints(emoji, 2);
        assertThat(capped.codePointCount(0, capped.length())).isEqualTo(2);
        assertThat(capped).isEqualTo("😀😁");
    }

    // ── cachedName / refresh / cache-warming ──────────────────────────────────

    @Test
    void cachedName_is_null_before_any_refresh() {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            assertThat(svc.cachedName(1)).isNull();
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void refreshAsync_warms_the_cache_with_the_derived_name() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Refactor the SessionRow\n", ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(3, "ai-sandbox-3");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(3)).isEqualTo("Refactor the SessionRow"));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void refreshAsync_is_a_noop_for_a_blank_or_null_project() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(1, "");
            svc.refreshAsync(1, "   ");
            svc.refreshAsync(1, null);
            // Give any erroneously-scheduled task a chance to run before asserting.
            TimeUnit.MILLISECONDS.sleep(150);
            verify(exec, never()).run(any(), any(), any(), any());
            assertThat(svc.cachedName(1)).isNull();
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void refreshAsync_does_not_run_the_derive_inline_and_dedups_per_n() throws Exception {
        // The executor blocks until released, modelling a slow docker exec. If
        // refreshAsync ran inline the caller would block here; it must not.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new ProcessExecutor.Result(0, "late-name", "");
        });

        ConversationNameService svc = new ConversationNameService(exec);
        try {
            long t0 = System.nanoTime();
            svc.refreshAsync(7, "ai-sandbox-7");
            // Caller returned promptly — the derive runs on the pool, not inline.
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
            // Cache is still cold while the (blocked) derive is in flight (AC6 — the
            // hot path reads null and the row falls back to tmuxTitle this tick).
            assertThat(svc.cachedName(7)).isNull();

            // Wait until the first task is actually running, then resubmit for the
            // SAME n — the in-flight marker must dedup it (no second task).
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            svc.refreshAsync(7, "ai-sandbox-7");
            svc.refreshAsync(7, "ai-sandbox-7");

            release.countDown();
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(7)).isEqualTo("late-name"));
            // Exactly one derive ran despite three refreshAsync calls for n=7.
            verify(exec, times(1)).run(any(), any(), any(), any());
        } finally {
            release.countDown();
            svc.shutdown();
        }
    }

    @Test
    void a_nonzero_exit_preserves_a_warmed_name_and_never_stores_blank() throws Exception {
        // UC-48 behavior change (was: "non-zero exit CLEARS the stale entry").
        // An exec FAILURE (exit≠0) is now a transient blip the service rides out:
        // derive() returns null and the refresh task touches NEITHER cache, so a
        // momentary docker hiccup no longer drops a perfectly good conversation
        // name (it is refreshed on the next successful tick). It also never stores
        // a blank. Clearing a name is now reserved for a SUCCESSFUL-but-empty
        // derive (see empty_successful_derive_clears_a_stale_entry).
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "warm-name", ""))
                .thenReturn(new ProcessExecutor.Result(1, "", "boom")); // exec failure
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(2)).isEqualTo("warm-name"));

            // A later FAILED derive leaves the warmed name in place (touch-nothing).
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> verify(exec, times(2)).run(any(), any(), any(), any()));
            assertThat(svc.cachedName(2))
                    .as("UC-48 — an exec failure must NOT drop a good name")
                    .isEqualTo("warm-name");
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void empty_successful_derive_clears_a_stale_entry() throws Exception {
        // UC-48 — a SUCCESSFUL derive whose name is empty/blank is the path that
        // clears a previously-warmed entry (the session genuinely has no name now);
        // it still never stores a blank value. (Distinct from a non-zero exit,
        // which preserves — see a_nonzero_exit_preserves_a_warmed_name_*.)
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "warm-name", ""))
                .thenReturn(new ProcessExecutor.Result(0, "   \n", "")); // success, blank name
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(2)).isEqualTo("warm-name"));

            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(2)).isNull());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void empty_output_yields_no_name() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "   \n", ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(4, "ai-sandbox-4");
            // Let the task run; an empty derive must leave the cache cold.
            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(svc.cachedName(4)).isNull();
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void an_ioexception_during_derive_never_poisons_the_cache() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenThrow(new IOException("exec timeout"));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(5, "ai-sandbox-5");
            TimeUnit.MILLISECONDS.sleep(200);
            assertThat(svc.cachedName(5)).isNull();
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void server_recaps_a_pathologically_long_name_at_120_codepoints() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        String longName = "z".repeat(500);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, longName, ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(6, "ai-sandbox-6");
            await().atMost(POLL).untilAsserted(() -> {
                String cached = svc.cachedName(6);
                assertThat(cached).isNotNull();
                assertThat(cached.codePointCount(0, cached.length()))
                        .isEqualTo(ConversationNameService.MAX_NAME_CODEPOINTS);
            });
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void derive_uses_the_compose_exec_argv_with_the_conversation_name_flag() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "n", ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(9, "ai-sandbox-9");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(9)).isEqualTo("n"));
            // Mirrors the tmuxTitle / readiness exec shape, plus the one-shot flag.
            verify(exec)
                    .run(
                            argThat(argv -> argv != null
                                    && argv.contains("docker")
                                    && argv.contains("compose")
                                    && argv.contains("-p")
                                    && argv.contains("ai-sandbox-9")
                                    && argv.contains("exec")
                                    && argv.contains("-T")
                                    && argv.contains("claude-sandbox")
                                    && argv.contains(ConversationNameService.HELPER)
                                    && argv.contains(ConversationNameService.CONVERSATION_NAME_FLAG)),
                            any(),
                            argThat(env -> env != null && "C".equals(env.get("LC_ALL"))),
                            eq(ConversationNameService.REFRESH_TIMEOUT));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void prune_drops_cache_entries_for_sessions_that_vanished() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "alive", ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(1, "ai-sandbox-1");
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> {
                assertThat(svc.cachedName(1)).isEqualTo("alive");
                assertThat(svc.cachedName(2)).isEqualTo("alive");
            });

            // Session 2 vanished — only 1 remains enumerated.
            svc.prune(Set.of(1));
            assertThat(svc.cachedName(1)).isEqualTo("alive");
            assertThat(svc.cachedName(2)).isNull();

            // A null active-set is a defensive no-op.
            svc.prune(null);
            assertThat(svc.cachedName(1)).isEqualTo("alive");
        } finally {
            svc.shutdown();
        }
    }

    // ── UC-48 — working-signal helpers (pure) ─────────────────────────────────

    @Test
    void secondLine_returns_the_second_newline_delimited_line_or_null() {
        assertThat(ConversationNameService.secondLine("name\nworking")).isEqualTo("working");
        assertThat(ConversationNameService.secondLine("name\nidle\ntrailing")).isEqualTo("idle");
        // Blank first line is fine — the working flag still rides line 2.
        assertThat(ConversationNameService.secondLine("\nworking")).isEqualTo("working");
        // Single-line (legacy pre-UC-48 helper output) → no second line.
        assertThat(ConversationNameService.secondLine("only-one-line")).isNull();
        assertThat(ConversationNameService.secondLine(null)).isNull();
    }

    @Test
    void parseWorking_only_the_exact_working_token_is_true() {
        assertThat(ConversationNameService.parseWorking("working")).isTrue();
        assertThat(ConversationNameService.parseWorking("  WORKING  ")).isTrue();
        assertThat(ConversationNameService.parseWorking("idle")).isFalse();
        assertThat(ConversationNameService.parseWorking("")).isFalse();
        assertThat(ConversationNameService.parseWorking(null)).isFalse();
        // Conservative — any unexpected token reads as idle (no stuck spinner).
        assertThat(ConversationNameService.parseWorking("working ")).isTrue();
        assertThat(ConversationNameService.parseWorking("workingish")).isFalse();
    }

    // ── UC-48 — working-signal hysteresis (deterministic injected clock) ──────

    /**
     * UC-48 hysteresis (b)+(c) — a {@code working=true} derivation stamps the
     * OFF-window timestamp, and {@link ConversationNameService#working(int)}
     * reports {@code true} only WHILE within {@link ConversationNameService#OFF_WINDOW_NANOS}
     * of that stamp, then ages out to {@code false}. The injected monotonic clock
     * makes the boundary deterministic (no sleeps).
     */
    @Test
    void working_signal_is_true_within_the_off_window_and_false_after() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Refactor the SessionRow\nworking\n", ""));
        AtomicLong now = new AtomicLong(1_000_000_000L);
        LongSupplier clock = now::get;
        ConversationNameService svc = new ConversationNameService(exec, clock);
        try {
            assertThat(svc.working(3)).as("no derivation yet → not working").isFalse();

            svc.refreshAsync(3, "ai-sandbox-3");
            // The clock is frozen at the start value while we wait, so once the
            // async derive stamps the timestamp, working(3) reads true immediately.
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.working(3)).isTrue());
            // The name rode the SAME derivation (independent of the working flag).
            assertThat(svc.cachedName(3)).isEqualTo("Refactor the SessionRow");

            // Just inside the OFF-window → still working (debounce holds the spinner).
            now.set(1_000_000_000L + ConversationNameService.OFF_WINDOW_NANOS - 1L);
            assertThat(svc.working(3)).as("inside the OFF-window stays working").isTrue();

            // Past the OFF-window → idle (the spinner finally turns off).
            now.set(1_000_000_000L + ConversationNameService.OFF_WINDOW_NANOS + 1L);
            assertThat(svc.working(3))
                    .as("past the OFF-window ages out to idle")
                    .isFalse();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-48 hysteresis (a) — an exec FAILURE (null SessionSignals) touches
     * NEITHER cache: a previously-warmed name survives AND the working timestamp
     * keeps aging on its own (a transient docker blip never strobes the spinner
     * off nor drops a good name — AC3/AC4).
     */
    @Test
    void an_exec_failure_leaves_name_and_working_untouched() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "warm-name\nworking\n", ""))
                .thenReturn(new ProcessExecutor.Result(1, "", "boom")); // exec failure
        AtomicLong now = new AtomicLong(5_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> {
                assertThat(svc.cachedName(2)).isEqualTo("warm-name");
                assertThat(svc.working(2)).isTrue();
            });

            // Second derive fails — wait until it has actually run.
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> verify(exec, times(2)).run(any(), any(), any(), any()));

            // Both signals are exactly as the successful derive left them (clock
            // unchanged, so the timestamp is still inside the OFF-window).
            assertThat(svc.cachedName(2))
                    .as("failure must not clear a good name")
                    .isEqualTo("warm-name");
            assertThat(svc.working(2))
                    .as("failure must not strobe the spinner off")
                    .isTrue();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-48 hysteresis (d) — name and working are applied INDEPENDENTLY: a
     * success with a blank name still records the working timestamp (so a working
     * session with no derivable name still animates), while the blank name clears
     * the name cache (row falls back to tmuxTitle).
     */
    @Test
    void a_success_with_a_blank_name_still_records_working() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "\nworking\n", ""));
        AtomicLong now = new AtomicLong(9_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(8, "ai-sandbox-8");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.working(8)).isTrue());
            // Blank name → no cached name; the working flag is independent of it.
            assertThat(svc.cachedName(8)).isNull();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-48 hysteresis — a success with {@code working=false} (idle line 2) does
     * NOT stamp the timestamp: a session that was never working stays not-working
     * (and a previously-working one is left to age out, never re-armed by an idle
     * tick).
     */
    @Test
    void an_idle_derivation_does_not_arm_the_working_signal() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "some-name\nidle\n", ""));
        AtomicLong now = new AtomicLong(2_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(4, "ai-sandbox-4");
            // Wait for the derive to land the name, then confirm working stayed false.
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(4)).isEqualTo("some-name"));
            assertThat(svc.working(4))
                    .as("an idle derivation never arms the spinner")
                    .isFalse();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-48 hysteresis (e) — {@code prune} clears the working timestamp for a
     * vanished session, so a re-used session number cannot inherit a stale
     * {@code working=true} from a prior tenant within the OFF-window.
     */
    @Test
    void prune_clears_the_working_state_for_vanished_sessions() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any())).thenReturn(new ProcessExecutor.Result(0, "alive\nworking\n", ""));
        AtomicLong now = new AtomicLong(7_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(1, "ai-sandbox-1");
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> {
                assertThat(svc.working(1)).isTrue();
                assertThat(svc.working(2)).isTrue();
            });

            // Session 2 vanished — only 1 remains enumerated.
            svc.prune(Set.of(1));
            assertThat(svc.working(1))
                    .as("surviving session keeps its working state")
                    .isTrue();
            assertThat(svc.working(2))
                    .as("vanished session's working state is cleared")
                    .isFalse();
        } finally {
            svc.shutdown();
        }
    }

    // ── UC-49 — pending-question helpers (pure) ───────────────────────────────

    @Test
    void thirdLine_returns_the_third_newline_delimited_line_or_null() {
        assertThat(ConversationNameService.thirdLine("name\nworking\npending-question"))
                .isEqualTo("pending-question");
        // A trailing 4th line is ignored — only line 3 is the pending token.
        assertThat(ConversationNameService.thirdLine("name\nidle\nnone\ntrailing")).isEqualTo("none");
        // Pre-UC-49 two-line output (capture-failure path) → no third line.
        assertThat(ConversationNameService.thirdLine("name\nworking")).isNull();
        assertThat(ConversationNameService.thirdLine("only-one-line")).isNull();
        assertThat(ConversationNameService.thirdLine(null)).isNull();
        // A blank first line is fine — the pending token still rides line 3.
        assertThat(ConversationNameService.thirdLine("\nworking\npending-question"))
                .isEqualTo("pending-question");
    }

    @Test
    void parsePending_is_a_conservative_tristate() {
        // "pending-question" ⇒ TRUE (case-insensitive, trimmed).
        assertThat(ConversationNameService.parsePending("pending-question")).isEqualTo(Boolean.TRUE);
        assertThat(ConversationNameService.parsePending("  PENDING-QUESTION  ")).isEqualTo(Boolean.TRUE);
        // "none" ⇒ FALSE.
        assertThat(ConversationNameService.parsePending("none")).isEqualTo(Boolean.FALSE);
        assertThat(ConversationNameService.parsePending("NONE")).isEqualTo(Boolean.FALSE);
        // null / blank / unrecognised ⇒ null = UNKNOWN (retain prior — failure policy (b)).
        assertThat(ConversationNameService.parsePending(null)).isNull();
        assertThat(ConversationNameService.parsePending("")).isNull();
        assertThat(ConversationNameService.parsePending("   ")).isNull();
        assertThat(ConversationNameService.parsePending("garbage")).isNull();
    }

    // ── UC-49 — pending-question signal set/clear + mutual exclusion ──────────

    /**
     * UC-49 AC1/AC5 — a derive whose line 3 is {@code pending-question} sets the
     * pending flag (the row shows the "?" badge). Even though line 2 says
     * {@code working}, {@link ConversationNameService#working(int)} reports
     * {@code false} while pending — the source-level mutual exclusion that
     * guarantees the row never shows the spinner and the "?" at once.
     */
    @Test
    void a_pending_derivation_sets_the_flag_and_suppresses_working() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nworking\npending-question\n", ""));
        AtomicLong now = new AtomicLong(3_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(3, "ai-sandbox-3");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.pendingQuestion(3)).isTrue());
            // The name rode the same derivation.
            assertThat(svc.cachedName(3)).isEqualTo("Pick a database");
            // AC5 — pending OVERRIDES the just-stamped working timestamp.
            assertThat(svc.working(3))
                    .as("AC5 — a pending question is never reported as working")
                    .isFalse();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-49 AC2 — a later derive whose line 3 is {@code none} clears the pending
     * flag (the badge hides) and, the question now answered, the row resumes its
     * normal working/idle behaviour (the working timestamp is unmasked).
     */
    @Test
    void a_none_derivation_clears_the_flag_and_unmasks_working() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nworking\npending-question\n", ""))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nworking\nnone\n", ""));
        AtomicLong now = new AtomicLong(4_000_000_000L);
        ConversationNameService svc = new ConversationNameService(exec, now::get);
        try {
            svc.refreshAsync(5, "ai-sandbox-5");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.pendingQuestion(5)).isTrue());
            assertThat(svc.working(5)).isFalse();

            // Second derive: the question is answered (line 3 "none").
            svc.refreshAsync(5, "ai-sandbox-5");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.pendingQuestion(5)).isFalse());
            // AC2 — with pending cleared and a fresh working stamp inside the
            // OFF-window, the row reads working again.
            assertThat(svc.working(5))
                    .as("AC2 — once the question clears, working resumes")
                    .isTrue();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-49 failure policy (b) — a derive that SUCCEEDS but OMITS line 3 (the
     * helper's 2-line capture-failure output) parses to {@code null} = unknown, so
     * the prior pending value is RETAINED rather than cleared. The badge does not
     * flicker off while a question is genuinely up.
     */
    @Test
    void a_missing_line3_retains_the_prior_pending_value() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nidle\npending-question\n", ""))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nidle\n", "")); // line 3 omitted
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(7, "ai-sandbox-7");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.pendingQuestion(7)).isTrue());

            // Second derive succeeds but omits line 3 → unknown → retain.
            svc.refreshAsync(7, "ai-sandbox-7");
            await().atMost(POLL).untilAsserted(() -> verify(exec, times(2)).run(any(), any(), any(), any()));
            assertThat(svc.pendingQuestion(7))
                    .as("failure policy (b) — a missing line 3 retains the prior pending value")
                    .isTrue();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-49 failure policy (a) — an exec FAILURE (null SessionSignals) touches
     * nothing, so a previously-set pending flag survives a transient docker blip
     * (the badge does not flicker off).
     */
    @Test
    void an_exec_failure_leaves_the_pending_flag_untouched() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "Pick a database\nidle\npending-question\n", ""))
                .thenReturn(new ProcessExecutor.Result(1, "", "boom")); // exec failure
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(8, "ai-sandbox-8");
            await().atMost(POLL).untilAsserted(() -> assertThat(svc.pendingQuestion(8)).isTrue());

            svc.refreshAsync(8, "ai-sandbox-8");
            await().atMost(POLL).untilAsserted(() -> verify(exec, times(2)).run(any(), any(), any(), any()));
            assertThat(svc.pendingQuestion(8))
                    .as("an exec failure must not clear a set pending flag")
                    .isTrue();
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void pendingQuestion_is_false_before_any_refresh() {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            assertThat(svc.pendingQuestion(1)).isFalse();
        } finally {
            svc.shutdown();
        }
    }

    /**
     * UC-49 — {@code prune} clears the pending flag for a vanished session, so a
     * re-used session number cannot inherit a stale "?" from a prior tenant.
     */
    @Test
    void prune_clears_the_pending_flag_for_vanished_sessions() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "alive\nidle\npending-question\n", ""));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(1, "ai-sandbox-1");
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL).untilAsserted(() -> {
                assertThat(svc.pendingQuestion(1)).isTrue();
                assertThat(svc.pendingQuestion(2)).isTrue();
            });

            svc.prune(Set.of(1));
            assertThat(svc.pendingQuestion(1))
                    .as("surviving session keeps its pending flag")
                    .isTrue();
            assertThat(svc.pendingQuestion(2))
                    .as("vanished session's pending flag is cleared")
                    .isFalse();
        } finally {
            svc.shutdown();
        }
    }
}
