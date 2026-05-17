package com.aisandbox.android.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.aisandbox.android.ui.screens.HapticEvent
import com.aisandbox.android.ui.screens.TerminalViewModel

/**
 * Side-effect composable that observes [TerminalViewModel.haptic] and
 * fires a 150 ms haptic vibration on AC14 BEL events.
 *
 * <p>Why a separate Composable: the screen file is large enough; pulling
 * the Vibrator wiring out keeps the screen's main when-expression
 * focused on rendering, and lets us reuse the same hook in the split-
 * pane render path without double-vibrating (LaunchedEffect keys on the
 * ViewModel so two surface composables share one collector).
 */
@Composable
fun HapticEventListener(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.haptic.collect { event ->
            when (event) {
                HapticEvent.Bell -> vibrate(context, durationMs = 150L)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrate(context: Context, durationMs: Long) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null) return
    val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
    vibrator.vibrate(effect)
}
