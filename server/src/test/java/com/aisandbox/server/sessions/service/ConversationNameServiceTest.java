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
    void a_nonzero_exit_never_poisons_the_cache_and_clears_a_stale_entry() throws Exception {
        ProcessExecutor exec = mock(ProcessExecutor.class);
        // First derive succeeds (warm the cache), then subsequent derives fail.
        when(exec.run(any(), any(), any(), any()))
                .thenReturn(new ProcessExecutor.Result(0, "warm-name", ""))
                .thenReturn(new ProcessExecutor.Result(1, "", "boom"));
        ConversationNameService svc = new ConversationNameService(exec);
        try {
            svc.refreshAsync(2, "ai-sandbox-2");
            await().atMost(POLL)
                    .untilAsserted(() -> assertThat(svc.cachedName(2)).isEqualTo("warm-name"));

            // A later FAILED derive must clear the stale entry, never store blank.
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
}
