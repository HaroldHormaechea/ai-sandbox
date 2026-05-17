package com.aisandbox.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography for ai-sandbox. The design (UC04 § Theming) calls for
 * Roboto Flex (sans, primary UI) + JetBrains Mono (mono, session ids /
 * fingerprints / terminal output).
 *
 * For Checkpoint-1 we declare placeholder [FontFamily.SansSerif] +
 * [FontFamily.Monospace] so the module compiles without bundled font
 * assets. The Roboto Flex / JetBrains Mono GoogleFont providers land in a
 * later checkpoint together with the actual font wiring.
 */

val Sans: FontFamily = FontFamily.SansSerif
val Mono: FontFamily = FontFamily.Monospace

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
