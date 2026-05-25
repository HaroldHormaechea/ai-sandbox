package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.UUID;

/**
 * A terminal session whose I/O is driven by an <em>external</em> byte stream
 * (a WebSocket to the ai-sandbox server) rather than a local pseudoterminal.
 *
 * <p>This is the ai-sandbox fork of Termux's {@code TerminalSession}. The
 * upstream class spawns a local subprocess over a JNI-created PTY
 * ({@code JNI.createSubprocess}). The ai-sandbox client has no local shell —
 * the shell lives in a Docker container reached over the network — so the JNI
 * local-process machinery (and the {@code JNI}/native module) is intentionally
 * <b>not</b> vendored. Instead:
 *
 * <ul>
 *   <li><b>Outbound</b> (user keystrokes + emulator responses): every byte the
 *       view or emulator writes is funnelled through {@link #write(byte[], int, int)}
 *       to a pluggable {@link OutputListener} — the ai-sandbox
 *       {@code WsTerminalSession} routes it to the WebSocket as PTY stdin.</li>
 *   <li><b>Inbound</b> (PTY stdout from the server): callers push bytes via
 *       {@link #appendToEmulator(byte[], int)}, which is marshalled onto the
 *       main thread, fed to the {@link TerminalEmulator}, and reflected to the
 *       view through {@link #notifyScreenUpdate()}.</li>
 * </ul>
 *
 * <p>The public API surface consumed by the vendored {@link TerminalView}
 * (size updates, {@code write}, {@code writeCodePoint}, {@code getEmulator},
 * clipboard/bell/title callbacks) is preserved verbatim, so the view is
 * unmodified against this drop-in.
 *
 * <p>Original work: Termux ({@code com.termux.terminal.TerminalSession}),
 * Apache License 2.0 — see NOTICE / LICENSE-termux.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;

    /** Sink for bytes the terminal produces (user input + emulator replies). */
    public interface OutputListener {
        void onOutput(byte[] data, int offset, int count);
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * Bytes received from the server (PTY stdout) buffered until the main
     * thread feeds them to the emulator. Mirrors upstream's process→terminal
     * queue, but fed from the WebSocket instead of a PTY file descriptor.
     */
    private final ByteQueue mIncomingQueue = new ByteQueue(64 * 1024);
    private final byte[] mReceiveBuffer = new byte[64 * 1024];

    /** Buffer to translate code points into UTF-8 before forwarding to the sink. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when the session changes (title, screen, bell, …). */
    TerminalSessionClient mClient;

    private OutputListener mOutputListener;

    /** Set by the application for user identification of the session, not by terminal. */
    public String mSessionName;

    /** Whether the backing stream is still connected. Cleared by {@link #finishIfRunning()}. */
    private volatile boolean mRunning = true;

    private final Integer mTranscriptRows;

    private final Handler mMainThreadHandler = new MainThreadHandler(Looper.getMainLooper());

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, OutputListener outputListener) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mOutputListener = outputListener;
    }

    public void setOutputListener(OutputListener outputListener) {
        this.mOutputListener = outputListener;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Initialize or reflow the emulator to the new size. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences, or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Construct the emulator. Unlike upstream, no subprocess is spawned and no
     * I/O threads are started — inbound bytes arrive via
     * {@link #appendToEmulator(byte[], int)}.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        // Drain anything that arrived before the emulator existed.
        if (mIncomingQueue.getBytesAvailable() > 0) {
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        }
    }

    /**
     * Feed PTY stdout bytes (received from the server) into the terminal. Safe
     * to call from any thread — the work is marshalled onto the main thread,
     * which is where the emulator and view must be touched.
     */
    public void appendToEmulator(byte[] data, int count) {
        if (count <= 0) return;
        if (!mIncomingQueue.write(data, 0, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Forward terminal output (user input / emulator replies) to the external sink. */
    @Override
    public void write(byte[] data, int offset, int count) {
        OutputListener listener = mOutputListener;
        if (mRunning && listener != null && count > 0) {
            listener.onOutput(data, offset, count);
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** Reset terminal emulator state. */
    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    /** Mark the session as no longer connected. There is no local process to kill. */
    public void finishIfRunning() {
        if (isRunning()) {
            mRunning = false;
            mIncomingQueue.close();
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Always 0 — there is no local exit status for a remote session. */
    public synchronized int getExitStatus() {
        return 0;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    /** No local process — the remote shell's pid is not meaningful here. */
    public int getPid() {
        return 0;
    }

    /** The remote shell's working directory is not observable from the client. */
    public String getCwd() {
        return null;
    }

    @SuppressLint("HandlerLeak")
    final class MainThreadHandler extends Handler {

        MainThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_NEW_INPUT) return;
            if (mEmulator == null) {
                // Emulator not yet sized; retry shortly so buffered bytes are not lost.
                if (mIncomingQueue.getBytesAvailable() > 0) {
                    sendEmptyMessageDelayed(MSG_NEW_INPUT, 16);
                }
                return;
            }
            int bytesRead = mIncomingQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }
        }
    }
}
