package com.aisandbox.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * UC-21 — adapter that bridges the WebSocket byte stream to the vendored
 * Termux [TerminalSession] / `TerminalEmulator`, replacing Termux's local-PTY
 * session.
 *
 * <p>It owns one [TerminalSession] (and therefore one `TerminalEmulator` screen
 * buffer) for the lifetime of a [TerminalStreamController]. Because the
 * controller is process-scoped, the emulator buffer survives back-navigation —
 * re-entering the terminal screen re-binds a fresh [TerminalView] to the same
 * session and the on-screen content is intact (AC#1 continuity).
 *
 * <p>Data flow:
 *
 * <ul>
 *   <li><b>stdin</b> — the view writes keystrokes via
 *       {@code TerminalSession.write(...)}, funnelled to [onStdin] (→
 *       {@code controller.sendStdin} → WebSocket).</li>
 *   <li><b>stdout</b> — incoming WebSocket bytes are pushed via [feed] →
 *       {@code TerminalSession.appendToEmulator} → emulator → screen.</li>
 *   <li><b>resize</b> — the view computes cols/rows from its size and calls
 *       {@code TerminalSession.updateSize}; the resize listener forwards to
 *       [onResize] (→ {@code controller.sendResize}).</li>
 *   <li><b>bell</b> — the emulator's {@code onBell()} maps to [emitBell]
 *       (AC#14 haptic).</li>
 * </ul>
 *
 * <p>This object also implements [TerminalSessionClient] (the emulator's
 * callback surface). The currently-attached [TerminalView] is held weakly via
 * [bindView]/[unbindView] so screen-update callbacks reach whatever view is on
 * screen, and nothing leaks when the screen is left.
 */
class WsTerminalSession(
    private val appContext: Context,
    private val onStdin: (ByteArray) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
    private val emitBell: () -> Unit,
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
) : TerminalSessionClient {

    /** The vendored, JNI-free session whose emulator holds the screen buffer. */
    val session: TerminalSession = TerminalSession(
        transcriptRows,
        this,
        TerminalSession.OutputListener { data, offset, count ->
            // Hand the WebSocket an exact, owned copy — the source buffer is reused.
            onStdin(data.copyOfRange(offset, offset + count))
        },
    ).apply {
        setResizeListener { columns, rows -> onResize(columns, rows) }
    }

    /** The view currently rendering this session, or null when the screen is gone. */
    @Volatile
    private var view: TerminalView? = null

    fun bindView(terminalView: TerminalView) {
        view = terminalView
    }

    fun unbindView(terminalView: TerminalView) {
        if (view === terminalView) view = null
    }

    /** Push PTY stdout bytes received from the server into the emulator. */
    fun feed(bytes: ByteArray) {
        session.appendToEmulator(bytes, bytes.size)
    }

    /**
     * UC-99 (Bug 2) — the pane's PENDING input line, read cursor-anchored from the
     * live emulator buffer via [PendingInputReader], or "" when there is nothing
     * recognizable to prefill. Main-thread only (the emulator is fed/marshalled on
     * the main thread; callers — the Compose dispatch path — already run there).
     */
    fun readPendingInputLine(): String = PendingInputReader.readPendingInputLine(session.emulator)

    /**
     * UC-36 — flush any pending IME composing word on the bound view to the PTY.
     * Used before out-of-band input (the ModifierBar's control bytes) so a
     * half-composed word is committed + echoed in order (AC#5). No-op when no
     * view is bound or nothing is composing. Main-thread only (the caller — the
     * Compose dispatch path — already runs there).
     */
    fun flushComposingText() {
        view?.flushComposingText()
    }

    /** Tear down the session (no local process — just stops accepting input). */
    fun shutdown() {
        view = null
        session.finishIfRunning()
    }

    // ── TerminalSessionClient ────────────────────────────────────────────────

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        // Title changes are not surfaced in the UI for M1; emulator tracks it.
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // The remote shell decides lifetime; nothing to clean up locally.
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ai-sandbox terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0)?.coerceToText(appContext)?.toString() ?: return
        if (text.isNotEmpty()) this.session.write(text)
    }

    override fun onBell(session: TerminalSession) {
        emitBell()
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Cursor blink ownership stays with the view; no action required.
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // No local shell pid for a remote session.
    }

    override fun getTerminalCursorStyle(): Int? = null

    // Logging — route through android.util.Log.
    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "", e)
    }

    companion object {
        private const val TAG = "WsTerminalSession"

        /** Scrollback retained by the emulator. */
        const val DEFAULT_TRANSCRIPT_ROWS = 2_000
    }
}
