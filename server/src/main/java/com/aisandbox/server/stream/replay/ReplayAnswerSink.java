package com.aisandbox.server.stream.replay;

import java.util.List;

/**
 * UC-85 — the seam through which {@link com.aisandbox.server.stream.facade.ConversationFacade}
 * handles an inbound answer when there is <b>no live tmux session</b> to inject keystrokes
 * into (the deterministic-gate {@code replay} profile).
 *
 * <p>In a normal run the facade translates an answer into selection keystrokes via
 * {@code InputInjectionService}; under {@code replay} it instead {@code record(...)}s the
 * answer here (mirroring the existing UC-60 no-op-inject branch) and skips tmux entirely.
 * Recording does two things: (1) it releases the per-session {@code await-answer} gate the
 * fixture tail is parked on (see {@code ReplayEnvelopeReader}), so the recorded post-answer
 * frames replay and the conversation resumes; and (2) it lets the handler decide to echo an
 * {@link com.aisandbox.server.stream.dto.ConversationServerMessage.AnswerEcho} back over the
 * WebSocket so the on-device gate can assert the selection that was actually transmitted
 * (UC-57 / UC-43).
 *
 * <p>The default ({@code @Profile("!replay")}) bean is {@link NoopReplayAnswerSink}, whose
 * {@link #enabled()} is {@code false}: the facade then takes the real injection path and no
 * {@code AnswerEcho} is ever emitted (a production-safety invariant, AC-11). The {@link
 * #DISABLED} instance is the same no-op behaviour for unit constructions that never wire a
 * bean.
 */
public interface ReplayAnswerSink {

    /**
     * Whether replay answer handling is active. {@code true} only under the {@code replay}
     * profile. The facade branches on this to skip tmux injection, and the handler branches
     * on it to emit an {@link
     * com.aisandbox.server.stream.dto.ConversationServerMessage.AnswerEcho}.
     */
    boolean enabled();

    /** Record (and gate-release) a single-question answer for session {@code n}. */
    void recordAnswer(int n, List<Integer> selections, String freeText);

    /** Record (and gate-release) a multi-question batch answer ({@code questionCount} items) for {@code n}. */
    void recordAnswerBatch(int n, int questionCount);

    /**
     * Block the calling (tail-pump) thread until an answer for session {@code n} is recorded,
     * or {@code timeoutMs} elapses, or the wait is {@link #release(int) released} (stream
     * close). Returns {@code true} iff an answer actually arrived. The default sink returns
     * immediately {@code false} (no gating outside replay).
     */
    boolean awaitAnswer(int n, long timeoutMs);

    /** Release any thread parked in {@link #awaitAnswer} for {@code n} (e.g. on stream close). */
    void release(int n);

    /** A no-op sink (production behaviour): never enabled, never gates. Used when no bean is wired. */
    ReplayAnswerSink DISABLED = new ReplayAnswerSink() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void recordAnswer(int n, List<Integer> selections, String freeText) {
            // no-op
        }

        @Override
        public void recordAnswerBatch(int n, int questionCount) {
            // no-op
        }

        @Override
        public boolean awaitAnswer(int n, long timeoutMs) {
            return false;
        }

        @Override
        public void release(int n) {
            // no-op
        }
    };
}
