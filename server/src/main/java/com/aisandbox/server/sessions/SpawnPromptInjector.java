package com.aisandbox.server.sessions;

import java.io.IOException;

/**
 * UC-98 — port (dependency-inversion seam) the {@code sessions} domain uses to
 * INJECT a fixed setup prompt into a freshly-spawned session's live main pane,
 * without depending on the {@code stream} domain.
 *
 * <p><b>Why this exists.</b> The post-spawn workspace-project prompt is typed
 * (and submitted) via the conversation domain's {@code InputInjectionService}
 * ({@code tmux send-keys}), which lives in {@code stream}. A direct
 * {@code sessions → stream} call would create a package cycle — {@code stream}
 * already depends on {@code sessions} (its services import
 * {@code sessions.dto} types) — which
 * {@code LayeringTest.no_cycles_between_top_level_feature_packages} forbids.
 * Inverting the dependency keeps the contract here in {@code sessions} and the
 * implementation in {@code stream}, so the only edge stays {@code stream →
 * sessions} — acyclic. This mirrors the {@code mcp.McpLoginInitiator} precedent
 * (contract in the consumer domain, implementation in {@code stream}).
 *
 * <p>Spring injects the single implementation by type (the conversation
 * domain's facade). The honesty contract is the implementation's: it types the
 * text and presses Enter exactly once (inject + submit) — it never retries.
 */
public interface SpawnPromptInjector {

    /**
     * Type {@code text} into session {@code n}'s live main pane and submit it
     * (press Enter) — exactly once. Used by the UC-98 post-spawn choreography to
     * pre-seed the "We will work in the project &lt;folder&gt;." setup prompt
     * before the user attaches (AC4/AC6).
     *
     * @param n    the target session number
     * @param text the fixed setup prompt to type and submit
     */
    void inject(int n, String text) throws IOException;
}
