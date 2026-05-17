package com.aisandbox.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── M3 Expressive token table (UC04 § Theming) ───────────────────────────
// One-to-one mapping with the CSS-variable table in
// design/android-ui/project/primitives.jsx. Naming preserves the
// design-bundle slot names so a future Compose ↔ design comparator works.
//
// Dark only — there is no light scheme by design (UC04 AC13).

val BgWorkbench       = Color(0xFF0A0A0C) // --bg-workbench
val Surface           = Color(0xFF131216) // --surface
val SurfaceLow        = Color(0xFF1A181D) // --surface-low
val SurfaceHigh       = Color(0xFF2A282E) // --surface-high
val SurfaceHighest    = Color(0xFF34323A) // --surface-highest
val OnSurface         = Color(0xFFECE6EC) // --on-surface
val OnSurfaceVariant  = Color(0xFFC8C2CC) // --on-surface-variant
val OnSurfaceMuted    = Color(0xFF8C8693) // --on-surface-muted
val Outline           = Color(0xFF7A747E) // --outline
val OutlineVariant    = Color(0xFF3A373D) // --outline-variant
val ErrorTone         = Color(0xFFFFB4AB) // --error
val ErrorContainer    = Color(0xFF5D1A17) // --error-container
val Success           = Color(0xFF8AD6A5) // --success
val Warning           = Color(0xFFFFB784) // --warning
val Accent            = Color(0xFFECE6EC) // --accent (mono-warm default)
val OnAccent          = Color(0xFF1F1D22) // --on-accent
val AccentContainer   = Color(0xFF2A262E) // --accent-container
val OnAccentContainer = Color(0xFFEFE9EF) // --on-accent-container

private val AiSandboxColorScheme = darkColorScheme(
    primary             = Accent,
    onPrimary           = OnAccent,
    primaryContainer    = AccentContainer,
    onPrimaryContainer  = OnAccentContainer,
    secondary           = Accent,
    onSecondary         = OnAccent,
    secondaryContainer  = AccentContainer,
    onSecondaryContainer = OnAccentContainer,
    tertiary            = Success,
    onTertiary          = OnAccent,
    background          = BgWorkbench,
    onBackground        = OnSurface,
    surface             = Surface,
    onSurface           = OnSurface,
    surfaceVariant      = SurfaceLow,
    onSurfaceVariant    = OnSurfaceVariant,
    surfaceContainerLowest = BgWorkbench,
    surfaceContainerLow    = SurfaceLow,
    surfaceContainer       = Surface,
    surfaceContainerHigh   = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    surfaceTint         = Accent,
    inverseSurface      = OnSurface,
    inverseOnSurface    = Surface,
    error               = ErrorTone,
    onError             = OnAccent,
    errorContainer      = ErrorContainer,
    onErrorContainer    = ErrorTone,
    outline             = Outline,
    outlineVariant      = OutlineVariant,
    scrim               = BgWorkbench,
)

/**
 * Theme wrapper. Dark-only by design (the [isSystemInDarkTheme] flag is
 * intentionally ignored). Forces the system status + nav bars to the
 * workbench tone so the OS chrome blends into the app instead of
 * carrying an out-of-palette grey.
 */
@Composable
fun AiSandboxTheme(
    @Suppress("UNUSED_PARAMETER")
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = BgWorkbench.toArgb()
            window.navigationBarColor = BgWorkbench.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialTheme(
        colorScheme = AiSandboxColorScheme,
        typography = AiSandboxTypography,
        content = content,
    )
}
