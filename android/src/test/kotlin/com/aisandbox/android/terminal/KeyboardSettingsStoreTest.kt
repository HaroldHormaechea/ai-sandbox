package com.aisandbox.android.terminal

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UC-36 AC#7 — the conversational-keyboard toggle is persisted across sessions
 * via {@link KeyboardSettingsStore} (DataStore Preferences), and defaults to
 * conversational (the mode the user asked for).
 *
 * <p>Robolectric supplies a real {@link android.content.Context} so the
 * production {@code preferencesDataStore} delegate writes to a temp files dir.
 * The whole lifecycle (default → write → read-back → flip → read-back) runs in
 * a <b>single</b> test method on purpose: the {@code preferencesDataStore}
 * delegate is a process-level singleton keyed by file name, so spreading reads
 * and writes across methods would race on one shared DataStore instance. One
 * method = one deterministic timeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeyboardSettingsStoreTest {

    @Test
    fun `default is conversational true and the toggle round-trips`() = runBlocking<Unit> {
        val store = KeyboardSettingsStore(RuntimeEnvironment.getApplication())

        // Default before anything is written — AC#7: conversational is the
        // user-requested default.
        assertThat(store.current())
            .withFailMessage("a fresh store must default to conversational=true")
            .isTrue()

        // Persist raw/char, read it back.
        store.setConversational(false)
        assertThat(store.current()).isFalse()

        // Persist conversational again, read it back — proves the value is
        // genuinely stored, not merely defaulted.
        store.setConversational(true)
        assertThat(store.current()).isTrue()
    }

    @Test
    fun `default constant matches the user-requested conversational default`() {
        // Pins the documented default independently of any DataStore I/O so a
        // future flip of the constant is a conscious, blamed change.
        assertThat(KeyboardSettingsStore.DEFAULT_CONVERSATIONAL).isTrue()
    }

    @Test
    fun `UC-99 - terminal composer is the default input surface`() {
        // AC#5 — the decoupled composer (lag + autocorrect fix) is the DEFAULT
        // terminal input mode; raw passthrough is the opt-in escape hatch. Pinned
        // as a pure constant (no DataStore I/O, so no race with the round-trip
        // test above) so flipping the default is a deliberate, blamed change.
        assertThat(KeyboardSettingsStore.DEFAULT_TERMINAL_COMPOSER).isTrue()
    }
}
