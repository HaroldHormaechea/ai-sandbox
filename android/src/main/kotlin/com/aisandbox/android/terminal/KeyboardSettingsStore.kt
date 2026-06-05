package com.aisandbox.android.terminal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * UC-36 — persists the conversational-keyboard toggle (AC#7) in DataStore
 * Preferences.
 *
 * <p>The single boolean `conversational_keyboard` selects between:
 * <ul>
 *   <li><b>true</b> (default) — the conversational mode the user asked for:
 *       word prediction + suggestion strip + autocomplete
 *       ([com.termux.view.TerminalView.computeInputType] non-char path);</li>
 *   <li><b>false</b> — the raw/char escape hatch (today's historical Termux
 *       behaviour), best for heavy CLI control.</li>
 * </ul>
 *
 * <p>Mirrors [com.aisandbox.android.net.ServerProfileStore]: a tiny, non-secret
 * preference kept in plain DataStore (no encryption needed). One key, so no
 * Proto-DataStore.
 */
class KeyboardSettingsStore(private val context: Context) {

    /**
     * Current mode — `true` = conversational (words + prediction), `false` =
     * raw/char. Falls back to [DEFAULT_CONVERSATIONAL] until a value is written.
     */
    val conversational: Flow<Boolean> =
        context.keyboardSettingsDataStore.data.map { prefs ->
            prefs[CONVERSATIONAL_KEY] ?: DEFAULT_CONVERSATIONAL
        }

    /** One-shot read for non-flow contexts. */
    suspend fun current(): Boolean = conversational.first()

    /** Persist the toggle across sessions (AC#7). */
    suspend fun setConversational(enabled: Boolean) {
        context.keyboardSettingsDataStore.edit { it[CONVERSATIONAL_KEY] = enabled }
    }

    companion object {
        /** UC-36 — the user asked for conversational input to be the default. */
        const val DEFAULT_CONVERSATIONAL = true
        private val CONVERSATIONAL_KEY = booleanPreferencesKey("conversational_keyboard")
    }
}

/** Top-level `Context.keyboardSettingsDataStore` accessor (preferences delegate). */
private val Context.keyboardSettingsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "keyboard_settings")
