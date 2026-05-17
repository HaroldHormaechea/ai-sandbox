package com.aisandbox.android.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aisandbox.android.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * UC04 AC23 — one-time battery-optimization prompt fired on first
 * stream open after install. Trade-off: exempting the app keeps the
 * dataSync foreground service alive across long lock-screen periods;
 * declining means Android may kill the WS after a few minutes idle.
 *
 * <p>The "have we asked yet" bit lives in a tiny dedicated DataStore
 * preferences file ([batteryOptDataStore]) so it can't conflict with
 * the [com.aisandbox.android.net.ServerProfileStore]'s schema.
 *
 * <p>The prompt only renders once — subsequent stream opens are silent
 * even if the user declined the first time. The README documents the
 * trade-off so an operator who declined initially can re-enable via
 * system settings → battery.
 */
@Composable
fun BatteryOptPrompt() {
    val context = LocalContext.current
    var shouldShow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val asked = context.batteryOptDataStore.data.first()[ASKED_KEY] ?: false
        if (!asked) shouldShow = true
    }

    if (!shouldShow) return
    AlertDialog(
        onDismissRequest = {
            shouldShow = false
            scope.launch { context.batteryOptDataStore.edit { it[ASKED_KEY] = true } }
        },
        title = { Text(stringResource(R.string.battery_opt_title)) },
        text = {
            Text(
                text = stringResource(R.string.battery_opt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                shouldShow = false
                scope.launch { context.batteryOptDataStore.edit { it[ASKED_KEY] = true } }
                openBatterySettings(context)
            }) {
                Text(stringResource(R.string.battery_opt_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                shouldShow = false
                scope.launch { context.batteryOptDataStore.edit { it[ASKED_KEY] = true } }
            }) {
                Text(stringResource(R.string.battery_opt_dismiss))
            }
        },
    )
}

/**
 * Deep-link to the per-app battery-settings screen. We use
 * {@code ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS} (the *list* of
 * exempt apps) rather than the per-app prompt
 * {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} — the latter
 * triggers Google's Play Console policy review even for sideload apps,
 * and AC29 plus the AC23 prose explicitly favour the deep-link.
 */
private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }.onFailure {
        // Fall back to the generic battery settings page.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }
}

private val ASKED_KEY = booleanPreferencesKey("battery_opt_asked_v1")

/** Tiny dedicated preferences file — namespace separation from server_profile. */
private val Context.batteryOptDataStore: androidx.datastore.core.DataStore<Preferences>
        by preferencesDataStore(name = "battery_opt")
