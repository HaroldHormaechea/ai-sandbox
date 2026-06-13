package com.aisandbox.android.ui.components

import androidx.compose.ui.graphics.Color
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-53 AC4 — the single shared agent-color palette behind the conversation
 * bubble tint. Pure-JVM coverage of the three functions in
 * {@link com.aisandbox.android.ui.components.AgentColors}: no Android framework
 * or Compose runtime is touched, only the value-class {@code Color} math.
 *
 * <ul>
 *   <li>{@code bubbleTintForSource} returns null for the main session / user /
 *       blank (those keep the neutral background regardless of the toggle), and a
 *       deterministic CHROMATIC color for a real subagent source;</li>
 *   <li>the chosen tint base is always a real chromatic palette member — never the
 *       neutral gray or the {@code Accent} fallback;</li>
 *   <li>{@code subtleBubbleTint} blends low-alpha over {@code SurfaceLow} so the
 *       result is opaque, distinct from both endpoints, and stays much closer to
 *       the neutral background than to the saturated dot color (readability).</li>
 * </ul>
 */
class AgentColorsTest {

    // The chromatic subset of the agentColor palette, reconstructed from the
    // public name→Color mapping (AC4 — ONE palette, no divergent set). The tint
    // must always pick from these, never the neutral gray or the Accent fallback.
    private val chromatic: Set<Color> =
        listOf("red", "green", "yellow", "blue", "magenta", "cyan", "orange")
            .map { agentColor(it) }
            .toSet()

    @Test
    fun `main user blank and null sources keep the neutral background`() {
        assertThat(bubbleTintForSource("main")).isNull()
        assertThat(bubbleTintForSource("user")).isNull()
        assertThat(bubbleTintForSource(null)).isNull()
        assertThat(bubbleTintForSource("")).isNull()
        assertThat(bubbleTintForSource("   ")).isNull()
    }

    @Test
    fun `a subagent source maps to a stable chromatic palette member`() {
        val first = bubbleTintForSource("subagent:worker-42")
        val second = bubbleTintForSource("subagent:worker-42")

        assertThat(first).isNotNull()
        // Deterministic: the same source always yields the same tint (AC4 —
        // "the same agent always gets the same tint").
        assertThat(first).isEqualTo(second)
        // Always a real chromatic color — never the neutral gray or the fallback.
        assertThat(first).isIn(chromatic)
        assertThat(first).isNotEqualTo(OnSurfaceVariant)
        assertThat(first).isNotEqualTo(Accent)
    }

    @Test
    fun `subtleBubbleTint is an opaque low-alpha blend toward the neutral background`() {
        val base = agentColor("red")
        val tint = subtleBubbleTint(base)

        // Composited over the opaque SurfaceLow → fully opaque result.
        assertThat(tint.alpha).isEqualTo(1f)
        // It is genuinely a blend: neither the raw saturated color nor the
        // untouched background.
        assertThat(tint).isNotEqualTo(base)
        assertThat(tint).isNotEqualTo(SurfaceLow)
        // Subtle (low alpha) → the result stays much closer to the neutral
        // background than to the saturated dot color, keeping body text readable.
        assertThat(distance(tint, SurfaceLow))
            .withFailMessage("tint should read as a subtle nudge from SurfaceLow, not the full color")
            .isLessThan(distance(tint, base))
    }

    private fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return dr * dr + dg * dg + db * db
    }
}
