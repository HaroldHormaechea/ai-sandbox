package com.aisandbox.android.ui.components

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.aisandbox.android.terminal.TerminalStreamController
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * UC-21 AC#1/#2/#4 — the real terminal surface.
 *
 * <p>Hosts the vendored Termux [TerminalView] via [AndroidView], attaching it to
 * the process-scoped [TerminalStreamController]'s [TerminalSession]. The view
 * renders the emulator's screen buffer (full ANSI/cursor/color — AC#1), accepts
 * IME and hardware-keyboard input (AC#2), and on size/font changes drives the
 * session's resize listener, which forwards a resize frame to the server PTY
 * (AC#4).
 *
 * <p>The view is rebuilt on each composition entry, but the session/emulator
 * live in the controller, so re-entering the screen shows the prior content
 * (continuity). On release the view is unbound from the session so no leak
 * survives back-navigation.
 */
@Composable
fun TerminalSurface(
    controller: TerminalStreamController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val textSizePx = (DEFAULT_TEXT_SIZE_SP * context.resources.displayMetrics.scaledDensity).toInt()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(AiSandboxTerminalViewClient(ctx, this, textSizePx))
                setTextSize(textSizePx)
                attachSession(controller.wsSession.session)
                controller.wsSession.bindView(this)
                // Take focus so the IME / hardware keyboard targets the terminal.
                requestFocus()
            }
        },
        onRelease = { view -> controller.wsSession.unbindView(view) },
    )
}

/**
 * Minimal [TerminalViewClient] for the ai-sandbox terminal. Modifier-key state
 * comes from the on-screen [ModifierBar] (which writes bytes directly), so the
 * hardware-modifier read-backs all return false here; the view handles raw key
 * and code-point input itself (returning false from the key/codepoint callbacks
 * delegates to the view → PTY).
 */
private class AiSandboxTerminalViewClient(
    private val context: Context,
    private val view: TerminalView,
    private val textSizePx: Int,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // No pinch-to-zoom in M1; keep the font size stable.
        return textSizePx.toFloat()
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // AC#2 — tapping the surface opens the soft keyboard.
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

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

    override fun logError(tag: String?, message: String?) {
        android.util.Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        android.util.Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        android.util.Log.i(tag ?: TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        android.util.Log.d(tag ?: TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        android.util.Log.v(tag ?: TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        android.util.Log.e(tag ?: TAG, "", e)
    }

    private companion object {
        const val TAG = "TerminalSurface"
    }
}

private const val DEFAULT_TEXT_SIZE_SP = 13
