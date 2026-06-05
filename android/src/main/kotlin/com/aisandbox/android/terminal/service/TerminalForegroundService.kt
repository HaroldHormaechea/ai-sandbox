package com.aisandbox.android.terminal.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.aisandbox.android.MainActivity
import com.aisandbox.android.R
import com.aisandbox.android.requireContainer
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.ui.screens.TerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * dataSync foreground service that keeps the terminal WebSocket alive
 * across lock-screen + task-switch (UC04 AC21, UC04-4 design).
 *
 * <p>The service does NOT own the WebSocket — that lives in the
 * process-scoped [TerminalStreamController] held by
 * [com.aisandbox.android.AppContainer]. Its single job is to keep the
 * process at foreground priority while the operator is attached, so
 * Android's low-memory-killer leaves the WS alone.
 *
 * <p><b>UC-34 / UC-35 — self-managing lifecycle.</b> The only foreground-service
 * lifecycle operations that are legal from the background are
 * {@code stopForeground()} / {@code stopSelf()} / {@code NotificationManager.notify()}
 * issued from <i>inside</i> the running service; <i>starting</i> an FGS is legal
 * only from a foreground context. So the UI only ever <b>starts</b> this service
 * (from a foreground {@code Open} transition, gated by {@code repeatOnLifecycle}),
 * and the running service self-manages everything else:
 *
 * <ul>
 *   <li>On {@link #ACTION_START} it binds to the session's controller and
 *       observes {@code controller.state}; when the state leaves the active set
 *       ({@code Open} / {@code Connecting} / {@code Reconnecting}) — i.e. it
 *       reaches {@code Idle} / {@code GaveUp} / {@code Revoked} / {@code Failed}
 *       — it self-stops via {@link #stopForegroundAndSelf}. This replaces the
 *       old background {@code startService(ACTION_STOP)} teardown that crashed
 *       on Android 8+ (UC-34).</li>
 *   <li>It also observes {@code controller.size} and re-posts the notification
 *       with fresh cols × rows via {@link NotificationManager#notify} — a
 *       background-legal update path (no {@code startService}).</li>
 *   <li>The notification's "Disconnect" action ({@link #ACTION_STOP}) closes the
 *       bound controller and self-stops.</li>
 * </ul>
 *
 * <p>Because the UI never issues a background {@code start*Service} /
 * {@code stopService}, neither the teardown edge (UC-34) nor a reconnect that
 * lands while backgrounded (UC-35) can throw
 * {@code BackgroundServiceStartNotAllowedException} /
 * {@code ForegroundServiceStartNotAllowedException}.
 *
 * <p>Notification channel was registered eagerly by
 * [com.aisandbox.android.AiSandboxApplication.onCreate], so the first
 * `notify()` call never has to wait for channel creation.
 */
class TerminalForegroundService : Service() {

    /**
     * Service-owned scope on the main dispatcher. Created in [onCreate],
     * cancelled in [onDestroy] — so the state/size collectors live exactly as
     * long as the running service and can never leak past a self-stop. Backed
     * by a [SupervisorJob] so one collector failing does not kill the other.
     */
    private lateinit var serviceScope: CoroutineScope

    /** The controller this service is currently observing (UC-34/35 binding). */
    private var boundController: TerminalStreamController? = null
    private var boundSessionN: Int? = null

    /** Parent job for the two collectors; cancelled + relaunched on a rebind. */
    private var collectorsJob: Job? = null

    /** Last-known notification params — the base for size-driven re-posts. */
    private var cachedParams: NotificationParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        boundController = null
        boundSessionN = null
        collectorsJob = null
        cachedParams = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(NotificationParams.fromIntent(intent))
            ACTION_STOP -> {
                // UC-34 AC2 — notification "Disconnect". Null-check the held
                // controller before closing; closing drives state → Idle, which
                // the state collector would also self-stop on, but we stop
                // explicitly here too (idempotent).
                boundController?.close("user-disconnect")
                stopForegroundAndSelf()
            }
            else -> {
                // UC-21 AC#8 — START_STICKY redelivers a null intent after the
                // OS kills + restarts the process. The WebSocket + emulator died
                // with that process (they live in the process-scoped
                // TerminalStreamController, not here), and we have no session
                // context to re-attach, so a lingering foreground notification
                // would be a zombie. Self-stop instead of leaking the FGS.
                stopForegroundAndSelf()
            }
        }
        return START_STICKY
    }

    /**
     * UC-35 — the ONLY start path. Always satisfies the FGS contract by calling
     * [startForeground] first (we may have been launched via
     * {@code startForegroundService}, which mandates {@code startForeground}
     * within ~5 s), then binds to the session's controller and (re)launches the
     * self-managing collectors.
     */
    private fun handleStart(params: NotificationParams) {
        cachedParams = params
        startForegroundWith(buildNotification(params))

        // Resolve WITHOUT creating or closing any controller (contrast
        // AppContainer.terminalController). A start with no live controller is a
        // stale/raced intent — do NOT bind against null; just self-stop.
        val controller = requireContainer(this).existingController(params.sessionN)
        if (controller == null) {
            stopForegroundAndSelf()
            return
        }

        if (controller !== boundController) {
            // New session (or first start) — rebind and relaunch the collectors
            // against the new controller. The notification was (re-)posted above.
            boundController = controller
            boundSessionN = params.sessionN
            launchCollectors(controller)
        }
        // Same instance → no-op the binding; cachedParams refreshed + the
        // notification re-posted above is all that's needed.
    }

    /**
     * Launch the two self-managing collectors against [controller], cancelling
     * any prior pair first. Both run on [serviceScope] (main dispatcher), so
     * every lifecycle op they issue (stopForeground/stopSelf/notify) is
     * background-legal.
     */
    private fun launchCollectors(controller: TerminalStreamController) {
        collectorsJob?.cancel()
        collectorsJob = serviceScope.launch {
            // (i) State collector — self-stop when the stream leaves the active
            // set. Active = {Open, Connecting, Reconnecting}; everything else
            // (Idle / GaveUp / Revoked / Failed) tears the FGS down from inside
            // the service, which is legal in the background (UC-34).
            launch {
                controller.state.collect { st ->
                    if (!st.isActiveStream()) {
                        stopForegroundAndSelf()
                    }
                }
            }
            // (ii) Size collector — re-post the notification with fresh cols ×
            // rows via NotificationManager.notify (background-legal). Rebuilds
            // from the cached params so the wssUrl / sessionN / idle fields stay
            // intact instead of being lost to a stale intent.
            launch {
                controller.size.collect { size ->
                    val base = cachedParams ?: return@collect
                    val updated = base.copy(cols = size.cols, rows = size.rows)
                    cachedParams = updated
                    val nm = getSystemService<NotificationManager>() ?: return@collect
                    nm.notify(NOTIFICATION_ID, buildNotification(updated))
                }
            }
        }
    }

    private fun startForegroundWith(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /** Build the AC22 / UC04-4 notification layout. */
    private fun buildNotification(p: NotificationParams): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags(),
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            REQ_DISCONNECT,
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags(),
        )
        val title = getString(R.string.notification_title, p.sessionN)
        val body = getString(
            R.string.notification_body,
            p.wssUrl,
            p.cols,
            p.rows,
            p.idleSec,
        )
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // LOW so the idle counter doesn't bing every tick. The AC22
            // design pill ("FOREGROUND · dataSync") is rendered by the
            // system's standard ongoing chrome — Android doesn't expose
            // an API to colour that pill from the app, so we accept the
            // system rendering and put the substantive info in the body.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_action_open),
                openIntent,
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_action_disconnect),
                disconnectIntent,
            )
            .build()
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    /** Notification payload parameters — extracted from the Intent extras. */
    data class NotificationParams(
        val sessionN: Int,
        val wssUrl: String,
        val cols: Int,
        val rows: Int,
        val idleSec: Int,
    ) {
        fun toIntent(action: String, context: Context): Intent =
            Intent(context, TerminalForegroundService::class.java).apply {
                this.action = action
                putExtra(EXTRA_SESSION_N, sessionN)
                putExtra(EXTRA_WSS_URL, wssUrl)
                putExtra(EXTRA_COLS, cols)
                putExtra(EXTRA_ROWS, rows)
                putExtra(EXTRA_IDLE_SEC, idleSec)
            }

        companion object {
            fun fromIntent(intent: Intent): NotificationParams = NotificationParams(
                sessionN = intent.getIntExtra(EXTRA_SESSION_N, 0),
                wssUrl = intent.getStringExtra(EXTRA_WSS_URL).orEmpty(),
                cols = intent.getIntExtra(EXTRA_COLS, 80),
                rows = intent.getIntExtra(EXTRA_ROWS, 24),
                idleSec = intent.getIntExtra(EXTRA_IDLE_SEC, 0),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.aisandbox.android.TerminalForegroundService.START"
        const val ACTION_STOP = "com.aisandbox.android.TerminalForegroundService.STOP"

        const val EXTRA_SESSION_N = "session_n"
        const val EXTRA_WSS_URL = "wss_url"
        const val EXTRA_COLS = "cols"
        const val EXTRA_ROWS = "rows"
        const val EXTRA_IDLE_SEC = "idle_sec"

        private const val NOTIFICATION_ID = 0x1ABC0001
        private const val REQ_OPEN = 1001
        private const val REQ_DISCONNECT = 1002

        /**
         * The stream states for which the FGS must stay up. Everything else
         * (Idle / GaveUp / Revoked / Failed) makes the running service
         * self-stop — see [launchCollectors]. Single source of truth for the
         * UC-34 active-set so the service and its regression test agree.
         */
        private fun TerminalState.isActiveStream(): Boolean =
            this is TerminalState.Open ||
                this is TerminalState.Connecting ||
                this is TerminalState.Reconnecting

        /**
         * Helper: start the service from a foreground [Context]. This is the
         * ONLY entry the UI uses — teardown + metadata updates are self-managed
         * by the running service (UC-34 / UC-35). Callers MUST invoke this from
         * a foreground context (the screen gates it on a STARTED-lifecycle
         * {@code Open} transition) so the Android 12+ background-FGS-start
         * restriction never trips.
         */
        fun start(context: Context, params: NotificationParams) {
            val intent = params.toIntent(ACTION_START, context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }
}
