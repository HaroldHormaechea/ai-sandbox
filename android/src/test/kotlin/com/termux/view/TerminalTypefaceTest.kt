package com.termux.view

import android.graphics.Typeface
import com.aisandbox.android.ui.components.monoTypeface
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the primary half of the terminal-font fix: a [Typeface] handed to the
 * vendored Termux [TerminalView] via [TerminalView.setTypeface] actually reaches
 * the renderer that paints the screen buffer, and the app's bundled JetBrains
 * Mono resolves to a real typeface on-device.
 *
 * <h2>Why Robolectric / JUnit4-vintage</h2>
 *
 * Reading the renderer's typeface needs a real Android {@link Typeface} and the
 * merged font resources, so this is a Robolectric test. Robolectric 4.x runs
 * only under JUnit 4, so this uses {@code @RunWith(RobolectricTestRunner)} +
 * {@code org.junit.Test} (driven by the {@code junit-vintage-engine} on the
 * module's {@code useJUnitPlatform()} runner) — NOT Jupiter {@code @Test}.
 *
 * <h2>Why package {@code com.termux.view}</h2>
 *
 * {@link TerminalRenderer#mTypeface} is package-private, so the assertion that
 * {@code setTypeface} swaps the renderer's typeface must live in this package.
 * {@link TerminalView#mRenderer} is public; {@code setTypeface} rebuilds the
 * renderer from the current text size, so we call {@code setTextSize} first
 * (exactly the order {@code TerminalSurface} uses) to initialize {@code mRenderer}.
 *
 * <h2>Coverage boundary</h2>
 *
 * This proves the Termux seam honors {@code setTypeface}, and that
 * {@code monoTypeface(ctx)} resolves. It does NOT prove {@code TerminalSurface}
 * actually *calls* {@code setTypeface} on the live view — that glue line needs an
 * instrumented / Compose-UI test, and CI runs no {@code androidTest}. That, plus
 * on-device ColorOS glyph-shape correctness, stays manual-verify-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TerminalTypefaceTest {

    @Test
    fun `setTypeface swaps the typeface the renderer paints with`() {
        val ctx = RuntimeEnvironment.getApplication()
        val view = TerminalView(ctx, null)
        // Mirror TerminalSurface: setTextSize initializes mRenderer, then
        // setTypeface rebuilds it preserving the text size.
        view.setTextSize(28)
        assertThat(view.mRenderer.mTypeface)
            .withFailMessage { "precondition: a fresh renderer defaults to MONOSPACE" }
            .isSameAs(Typeface.MONOSPACE)

        val jb = monoTypeface(ctx)
        assertThat(jb).isNotNull

        view.setTypeface(jb!!)

        // The swap must land on the renderer that actually draws the buffer —
        // not merely be stored on the view. mRenderer is rebuilt by setTypeface.
        assertThat(view.mRenderer.mTypeface)
            .withFailMessage { "setTypeface must propagate to the live renderer" }
            .isSameAs(jb)
    }

    @Test
    fun `monoTypeface resolves the bundled JetBrains Mono to a non-null typeface`() {
        val ctx = RuntimeEnvironment.getApplication()
        // ResourcesCompat.getFont must find R.font.jetbrains_mono_regular in the
        // merged resources; a null here is the bad-packaging path TerminalSurface
        // only logs a warning for.
        assertThat(monoTypeface(ctx))
            .withFailMessage { "bundled R.font.jetbrains_mono_regular must resolve to a Typeface" }
            .isNotNull
    }
}
