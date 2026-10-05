package com.aisandbox.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aisandbox.android.R

/**
 * Typography for ai-sandbox. The design (UC04 § Theming) calls for
 * Roboto Flex (sans, primary UI) + JetBrains Mono (mono, session ids /
 * fingerprints / terminal output).
 *
 * [Mono] is backed by the bundled static JetBrains Mono TTFs
 * ([R.font.jetbrains_mono_regular] W400 + [R.font.jetbrains_mono_medium] W500),
 * so all Compose mono chrome (session ids / fingerprints / cert metadata)
 * renders with the same typeface the vendored Termux terminal view now uses
 * (the terminal renderer is wired separately in `TerminalSurface.kt`, which
 * takes a raw [android.graphics.Typeface] rather than a Compose [FontFamily]).
 * [Sans] remains a placeholder [FontFamily.SansSerif]; the Roboto Flex wiring
 * lands in a later checkpoint.
 */

val Sans: FontFamily = FontFamily.SansSerif
val Mono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.W400),
    Font(R.font.jetbrains_mono_medium, FontWeight.W500),
)

private fun robotoFlex(weight: FontWeight, size: Int, line: Int, tracking: Int = 0): TextStyle =
    TextStyle(
        fontFamily = Sans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
    )

private fun jetBrainsMono(weight: FontWeight, size: Int, line: Int): TextStyle =
    TextStyle(
        fontFamily = Mono,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
    )

val AiSandboxTypography: Typography = Typography(
    displayLarge   = robotoFlex(FontWeight.W500, 57, 64),
    displayMedium  = robotoFlex(FontWeight.W500, 45, 52),
    displaySmall   = robotoFlex(FontWeight.W500, 36, 44),
    headlineLarge  = robotoFlex(FontWeight.W500, 32, 40),
    headlineMedium = robotoFlex(FontWeight.W500, 28, 36),
    headlineSmall  = robotoFlex(FontWeight.W500, 24, 32),
    titleLarge     = robotoFlex(FontWeight.W500, 22, 28),
    titleMedium    = robotoFlex(FontWeight.W500, 16, 24),
    titleSmall     = robotoFlex(FontWeight.W500, 14, 20),
    bodyLarge      = robotoFlex(FontWeight.W400, 16, 24),
    bodyMedium     = robotoFlex(FontWeight.W400, 14, 20),
    bodySmall      = robotoFlex(FontWeight.W400, 12, 16),
    labelLarge     = robotoFlex(FontWeight.W500, 14, 20),
    labelMedium    = robotoFlex(FontWeight.W500, 12, 16),
    labelSmall     = robotoFlex(FontWeight.W500, 11, 16),
)

/** Mono presets for terminal chrome / fingerprints / cert metadata. */
object AiSandboxMonoTypography {
    val terminalBody:   TextStyle = jetBrainsMono(FontWeight.W400, 13, 18)
    val terminalSmall:  TextStyle = jetBrainsMono(FontWeight.W400, 11, 16)
    val metadata:       TextStyle = jetBrainsMono(FontWeight.W500, 12, 16)
    val fingerprint:    TextStyle = jetBrainsMono(FontWeight.W400, 12, 16)
    val sessionId:      TextStyle = jetBrainsMono(FontWeight.W500, 14, 20)
}
