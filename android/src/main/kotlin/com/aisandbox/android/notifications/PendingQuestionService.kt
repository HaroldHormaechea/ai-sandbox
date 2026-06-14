package com.aisandbox.android.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aisandbox.android.MainActivity
import com.aisandbox.android.R
import com.aisandbox.android.requireContainer
import com.aisandbox.android.ui.screens.SessionEventsController

/**
 * UC-69 — app-wide dataSync foreground service that hosts a process-lifetime
 * sessions-events subscription whose ONLY consumer is the
 * [PendingQuestionNotifier]. This is what makes pending-question notifications
 * fire while the app is backgrounded (AC5): unlike the Sessions screen's
 * foreground-bound [SessionEventsController] (which closes on STOP), this feed
 * stays connected for as long as the service runs.
 *
 * <h2>Why a SEPARATE feed (no double-buzz)</h2>
 *
 * The Sessions screen keeps its own events controller (for the live list UI) but
 * deliberately does NOT feed the notifier — otherwise a foregrounded list and
 * this service would each post the same notification. Notifier ownership lives
 * here and only here.
 *
 * <h2>Two channels</h2>
 *
 * <ul>
 *   <li>LOW {@code question-watcher} — this service's own ongoing FGS
 *       notification (informational, never alerts).</li>
 *   <li>HIGH {@code pending-questions} — the per-session alerting notifications
 *       the notifier posts (built by [NotificationGateway]).</li>
 * </ul>
 * Both channels are registered eagerly in
 * {@code AiSandboxApplication.onCreate}, so the first {@code notify} never waits.
 *
 * <h2>Lifecycle constraints (documented limitations)</h2>
 *
 * <ul>
 *   <li><b>Foreground-start only.</b> Starting an FGS is legal only from a
 *       foreground context, so [start] is invoked from {@code MainActivity}
 *       (a foreground Activity) — never from the background. Once running it
 *       persists into the background, which is the whole point.</li>
 *   <li><b>Android 14 dataSync budget.</b> A {@code dataSync} FGS is subject to
 *       the platform's ~6h/day cumulative runtime budget on Android 14+; after
 *       the budget is exhausted the system may stop the service. This is an
 *       accepted limitation for an MVP local-notification path (no FCM); the
 *       in-app UC-49 indicators remain when the watcher is not running.</li>
 * </ul>
 */
class PendingQuestionService : Service() {

    private var notifier: PendingQuestionNotifier? = null
    private var eventsController: SessionEventsController? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = requireContainer(this)
        val notifier = PendingQuestionNotifier(
            gateway = NotificationGateway(),
            fallbackBody = getString(R.string.pending_question_fallback_body),
        )
        this.notifier = notifier
        // Process-lifetime feed feeding ONLY the notifier (no UI). Built from the
        // same per-profile factories the Sessions screen uses, but owned here so
        // it survives the screen going away (AC5).
        eventsController = SessionEventsController(
            profileStore = container.profileStore,
            httpClientFactory = container::httpClient,
            eventsClientFactory = container::sessionEventsClient,
            onSnapshot = { sessions -> notifier.onSnapshot(sessions) },
            onDelta = { upserts, removed -> notifier.onDelta(upserts, removed) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ACTION_START and a START_STICKY null-intent redelivery both mean the
        // same thing for this app-wide watcher: ensure foreground + (re)connect.
        // There is no per-session context to lose, so (unlike the terminal FGS)
        // a null intent resumes watching rather than self-stopping.
        ensureForeground()
        eventsController?.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        eventsController?.close("service-destroy")
        eventsController = null
        notifier?.clearAll()
        notifier = null
        started = false
        super.onDestroy()
    }

    private fun ensureForeground() {
        val notification = NotificationCompat.Builder(this, getString(R.string.question_watcher_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.question_watcher_title))
            .setContentText(getString(R.string.question_watcher_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(WATCHER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(WATCHER_NOTIFICATION_ID, notification)
        }
        started = true
    }

    /** Tap on the ongoing watcher notification → open the app (sessions list). */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, REQ_OPEN_APP, intent, pendingIntentFlags())
    }

    /**
     * Deep-link PendingIntent into [sessionN]'s conversation. A distinct request
     * code per session keeps the extras from being clobbered by
     * {@code FLAG_UPDATE_CURRENT} (AC4 — each notification opens its own session).
     */
    private fun deepLinkPendingIntent(sessionN: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEEP_LINK_SESSION_N, sessionN)
        }
        return PendingIntent.getActivity(this, DEEP_LINK_REQ_BASE + sessionN, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    /** Platform implementation of the notifier's pure sink — the only Android-touching code. */
    private inner class NotificationGateway : PendingQuestionNotifier.Gateway {
        private val nm = NotificationManagerCompat.from(this@PendingQuestionService)

        override fun areNotificationsEnabled(): Boolean = nm.areNotificationsEnabled()

        // Guarded by areNotificationsEnabled() (which is false on 13+ without the
        // POST_NOTIFICATIONS grant), so the notify call is permission-safe.
        @SuppressLint("MissingPermission")
        override fun post(notificationId: Int, sessionN: Int, title: String, body: String, alertOnce: Boolean) {
            if (!nm.areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(
                this@PendingQuestionService,
                getString(R.string.pending_question_channel_id),
            )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(alertOnce)
                .setContentIntent(deepLinkPendingIntent(sessionN))
                .build()
            try {
                nm.notify(notificationId, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the enabled-check and notify — no-op (AC8).
            }
        }

        override fun cancel(notificationId: Int) {
            nm.cancel(notificationId)
        }
    }

    companion object {
        /** Ongoing FGS notification id — distinct from the per-session id space. */
        private const val WATCHER_NOTIFICATION_ID: Int = 0x2BCD0001

        private const val REQ_OPEN_APP: Int = 0x2BCD0010

        /** Base request code for per-session deep-link PendingIntents. */
        private const val DEEP_LINK_REQ_BASE: Int = 0x2BCE0000

        /**
         * Start the app-wide pending-question watcher. MUST be called from a
         * foreground context (MainActivity) — an FGS start from the background is
         * rejected on Android 12+. Idempotent: a second start while running just
         * re-asserts foreground + reconnects.
         */
        fun start(context: Context) {
            val intent = Intent(context, PendingQuestionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }
}
