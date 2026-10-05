package com.aisandbox.android.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.text.font.ResourceFont
import com.aisandbox.android.R
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the terminal-font fix on the Compose side: [Mono] is the bundled static
 * JetBrains Mono family, not the generic-monospace placeholder it used to be.
 *
 * <p>This is a pure-JVM test (JUnit 5 Jupiter — the module runs on
 * {@code useJUnitPlatform()}). Compose's {@code Font(resId, weight)} only
 * *describes* a resource font; the TTF is not decoded until a text layout
 * actually rasterizes it, so constructing and inspecting the [FontFamily] needs
 * no Android runtime or Robolectric.
 *
 * <p>Scope note: this covers only the Compose-chrome mono family (session ids /
 * fingerprints / cert metadata). The vendored Termux terminal renderer takes a
 * raw {@link android.graphics.Typeface}, not a Compose [FontFamily]; that path
 * is pinned separately by {@code com.termux.view.TerminalTypefaceTest}.
 */
class TypographyMonoFontTest {

    @Test
    fun `Mono is no longer the generic monospace placeholder`() {
        // The regression this guards against: Mono was `FontFamily.Monospace`,
        // which on a GMS-less / quirky-OEM device resolves to a system font with
        // poor box-drawing coverage — the blocky-glyph bug.
        assertThat(Mono).isNotSameAs(FontFamily.Monospace)
        assertThat(Mono).isNotInstanceOf(GenericFontFamily::class.java)
        assertThat(Mono).isInstanceOf(FontListFontFamily::class.java)
    }

    @Test
    fun `Mono bundles JetBrains Mono regular at W400 and medium at W500`() {
        val fonts = (Mono as FontListFontFamily).fonts
        // Every entry must be a bundled resource font (never a downloadable /
        // system fallback), so the family is deterministic offline.
        val resourceFonts = fonts.filterIsInstance<ResourceFont>()
        assertThat(resourceFonts)
            .withFailMessage { "Mono must be built from bundled R.font resources, got: $fonts" }
            .hasSameSizeAs(fonts)
            .hasSize(2)

        val byResIdAndWeight = resourceFonts.map { it.resId to it.weight }
        assertThat(byResIdAndWeight).containsExactlyInAnyOrder(
            R.font.jetbrains_mono_regular to FontWeight.W400,
            R.font.jetbrains_mono_medium to FontWeight.W500,
        )
    }
}
