package com.aisandbox.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aisandbox.android.terminal.TerminalStreamController.Companion.MAIN_TARGET_ID
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow

/**
 * UC-53 — the single shared agent-color palette. Extracted out of
 * [AgentSwitcherBar] (which imports it from here, with no behavior change to the
 * switcher dots) so that both the switcher and the conversation bubble tint
 * (AC4) read ONE palette — there is no second, divergent set of colors.
 */

/**
 * Map a Claude Code agent color name to a display color. Unknown / null names
 * fall back to the mono-warm [Accent] so an unrecognised upstream color never
 * breaks the row. (Moved verbatim from [AgentSwitcherBar] in UC-53.)
 */
fun agentColor(name: String?): Color = when (name?.lowercase()) {
    "red" -> Color(0xFFFF6B68)
    "green" -> Color(0xFF8AD6A5)
    "yellow" -> Color(0xFFFFD479)
    "blue" -> Color(0xFF7CA8FF)
    "magenta", "purple", "pink" -> Color(0xFFD79AE0)
    "cyan", "teal" -> Color(0xFF79D6D6)
    "orange" -> Color(0xFFFFB784)
    "white", "gray", "grey" -> OnSurfaceVariant
    else -> Accent
}

/**
 * UC-53 (AC4) — the chromatic subset of the [agentColor] palette, excluding the
 * neutral gray and the [Accent] fallback. A bubble tint must always read as an
 * actual agent color, never the neutral background, so we pick only from these.
 */
private val CHROMATIC_PALETTE: List<Color> = listOf(
    Color(0xFFFF6B68), // red
    Color(0xFF8AD6A5), // green
    Color(0xFFFFD479), // yellow
    Color(0xFF7CA8FF), // blue
    Color(0xFFD79AE0), // magenta
    Color(0xFF79D6D6), // cyan
    Color(0xFFFFB784), // orange
)

/**
 * UC-53 (AC4) — derive a deterministic chromatic tint base for a conversation
 * item's [source] string (`"main"` | `"subagent:<agentId>"` | `"user"` | blank).
 *
 * <p>Returns `null` for the main session, the user, and any null/blank source —
 * those keep the neutral [SurfaceLow] background regardless of the toggle. For a
 * real agent source the [source] string is stable-hashed into [CHROMATIC_PALETTE]
 * so the same agent always gets the same tint within and across sessions.
 *
 * <p><b>By design</b> this tint is NOT guaranteed to match the agent's switcher
 * dot: the conversation stream carries no per-message agent-color, only the
 * [source] string, while the switcher dot is keyed on a tmux-pane id in a
 * different identifier space. Both draw from the SAME palette (AC4); the join is
 * what differs.
 */
fun bubbleTintForSource(source: String?): Color? {
    if (source.isNullOrBlank()) return null
    if (source == MAIN_TARGET_ID || source == "user") return null
    // Stable, non-negative index — `and 0xFFFFFFFFL` avoids the Int.MIN_VALUE
    // abs() foot-gun while keeping the mapping deterministic across runs.
    val index = ((source.hashCode().toLong() and 0xFFFFFFFFL) % CHROMATIC_PALETTE.size).toInt()
    return CHROMATIC_PALETTE[index]
}

/**
 * UC-53 (AC3/AC4 + pitfall) — blend [color] over [SurfaceLow] at a low alpha so
 * the assistant bubble reads as subtly tinted toward the agent color while
 * keeping `OnSurface` body text readable. Deliberately NOT the full saturated
 * dot color used in the switcher.
 */
fun subtleBubbleTint(color: Color): Color =
    color.copy(alpha = BUBBLE_TINT_ALPHA).compositeOver(SurfaceLow)

private const val BUBBLE_TINT_ALPHA = 0.14f
