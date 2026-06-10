package com.aisandbox.android.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UC-53 AC2/AC3/AC6/AC8 — the conversation-view appearance preferences are
 * persisted across sessions via {@link AppearanceSettingsStore} (DataStore
 * Preferences), default to MEDIUM font + agent-color OFF, and survive an
 * unknown/legacy stored font value by falling back to MEDIUM.
 *
 * <p>Mirrors {@link com.aisandbox.android.terminal.KeyboardSettingsStoreTest}:
 * Robolectric supplies a real {@link android.content.Context} so the production
 * {@code preferencesDataStore} delegate writes to a temp files dir, and the WHOLE
 * lifecycle runs in a <b>single</b> test method on purpose — the
 * {@code preferencesDataStore} delegate is a process-level singleton keyed by file
 * name, so spreading reads/writes across methods would race on one shared
 * DataStore instance. One method = one deterministic timeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppearanceSettingsStoreTest {

    @Test
    fun `unknown stored font falls back to MEDIUM and both prefs round-trip`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()

        // ── Seed a legacy/unknown font-size string into the SAME backing file the
        // production delegate uses, through a short-lived DataStore on its own
        // scope. Cancelling+joining that scope frees the file from DataStore's
        // process-wide "active files" registry BEFORE the production store claims
        // it, so the two never collide. (AC8 — defensive parse must not crash on a
        // future/renamed enum constant.)
        run {
            val seedJob = Job()
            val seedScope = CoroutineScope(seedJob + Dispatchers.IO)
            val seedStore = PreferenceDataStoreFactory.create(scope = seedScope) {
                context.preferencesDataStoreFile("appearance_settings")
            }
            seedStore.edit { it[stringPreferencesKey("conversation_font_size")] = "GIGANTIC_LEGACY_VALUE" }
            seedJob.cancelAndJoin()
        }

        val store = AppearanceSettingsStore(context)

        // Unknown stored value → MEDIUM (defensive parse, AC8). The agent-color key
        // was never written, so it reads its documented default OFF (AC3).
        assertThat(store.currentFontSize())
            .withFailMessage("an unrecognised stored font value must fall back to MEDIUM")
            .isEqualTo(ConversationFontSize.MEDIUM)
        assertThat(store.currentUseAgentColor())
            .withFailMessage("a fresh agent-color pref must default to OFF (AC3)")
            .isFalse()

        // Font size persists across the discrete steps (AC2/AC6).
        store.setFontSize(ConversationFontSize.XLARGE)
        assertThat(store.currentFontSize()).isEqualTo(ConversationFontSize.XLARGE)

        // Flip to a different step and read it back — proves the value is genuinely
        // stored, not merely defaulted.
        store.setFontSize(ConversationFontSize.SMALL)
        assertThat(store.currentFontSize()).isEqualTo(ConversationFontSize.SMALL)

        // Agent-color toggle persists on (AC3/AC6) …
        store.setUseAgentColorInBubbles(true)
        assertThat(store.currentUseAgentColor()).isTrue()

        // … and back off — round-trip, not a defaulted read.
        store.setUseAgentColorInBubbles(false)
        assertThat(store.currentUseAgentColor()).isFalse()
    }

    @Test
    fun `default constants match the documented appearance defaults`() {
        // Pins the documented defaults independently of any DataStore I/O so a
        // future flip of either constant is a conscious, blamed change.
        assertThat(AppearanceSettingsStore.DEFAULT_FONT_SIZE).isEqualTo(ConversationFontSize.MEDIUM)
        assertThat(AppearanceSettingsStore.DEFAULT_USE_AGENT_COLOR).isFalse()
    }
}
