package com.aisandbox.android.terminal

/**
 * UC-99 (Bug 3) — the single Android-side pin of the Claude Code TUI build that
 * the terminal composer's version-sensitive behaviour is verified against.
 *
 * <p>Several client-side behaviours depend on the exact Claude Code TUI shape:
 * the cursor-anchored pending-line extraction ([PendingInputReader]) and the
 * kill-line control byte the composer encoder prepends ([encodeComposerLine]).
 * Those are all pinned to ONE version so that bumping the pin is a single edit
 * here rather than a scattered hunt. Keep this in lock-step with the server pin
 * (`InputInjectionService.PINNED_CLAUDE_VERSION`), the two
 * `container-bin/aisandbox-conversation-tail` `pinnedClaudeVersion` constants,
 * the `SandboxDockerfile` `CLAUDE_CODE_VERSION` build arg, and
 * `server/CONVERSATION_PROTOCOL.md` — a bump reconciles every location together
 * and re-greens the UC-85 gate (see PROJECT_BRIEF.md § versions/pinning policy).
 */
object ClaudeTuiPin {

    /** The Claude Code TUI build the terminal composer behaviours are verified against. */
    const val PINNED_CLAUDE_VERSION: String = "2.1.169"
}
