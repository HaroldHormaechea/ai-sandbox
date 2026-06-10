package com.aisandbox.android.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * UC-53 — discrete steps for the conversation/agent-view font-size preference
 * (AC2). Each step carries the [scale] multiplier applied to the transcript's
 * text styles (and only those — see [com.aisandbox.android.ui.screens.ConversationScreen]).
 * The scales are intentionally modest so that even [XLARGE] keeps the 80%-of-
 * viewport bubble cap intact (AC7).
 */
enum class ConversationFontSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.15f),
    XLARGE(1.30f),
}

/**
 * UC-53 — persists the conversation/agent-view appearance preferences (AC6) in
 * DataStore Preferences. Mirrors
 * [com.aisandbox.android.terminal.KeyboardSettingsStore]: a tiny, non-secret
 * preference store kept in plain DataStore (no encryption needed).
 *
 * <p>Two preferences live here:
 * <ul>
 *   <li><b>font size</b> ([fontSize]/[setFontSize]) — a {@link ConversationFontSize}
 *       step persisted by name (defensive parse falls back to [DEFAULT_FONT_SIZE]
 *       for an unknown/legacy value); scales transcript text in the conversation
 *       view only (AC2);</li>
 *   <li><b>agent color in bubbles</b> ([useAgentColorInBubbles]/[setUseAgentColorInBubbles])
 *       — a boolean, <b>default {@link #DEFAULT_USE_AGENT_COLOR off}</b> (AC3); when on,
 *       assistant bubbles get a subtle agent-derived tint.</li>
 * </ul>
 *
 * <p>Both flows are read reactively by the conversation view so a change applies
 * live, without an app restart (AC6).
 */
class AppearanceSettingsStore(private val context: Context) {

    /**
     * Current conversation font-size step. Falls back to [DEFAULT_FONT_SIZE]
     * until a value is written, and on any unrecognised stored value (defensive
     * parse so a future/renamed enum constant can never crash the read).
     */
    val fontSize: Flow<ConversationFontSize> =
        context.appearanceSettingsDataStore.data.map { prefs ->
            prefs[FONT_SIZE_KEY]?.let { stored ->
                ConversationFontSize.entries.firstOrNull { it.name == stored }
            } ?: DEFAULT_FONT_SIZE
        }

    /**
     * Whether assistant bubbles render with a subtle agent-color tint (AC3).
     * Falls back to [DEFAULT_USE_AGENT_COLOR] (off) until a value is written.
     */
    val useAgentColorInBubbles: Flow<Boolean> =
        context.appearanceSettingsDataStore.data.map { prefs ->
            prefs[USE_AGENT_COLOR_KEY] ?: DEFAULT_USE_AGENT_COLOR
        }

    /** One-shot read for non-flow contexts. */
    suspend fun currentFontSize(): ConversationFontSize = fontSize.first()

    /** One-shot read for non-flow contexts. */
    suspend fun currentUseAgentColor(): Boolean = useAgentColorInBubbles.first()

    /** Persist the font-size step across sessions (AC6). */
    suspend fun setFontSize(size: ConversationFontSize) {
        context.appearanceSettingsDataStore.edit { it[FONT_SIZE_KEY] = size.name }
    }

    /** Persist the agent-color-in-bubbles toggle across sessions (AC6). */
    suspend fun setUseAgentColorInBubbles(enabled: Boolean) {
        context.appearanceSettingsDataStore.edit { it[USE_AGENT_COLOR_KEY] = enabled }
    }

    companion object {
        /** UC-53 — medium is the neutral 1.0× baseline. */
        val DEFAULT_FONT_SIZE = ConversationFontSize.MEDIUM

        /** UC-53 (AC3) — the agent-color tint is opt-in; off by default. */
        const val DEFAULT_USE_AGENT_COLOR = false

        private val FONT_SIZE_KEY = stringPreferencesKey("conversation_font_size")
        private val USE_AGENT_COLOR_KEY = booleanPreferencesKey("use_agent_color_in_bubbles")
    }
}

/** Top-level `Context.appearanceSettingsDataStore` accessor (preferences delegate). */
private val Context.appearanceSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "appearance_settings")
