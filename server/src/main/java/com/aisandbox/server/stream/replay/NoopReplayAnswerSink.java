package com.aisandbox.server.stream.replay;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — the production {@link ReplayAnswerSink}: active for every profile EXCEPT
 * {@code replay} ({@code @Profile("!replay")}). {@link #enabled()} is {@code false}, so the
 * conversation facade always takes the real tmux-injection path and the handler never emits
 * an {@link com.aisandbox.server.stream.dto.ConversationServerMessage.AnswerEcho}. Its mere
 * existence as the wired bean outside the {@code replay} profile is what makes the
 * production-safety guard (AC-11 layer 1) hold: with no {@code replay} profile there is no
 * recording sink and no echo path.
 */
@Component
@Profile("!replay")
public class NoopReplayAnswerSink implements ReplayAnswerSink {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void recordAnswer(int n, List<Integer> selections, String freeText) {
        // no-op — production runs inject keystrokes; they never record/echo.
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
}
