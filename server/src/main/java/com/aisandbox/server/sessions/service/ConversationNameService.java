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
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * UC-48 — hysteresis OFF-window. {@link #working(int)} reports {@code true}
     * for this long after the last {@code working=true} derivation, so a brief
     * idle gap between two turns (or one refresh tick that happens to catch a
     * momentary turn-end) does not strobe the spinner off. ~2.5s comfortably
     * spans a single ~1s enumeration cache window plus a refresh round-trip.
     */
    static final Duration OFF_WINDOW = Duration.ofMillis(2500);

    static final long OFF_WINDOW_NANOS = OFF_WINDOW.toNanos();

    private static final int POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 32;

    private final ProcessExecutor executor;

    /**
     * Monotonic clock source (nanoseconds) for the working-signal hysteresis.
     * Defaults to {@link System#nanoTime} in production; injected in unit tests
     * so the OFF-window aging is deterministic. Used ONLY for relative
     * comparisons — never as a wall-clock.
     */
    private final LongSupplier clock;

    /** n → derived conversation name. Absence ⇒ no known name (row falls back). */
    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>();

    /**
     * UC-48 — n → {@code clock.getAsLong()} at the most recent {@code working=true}
     * derivation. {@link #working(int)} reports {@code true} while the entry is
     * younger than {@link #OFF_WINDOW_NANOS}; a {@code working=false} derivation is
     * a no-op (the entry simply ages out), giving the spinner its OFF-debounce.
     */
    private final ConcurrentHashMap<Integer, Long> lastWorkingNanos = new ConcurrentHashMap<>();

    /**
     * UC-49 — n → {@code true} while the session's MAIN pane is showing a pending
     * {@code AskUserQuestion} awaiting an answer. Absence (or {@code false}) ⇒ no
     * pending question, so the row shows no "?" badge. Set/cleared from the helper's
     * line-3 signal; a MISSING line 3 (capture failure) leaves the prior value
     * UNTOUCHED so the badge does not flicker off while a question is genuinely up
     * (failure policy (b)).
     */
    private final ConcurrentHashMap<Integer, Boolean> pending = new ConcurrentHashMap<>();

    /** Sessions with a refresh currently queued or running — dedups resubmits. */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();

    private final ThreadPoolExecutor pool;

    @Autowired
    public ConversationNameService(ProcessExecutor executor) {
        this(executor, System::nanoTime);
    }

    /**
     * UC-48 — package-private constructor that injects the monotonic {@code clock}
     * so unit tests can drive the working-signal hysteresis deterministically.
     * Production wiring uses the public single-arg ctor (Spring) which defaults
     * the clock to {@link System#nanoTime}.
     */
    ConversationNameService(ProcessExecutor executor, LongSupplier clock) {
        this.executor = executor;
        this.clock = clock;
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
     * UC-48 — non-blocking read of the working signal for session {@code n}, with
     * OFF-window hysteresis: {@code true} iff a {@code working=true} derivation
     * landed within the last {@link #OFF_WINDOW_NANOS}. Absence (never working, or
     * aged past the window) ⇒ {@code false}. Safe to call on the enumeration hot
     * path. The DEBOUNCE is OFF-only: turning ON is immediate (the next refresh
     * stamps the timestamp), turning OFF lags by the window so a momentary
     * between-turns idle does not strobe the spinner (AC4 pitfall).
     */
    public boolean working(int n) {
        // UC-49 — source-level mutual exclusion (AC5): a pending question is the
        // session WAITING, never working. This OVERRIDES the OFF-window hysteresis,
        // so a session that was working <2.5s ago and then put up a question reads
        // not-working immediately — the row never shows the spinner and the "?" at once.
        if (pendingQuestion(n)) {
            return false;
        }
        Long ts = lastWorkingNanos.get(n);
        return ts != null && (clock.getAsLong() - ts) < OFF_WINDOW_NANOS;
    }

    /**
     * UC-49 — non-blocking read of the pending-question signal for session {@code n}:
     * {@code true} iff the MAIN pane is showing an {@code AskUserQuestion} awaiting an
     * answer. Absence ⇒ {@code false}. Safe to call on the enumeration hot path.
     */
    public boolean pendingQuestion(int n) {
        return Boolean.TRUE.equals(pending.get(n));
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
        // UC-48 — drop the working timestamp for vanished sessions too, so a
        // re-used session number can't inherit a stale "working" from a prior
        // tenant within the OFF-window.
        lastWorkingNanos.keySet().retainAll(activeNs);
        // UC-49 — drop the pending flag for vanished sessions too, so a re-used
        // session number can't inherit a stale "?" from a prior tenant.
        pending.keySet().retainAll(activeNs);
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
                SessionSignals sig = derive(n, project);
                if (sig == null) {
                    // Exec failure (exit≠0 / timeout / IOException): the derivation
                    // produced no signals at all — touch NOTHING. Both the cached
                    // name and the working timestamp are left exactly as they were, so
                    // a transient blip neither clears a good name (AC3) nor strobes the
                    // spinner (the OFF-window keeps aging on its own).
                    return;
                }
                // Success — apply name and working INDEPENDENTLY.
                if (sig.nameOrNull() != null && !sig.nameOrNull().isBlank()) {
                    cache.put(n, sig.nameOrNull());
                } else {
                    // No name available — clear any stale entry, but never store an
                    // empty/blank value (AC3: a row with no name falls back to tmuxTitle).
                    cache.remove(n);
                }
                if (sig.working()) {
                    // Stamp the most-recent working moment; working(n) ages it out.
                    lastWorkingNanos.put(n, clock.getAsLong());
                }
                // working==false → no-op: let the existing timestamp (if any) age out
                // of the OFF-window, giving the spinner its debounce.
                // UC-49 — apply the pending-question signal (failure policy):
                //   TRUE  → a question is up: set the flag (badge shows).
                //   FALSE → screen has no question: clear the flag (badge hides).
                //   null  → the pane capture failed (helper omitted line 3): leave the
                //           prior value UNTOUCHED so the badge does not flicker off
                //           mid-question (failure policy (b)).
                if (sig.pendingQuestion() != null) {
                    if (sig.pendingQuestion()) {
                        pending.put(n, Boolean.TRUE);
                    } else {
                        pending.remove(n);
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("conversation-name refresh for n={} failed: {}", n, e.toString());
            } finally {
                inFlight.remove(n);
            }
        }
    }

    /**
     * UC-48 — the pair of signals one helper invocation yields. {@code nameOrNull}
     * is the (possibly null/blank) conversation name from line 1; {@code working}
     * is the working/idle flag from line 2. A {@code null} {@link SessionSignals}
     * (NOT a {@code SessionSignals} with null name) signals an exec FAILURE — the
     * caller then touches neither cache. The two fields are applied independently:
     * an empty name still clears the name cache while a {@code working=true} still
     * stamps the timestamp.
     *
     * <p>UC-49 — {@code pendingQuestion} is a TRI-STATE {@link Boolean}: {@code TRUE}
     * = a question is up, {@code FALSE} = the screen has no question, {@code null} =
     * UNKNOWN (the pane capture failed, helper omitted line 3) so the caller must
     * RETAIN the prior pending value rather than clearing it (failure policy (b)).
     */
    record SessionSignals(String nameOrNull, boolean working, Boolean pendingQuestion) {}

    /**
     * Run the helper one-shot and return both signals, or {@code null} on an exec
     * FAILURE (exit≠0 / timeout / IOException) so the caller leaves both caches
     * untouched. On success the name (line 1) is trimmed + codepoint-capped (may be
     * null/blank) and the working flag is parsed from line 2 ({@code "working"} ⇒
     * true, anything else incl. a missing line ⇒ false). The pending flag is parsed
     * from line 3 ({@code "pending-question"} ⇒ TRUE, {@code "none"} ⇒ FALSE, a
     * missing/blank line ⇒ {@code null} = unknown/retain — UC-49). {@code LC_ALL=C}
     * keeps the exec environment locale-stable, mirroring the other docker calls.
     */
    private SessionSignals derive(int n, String project) {
        try {
            List<String> argv = List.of(
                    "docker", "compose", "-p", project, "exec", "-T", "claude-sandbox", HELPER, CONVERSATION_NAME_FLAG);
            ProcessExecutor.Result r = executor.run(argv, null, Map.of("LC_ALL", "C"), REFRESH_TIMEOUT);
            if (r.exitCode() != 0) {
                return null;
            }
            String name = capCodepoints(trimToNull(firstLine(r.stdout())), MAX_NAME_CODEPOINTS);
            boolean working = parseWorking(secondLine(r.stdout()));
            Boolean pendingQuestion = parsePending(thirdLine(r.stdout()));
            return new SessionSignals(name, working, pendingQuestion);
        } catch (IOException io) {
            // Any I/O failure (exec error, timeout) → no signals; do NOT poison either cache.
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

    /**
     * UC-48 — second newline-delimited line of {@code out} (the working/idle flag),
     * or {@code null} when there is no second line (old single-line helper output,
     * or a failure path). Null-safe.
     */
    static String secondLine(String out) {
        if (out == null) {
            return null;
        }
        int nl = out.indexOf('\n');
        if (nl < 0) {
            return null;
        }
        String rest = out.substring(nl + 1);
        int nl2 = rest.indexOf('\n');
        return nl2 < 0 ? rest : rest.substring(0, nl2);
    }

    /**
     * UC-48 — parse the helper's working flag (line 2). Only the exact token
     * {@code "working"} (case-insensitive, trimmed) ⇒ {@code true}; {@code "idle"},
     * empty, or a missing/blank line ⇒ {@code false}. Conservative by design: any
     * unexpected output reads as idle (no spinner) rather than a stuck spinner.
     */
    static boolean parseWorking(String line) {
        return line != null && "working".equalsIgnoreCase(line.strip());
    }

    /**
     * UC-49 — third newline-delimited line of {@code out} (the pending-question
     * flag), or {@code null} when there is no third line (pre-UC-49 helper output,
     * or a capture-failure path where the helper deliberately omits line 3).
     * Null-safe.
     */
    static String thirdLine(String out) {
        if (out == null) {
            return null;
        }
        int nl = out.indexOf('\n');
        if (nl < 0) {
            return null;
        }
        String rest = out.substring(nl + 1);
        int nl2 = rest.indexOf('\n');
        if (nl2 < 0) {
            return null;
        }
        String third = rest.substring(nl2 + 1);
        int nl3 = third.indexOf('\n');
        return nl3 < 0 ? third : third.substring(0, nl3);
    }

    /**
     * UC-49 — parse the helper's pending flag (line 3) into a TRI-STATE:
     * {@code "pending-question"} (case-insensitive, trimmed) ⇒ {@link Boolean#TRUE};
     * {@code "none"} ⇒ {@link Boolean#FALSE}; a {@code null} / blank / unrecognised
     * line ⇒ {@code null} = UNKNOWN, signalling the caller to RETAIN the prior
     * pending value (failure policy (b)) rather than clearing it. Conservative: an
     * unexpected token never flips the badge — it is treated as "no information".
     */
    static Boolean parsePending(String line) {
        if (line == null) {
            return null;
        }
        String t = line.strip();
        if ("pending-question".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("none".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        return null;
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
