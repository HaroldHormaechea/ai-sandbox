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

/**
 * dataSync foreground service that keeps the terminal WebSocket alive
 * across lock-screen + task-switch (UC04 AC21, UC04-4 design).
 *
 * <p>The service does NOT own the WebSocket — that lives in the
 * Activity's [com.aisandbox.android.ui.screens.TerminalViewModel].
 * Its single job is to keep the process at foreground priority while
 * the operator is attached, so Android's low-memory-killer leaves the
 * WS alone.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>{@link com.aisandbox.android.ui.screens.TerminalScreen} starts
 *       the service with [ACTION_START] + the session N + connection
 *       metadata when the WS reaches {@code Open}.</li>
 *   <li>The service calls {@link #startForeground} with the AC21-AC22
 *       ongoing notification (UC04-4 layout).</li>
 *   <li>The screen sends {@link ACTION_UPDATE} on metadata changes
 *       (cols × rows, idle seconds).</li>
 *   <li>The screen — or the notification's "Disconnect" action — sends
 *       {@link ACTION_STOP}. The service calls {@link #stopForeground}
 *       and {@link #stopSelf}.</li>
 * </ul>
 *
 * <p>Notification channel was registered eagerly by
 * [com.aisandbox.android.AiSandboxApplication.onCreate], so the first
 * `notify()` call never has to wait for channel creation.
 */
class TerminalForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> {
                val params = NotificationParams.fromIntent(intent)
                val notification = buildNotification(params)
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
            ACTION_STOP -> {
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
        const val ACTION_UPDATE = "com.aisandbox.android.TerminalForegroundService.UPDATE"
        const val ACTION_STOP = "com.aisandbox.android.TerminalForegroundService.STOP"

        const val EXTRA_SESSION_N = "session_n"
        const val EXTRA_WSS_URL = "wss_url"
        const val EXTRA_COLS = "cols"
        const val EXTRA_ROWS = "rows"
        const val EXTRA_IDLE_SEC = "idle_sec"

        private const val NOTIFICATION_ID = 0x1ABC0001
        private const val REQ_OPEN = 1001
        private const val REQ_DISCONNECT = 1002

        /** Helper: start the service from a [Context]. */
        fun start(context: Context, params: NotificationParams) {
            val intent = params.toIntent(ACTION_START, context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        /** Helper: update an already-running service's notification. */
        fun update(context: Context, params: NotificationParams) {
            context.startService(params.toIntent(ACTION_UPDATE, context))
        }

        /** Helper: stop. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, TerminalForegroundService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Manually dismiss the notification if the service has already been stopped. */
        fun dismissNotification(context: Context) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
