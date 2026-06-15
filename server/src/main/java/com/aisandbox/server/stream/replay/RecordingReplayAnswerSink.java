package com.aisandbox.server.stream.replay;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — the {@code replay}-profile {@link ReplayAnswerSink}. Active ONLY when the
 * deterministic-gate {@code replay} profile is on ({@code @Profile("replay")}).
 *
 * <p>It records every answer the device sends (for inspection / debugging) and uses a
 * per-session gate so a fixture tail parked at its {@code await-answer} marker resumes the
 * instant the answer is recorded. The gate is a one-slot signal queue per session number:
 * {@link #awaitAnswer} polls it (bounded by the gate timeout); {@link #recordAnswer}/{@link
 * #recordAnswerBatch} and {@link #release} each offer a token so the poll returns promptly on
 * either an answer or a stream close.
 */
@Component
@Profile("replay")
public class RecordingReplayAnswerSink implements ReplayAnswerSink {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingReplayAnswerSink.class);

    /** Marker token offered to a session's gate to unblock {@link #awaitAnswer}. */
    private static final Object SIGNAL = new Object();

    private final ConcurrentHashMap<Integer, BlockingQueue<Object>> gates = new ConcurrentHashMap<>();

    /** Every answer recorded this run, oldest-first — useful when debugging a gate failure. */
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    /** One recorded answer (single-question or a batch). */
    public record Recorded(int n, boolean batch, List<Integer> selections, String freeText, int count) {}

    private BlockingQueue<Object> gate(int n) {
        return gates.computeIfAbsent(n, k -> new LinkedBlockingQueue<>());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void recordAnswer(int n, List<Integer> selections, String freeText) {
        recorded.add(new Recorded(n, false, selections == null ? List.of() : List.copyOf(selections), freeText, 1));
        gate(n).offer(SIGNAL);
        LOG.info("replay recorded single answer for n={} selections={} freeText.len={}", n, selections,
                freeText == null ? 0 : freeText.length());
    }

    @Override
    public void recordAnswerBatch(int n, int questionCount) {
        recorded.add(new Recorded(n, true, List.of(), null, questionCount));
        gate(n).offer(SIGNAL);
        LOG.info("replay recorded batch answer for n={} questionCount={}", n, questionCount);
    }

    @Override
    public boolean awaitAnswer(int n, long timeoutMs) {
        try {
            Object tok = gate(n).poll(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            return tok != null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void release(int n) {
        gate(n).offer(SIGNAL);
    }

    /** Test/diagnostic accessor — the answers recorded so far. */
    public List<Recorded> recorded() {
        return List.copyOf(recorded);
    }
}
