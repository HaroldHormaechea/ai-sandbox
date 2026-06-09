package com.aisandbox.server.sessions.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-47 — derives and caches the Claude conversation name for each running
 * session's MAIN pane, off the {@code GET /v1/sessions} enumeration hot path.
 *
 * <h2>Why a cache + async refresh (AC6)</h2>
 *
 * Deriving the name requires a {@code docker compose exec} into the container to
 * read the active transcript — the same class of per-session round-trip that
 * {@code tmuxTitle} already pays. Doing it synchronously inside
 * {@link DockerEnumerationService#enumerate()} would multiply list latency with
 * the session count. Instead this service keeps a non-blocking
 * {@link ConcurrentHashMap} cache: the enumerator reads {@link #cachedName(int)}
 * (a plain map lookup, never blocking) and fires {@link #refreshAsync(int, String)}
 * for the NEXT enumeration. The first enumeration after a name appears returns
 * {@code null} (row falls back to {@code tmuxTitle} — AC3); the subsequent one
 * picks up the warmed value, and the UC-32 watcher's record-equality diff emits a
 * Delta on the change (AC4).
 *
 * <h2>Threading + bounding</h2>
 *
 * Refresh tasks run on a small bounded {@link ThreadPoolExecutor} (4 daemon
 * threads, a ~32-slot queue, discard-on-overflow). A per-{@code n} in-flight set
 * dedups: while a refresh for session {@code n} is queued or running, repeated
 * enumerations don't pile up duplicate work. ANY derivation failure (exec error,
 * timeout, no transcript, empty output) leaves the cache untouched (or clears a
 * stale entry) — a failed lookup never poisons the cache with an empty/garbage
 * name (AC3). {@link #prune(Set)} drops cache entries for sessions that vanished;
 * {@link #shutdown()} stops the pool on context teardown.
 *
 * <p>Layering ({@code profile-java-server-architecture}): this is a sessions-domain
 * SERVICE invoked by {@link DockerEnumerationService} (also a service in the same
 * domain) — a legal same-domain service→service call. It owns no transaction
 * (there is no data store) so it declares no {@code @Transactional}.
 */
@Service
public class ConversationNameService {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationNameService.class);

    /** In-container helper that resolves the active transcript and prints the name. */
    static final String HELPER = "aisandbox-conversation-tail";

    /** One-shot mode flag added to the helper by UC-47. */
    static final String CONVERSATION_NAME_FLAG = "--conversation-name";

    /**
     * Short per-exec timeout. The helper does a one-shot resolve + tail scan and
     * exits; a slow/wedged container must not stall a refresh thread for long.
     */
    static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Server-side codepoint cap (AC5 belongs to the client, but we also bound the
     * payload here so a pathological transcript can't ship a multi-KB "name").
     * Codepoints — not chars — so a name made of astral-plane emoji is not split
     * mid-surrogate.
     */
    static final int MAX_NAME_CODEPOINTS = 120;

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 32;

    private final ProcessExecutor executor;

    /** n → derived conversation name. Absence ⇒ no known name (row falls back). */
    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>();

    /** Sessions with a refresh currently queued or running — dedups resubmits. */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();

    private final ThreadPoolExecutor pool;

    public ConversationNameService(ProcessExecutor executor) {
        this.executor = executor;
        // Discard-on-overflow: under a burst the freshest tasks are simply dropped
        // (the next enumeration re-submits). The handler clears the in-flight marker
        // for a dropped task so that session is not wedged out of future refreshes.
        RejectedExecutionHandler discardAndUnmark = new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor ex) {
                if (r instanceof NameTask nt) {
                    inFlight.remove(nt.n);
                }
                // Otherwise drop silently (queue full or pool shutting down).
            }
        };
        this.pool = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                daemonThreadFactory(),
                discardAndUnmark);
    }

    /**
     * Non-blocking read of the cached name for session {@code n}; {@code null}
     * when none is known. Safe to call on the enumeration hot path.
     */
    public String cachedName(int n) {
        return cache.get(n);
    }

    /**
     * Fire-and-forget: schedule a background refresh of session {@code n}'s
     * conversation name. No-op when {@code project} is blank, when a refresh for
     * {@code n} is already in flight, or when the pool is shutting down. Never
     * blocks and never throws.
     *
     * @param n       session number
     * @param project the compose project name, e.g. {@code ai-sandbox-3}
     */
    public void refreshAsync(int n, String project) {
        if (project == null || project.isBlank()) {
            return;
        }
        if (pool.isShutdown()) {
            return;
        }
        // Per-n dedup: only the first caller to flip the marker enqueues work.
        if (!inFlight.add(n)) {
            return;
        }
        try {
            pool.execute(new NameTask(n, project));
        } catch (RejectedExecutionException rex) {
            // Defensive — the discard handler normally absorbs rejection without
            // throwing, but a hard shutdown race could. Clear the marker either way.
            inFlight.remove(n);
        }
    }

    /**
     * Drop cache + in-flight bookkeeping for sessions no longer present, so a
     * deleted session's name doesn't linger. Called once per enumeration with the
     * set of currently-enumerated session numbers.
     */
    public void prune(Set<Integer> activeNs) {
        if (activeNs == null) {
            return;
        }
        cache.keySet().retainAll(activeNs);
        // In-flight tasks self-clear in their finally; retaining here only trims
        // markers for sessions that vanished mid-derive (harmless if the task later
        // no-ops its remove).
        inFlight.retainAll(activeNs);
    }

    /** Stop the refresh pool on context shutdown. */
    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** A single background name-refresh unit; carries {@code n} so the rejection handler can unmark it. */
    private final class NameTask implements Runnable {
        private final int n;
        private final String project;

        NameTask(int n, String project) {
            this.n = n;
            this.project = project;
        }

        @Override
        public void run() {
            try {
                String name = derive(n, project);
                if (name != null && !name.isBlank()) {
                    cache.put(n, name);
                } else {
                    // No name available — clear any stale entry, but never store an
                    // empty/blank value (AC3: a failed/empty lookup must not poison
                    // the cache; the row falls back to tmuxTitle).
                    cache.remove(n);
                }
            } catch (RuntimeException e) {
                LOG.debug("conversation-name refresh for n={} failed: {}", n, e.toString());
            } finally {
                inFlight.remove(n);
            }
        }
    }

    /**
     * Run the helper one-shot and return the trimmed, codepoint-capped name, or
     * {@code null} on any failure / empty output. {@code LC_ALL=C} keeps the exec
     * environment locale-stable, mirroring the other docker calls.
     */
    private String derive(int n, String project) {
        try {
            List<String> argv = List.of(
                    "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", HELPER, CONVERSATION_NAME_FLAG);
            ProcessExecutor.Result r = executor.run(argv, null, Map.of("LC_ALL", "C"), REFRESH_TIMEOUT);
            if (r.exitCode() != 0) {
                return null;
            }
            return capCodepoints(trimToNull(firstLine(r.stdout())), MAX_NAME_CODEPOINTS);
        } catch (IOException io) {
            // Any I/O failure (exec error, timeout) → no name; do NOT poison the cache.
            LOG.debug("conversation-name derive for n={} project={}: {}", n, project, io.toString());
            return null;
        }
    }

    // ──────────────────────── pure helpers (package-private for unit tests) ────────────────────────

    /** First newline-delimited line of {@code out}, or the whole string if it has no newline; null-safe. */
    static String firstLine(String out) {
        if (out == null) {
            return null;
        }
        int nl = out.indexOf('\n');
        return nl < 0 ? out : out.substring(0, nl);
    }

    /** Strip and return {@code null} for an empty/blank result. */
    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** Truncate to at most {@code maxCodepoints} Unicode codepoints (never splits a surrogate pair). */
    static String capCodepoints(String s, int maxCodepoints) {
        if (s == null || maxCodepoints <= 0) {
            return s;
        }
        int cpCount = s.codePointCount(0, s.length());
        if (cpCount <= maxCodepoints) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxCodepoints);
        return s.substring(0, end);
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger idx = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "conversation-name-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
