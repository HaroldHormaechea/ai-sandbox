package com.aisandbox.android.ui

/**
 * Stable navigation route table. Centralised here so the NavGraphBuilder
 * and the navigate() call sites don't drift on string literals.
 *
 * <p>UC04 screens (per the design bundle):
 *
 * <ul>
 *   <li>UC04-1 → [Onboarding]</li>
 *   <li>UC04-2 → [Sessions]</li>
 *   <li>UC04-2a → bottom sheet within [Sessions]</li>
 *   <li>UC04-2b → dialog within [Sessions]</li>
 *   <li>UC04-3 → [Terminal] (path arg `n`)</li>
 *   <li>UC04-5 → [Settings]</li>
 *   <li>UC04-7 → [CertRevoked]</li>
 *   <li>(pin mismatch) → [ServerIdentityChanged]</li>
 * </ul>
 */
object Routes {
    const val Onboarding = "onboarding"
    const val Sessions = "sessions"
    /** Path with `{n}` placeholder; use [terminalFor]. */
    const val TerminalPattern = "terminal/{n}"
    const val Settings = "settings"
    const val CertRevoked = "cert-revoked"
    const val ServerIdentityChanged = "server-identity-changed"

    fun terminalFor(n: Int): String = "terminal/$n"
}
