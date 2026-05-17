package com.aisandbox.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC04 § Theming — snapshot the M3 Expressive dark-only token table
 * against the CSS-variable values in the Claude Design handoff bundle
 * (`design/android-ui/project/primitives.jsx` →
 * `--bg-workbench`, `--surface`, …).
 *
 * <p>Catches accidental edits to any Color constant that would push the
 * Android client out of design lockstep. The design owns these
 * choices; the dev-team's job is to reproduce them.
 */
class ThemeTokensTest {

    @Test
    fun `token table matches the design bundle hex values`() {
        val expected: List<Pair<String, Color>> = listOf(
            "BgWorkbench"       to Color(0xFF0A0A0C),
            "Surface"           to Color(0xFF131216),
            "SurfaceLow"        to Color(0xFF1A181D),
            "SurfaceHigh"       to Color(0xFF2A282E),
            "SurfaceHighest"    to Color(0xFF34323A),
            "OnSurface"         to Color(0xFFECE6EC),
            "OnSurfaceVariant"  to Color(0xFFC8C2CC),
            "OnSurfaceMuted"    to Color(0xFF8C8693),
            "Outline"           to Color(0xFF7A747E),
            "OutlineVariant"    to Color(0xFF3A373D),
            "ErrorTone"         to Color(0xFFFFB4AB),
            "ErrorContainer"    to Color(0xFF5D1A17),
            "Success"           to Color(0xFF8AD6A5),
            "Warning"           to Color(0xFFFFB784),
            "Accent"            to Color(0xFFECE6EC),
            "OnAccent"          to Color(0xFF1F1D22),
            "AccentContainer"   to Color(0xFF2A262E),
            "OnAccentContainer" to Color(0xFFEFE9EF),
        )

        val actual: Map<String, Color> = mapOf(
            "BgWorkbench"       to BgWorkbench,
            "Surface"           to Surface,
            "SurfaceLow"        to SurfaceLow,
            "SurfaceHigh"       to SurfaceHigh,
            "SurfaceHighest"    to SurfaceHighest,
            "OnSurface"         to OnSurface,
            "OnSurfaceVariant"  to OnSurfaceVariant,
            "OnSurfaceMuted"    to OnSurfaceMuted,
            "Outline"           to Outline,
            "OutlineVariant"    to OutlineVariant,
            "ErrorTone"         to ErrorTone,
            "ErrorContainer"    to ErrorContainer,
            "Success"           to Success,
            "Warning"           to Warning,
            "Accent"            to Accent,
            "OnAccent"          to OnAccent,
            "AccentContainer"   to AccentContainer,
            "OnAccentContainer" to OnAccentContainer,
        )

        for ((name, exp) in expected) {
            val got = actual[name]
            assertThat(got).withFailMessage { "token $name should match design bundle (exp=$exp, got=$got)" }.isEqualTo(exp)
        }
    }

    @Test
    fun `accent default is mono-warm per the design`() {
        // UC04 § "Accent swatch" — v0.1 ships the mono-warm default
        // (`#ece6ec`). The design exposes 4 alternates as build-time
        // operator variations but they're not required for v0.1.
        assertThat(Accent).isEqualTo(Color(0xFFECE6EC))
    }

    @Test
    fun `success and warning tones differ from accent so status pills are legible`() {
        // The design's StatusPill component uses Success for "running" and
        // Warning for "starting". Both must visibly differ from Accent so
        // the dot is readable on the cards surface.
        assertThat(Success).isNotEqualTo(Accent)
        assertThat(Warning).isNotEqualTo(Accent)
        assertThat(Success).isNotEqualTo(Warning)
    }
}
