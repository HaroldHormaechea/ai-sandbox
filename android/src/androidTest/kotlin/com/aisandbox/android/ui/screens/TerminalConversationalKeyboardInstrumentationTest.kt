package com.aisandbox.android.ui.screens

import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aisandbox.android.ui.components.ModifierBar
import com.aisandbox.android.ui.theme.AiSandboxTheme
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * UC-36 AC#1/#2/#5/#8 — on-device (instrumented) coverage for the conversational
 * keyboard, run on the headless emulator (AndroidJUnit4).
 *
 * <p><b>Why instrumented, in addition to the Robolectric gate.</b> The
 * make-or-break byte-delivery contract is fully pinned by the JVM/Robolectric
 * {@code com.termux.view.TerminalInputConnectionDeliveryTest} (the gate). What
 * that test <em>cannot</em> reach is the path that needs a real window: the
 * IME's own {@code BaseInputConnection.sendKeyEvent} dispatches through the
 * view's {@code ViewRootImpl}, which only exists when the view is attached to a
 * real window. These tests host the real {@link TerminalView} in a Compose
 * {@code AndroidView} (so it has a window) and:
 *
 * <ul>
 *   <li>assert the IME {@code inputType} flips when the conversational toggle
 *       changes — the observable effect of {@code restartInput()} re-querying
 *       {@code onCreateInputConnection} (AC#1/#8);</li>
 *   <li>drive a real {@code InputConnection.sendKeyEvent(ENTER)} over a window
 *       and assert the pending composing word is flushed to the PTY before the
 *       CR — closing the Robolectric dispatch gap (AC#2/#5);</li>
 *   <li>render the {@link ModifierBar} alongside the terminal surface to confirm
 *       the UC-21/UC-23 control row coexists with the new InputConnection
 *       (AC#8 — no regression to modifier-bar position / keyboard handling).</li>
 * </ul>
 *
 * <p><b>NOT RUN in CI-less / emulator-less environments.</b> Instrumented tests
 * require a booted AVD (see the {@code android-emulator-setup} skill). When no
 * emulator is attached this class is simply not executed; the byte-delivery
 * gate is the Robolectric suite, which runs on every JVM build.
 */
@RunWith(AndroidJUnit4::class)
class TerminalConversationalKeyboardInstrumentationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── AC#1/#8 — the toggle flips the IME inputType (restartInput effect) ─────

    @Test
    fun inputType_flips_between_conversational_and_raw() {
        lateinit var view: TerminalView
        var conversational by mutableStateOf(true)

        composeTestRule.setContent {
            AiSandboxTheme {
                AndroidView(
                    modifier = Modifier.testTag("terminal"),
                    factory = { ctx ->
                        TerminalView(ctx, null).also {
                            it.setTerminalViewClient(TestViewClient { !conversational })
                            view = it
                        }
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        val convType = composeTestRule.runOnUiThread { editorInputType(view) }
        conversational = false
        composeTestRule.waitForIdle()
        val rawType = composeTestRule.runOnUiThread { editorInputType(view) }

        // Conversational: a normal text field with suggestions ON (AC#1).
        assertEquals(
            "conversational must be TYPE_CLASS_TEXT|VARIATION_NORMAL",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            convType,
        )
        assertEquals(
            "conversational must NOT set NO_SUGGESTIONS",
            0,
            convType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        // Raw/char: suggestions suppressed (today's behaviour).
        assertEquals(
            "raw must be VISIBLE_PASSWORD|NO_SUGGESTIONS",
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            rawType,
        )
        assertNotEquals("the two modes must produce distinct inputType", convType, rawType)
    }

    // ── AC#2/#5 — real-window key dispatch flushes composing word before CR ─────

    @Test
    fun enter_over_a_real_window_flushes_composing_word_then_sends_cr() {
        val sink = ByteArrayOutputStream()
        lateinit var view: TerminalView

        composeTestRule.setContent {
            AiSandboxTheme {
                AndroidView(
                    modifier = Modifier.testTag("terminal"),
                    factory = { ctx ->
                        TerminalView(ctx, null).also { v ->
                            v.isFocusable = true
                            v.isFocusableInTouchMode = true
                            v.setTerminalViewClient(TestViewClient { false }) // conversational
                            val session = TerminalSession(
                                2000,
                                TestSessionClient(),
                                TerminalSession.OutputListener { d, o, c -> sink.write(d, o, c) },
                            )
                            session.initializeEmulator(80, 24, 10, 10)
                            v.mTermSession = session
                            v.mEmulator = session.getEmulator()
                            v.requestFocus()
                            view = v
                        }
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            val ic = view.onCreateInputConnection(EditorInfo())
            ic.setComposingText("foo", 1)
            // Real window present → BaseInputConnection.sendKeyEvent dispatches
            // the event through the ViewRootImpl, the path Robolectric can't take.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        composeTestRule.waitForIdle()

        // 'f','o','o' then CR — word committed/echoed before the command runs.
        assertArrayEquals(byteArrayOf(102, 111, 111, 13), sink.toByteArray())
    }

    // ── AC#8 — the modifier bar coexists with the conversational input seam ────

    @Test
    fun modifier_bar_coexists_with_the_terminal_surface() {
        composeTestRule.setContent {
            AiSandboxTheme {
                androidx.compose.foundation.layout.Column {
                    AndroidView(
                        modifier = Modifier.testTag("terminal"),
                        factory = { ctx ->
                            TerminalView(ctx, null).also { it.setTerminalViewClient(TestViewClient { false }) }
                        },
                    )
                    ModifierBar(onKey = {})
                }
            }
        }
        composeTestRule.onNodeWithTag("terminal").assertIsDisplayed()
        for (label in listOf("Ctrl", "Alt", "Esc", "Tab")) {
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    private fun editorInputType(view: TerminalView): Int {
        val info = EditorInfo()
        view.onCreateInputConnection(info)
        return info.inputType
    }

    /**
     * Minimal [TerminalViewClient] mirroring the production
     * {@code AiSandboxTerminalViewClient} contract: the only branch the keyboard
     * policy depends on is {@code shouldEnforceCharBasedInput()} (= !conversational),
     * supplied live via [charBased] so a state flip is reflected on the next
     * {@code onCreateInputConnection}. Everything else returns the production
     * defaults (no hardware modifiers; the view handles keys/code-points itself).
     */
    private class TestViewClient(private val charBased: () -> Boolean) : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = charBased()
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /** No-op [TerminalSessionClient] — the emulator only needs a non-null sink for callbacks. */
    private class TestSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
}
