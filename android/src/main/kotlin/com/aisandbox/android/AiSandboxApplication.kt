package com.aisandbox.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.aisandbox.android.identity.BouncyCastleClientProvider

/**
 * Process-level [Application] instance for the ai-sandbox Android client.
 *
 * Per UC04 AC29, this class performs **no** telemetry / analytics / crash
 * reporter initialisation. The only one-shot work it does is registering
 * the foreground-service notification channel so the [TerminalForegroundService]
 * can post its dataSync notification on Android 8+.
 */
class AiSandboxApplication : Application() {

    /**
     * Process-wide service locator. Built eagerly on app start; held by
     * the Application so ViewModels can re-resolve it from any
     * `Context` without a DI container. See [AppContainer].
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // UC13 — Register the real BouncyCastle JCE provider under the
        // project-specific name `BC-ai-sandbox-client` BEFORE AppContainer
        // is constructed. AppContainer eagerly builds KeyStoreIdentityManager
        // and the network stack; the PKCS#12 import path inside
        // KeyStoreIdentityManager.importPkcs12 looks up the provider by
        // name, so it must be present in `java.security.Security` before
        // any KeyStore call. See identity/BouncyCastleClientProvider.kt
        // for the full rationale (OID 1.2.840.113549.1.5.12, distinct
        // name, `addProvider` vs `insertProviderAt`, PKCS#12-only scope).
        BouncyCastleClientProvider.register()
        container = AppContainer(this)
        registerTerminalStreamNotificationChannel()
        registerPendingQuestionChannels()
    }

    private fun registerTerminalStreamNotificationChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            // LOW so the ongoing notification doesn't bing the user every
            // time the idle counter ticks — the design's UC04-4 chrome
            // is informational, not alerting.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * UC-69 — register the two channels the pending-question push path uses:
     * a HIGH "Pending questions" channel for the per-session alerting
     * notifications, and a LOW "Question watcher" channel for the app-wide
     * [com.aisandbox.android.notifications.PendingQuestionService] ongoing FGS
     * notification. Registered eagerly so the first {@code notify} never waits;
     * a user who dislikes them can disable either channel from system settings
     * (AC8). No-op on a device without a NotificationManager.
     */
    private fun registerPendingQuestionChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        val pending = NotificationChannel(
            getString(R.string.pending_question_channel_id),
            getString(R.string.pending_question_channel_name),
            // HIGH so a question genuinely alerts the user even when backgrounded.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.pending_question_channel_description)
            setShowBadge(true)
        }
        val watcher = NotificationChannel(
            getString(R.string.question_watcher_channel_id),
            getString(R.string.question_watcher_channel_name),
            // LOW — the ongoing watcher notification is informational, never alerts.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.question_watcher_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(pending)
        nm.createNotificationChannel(watcher)
    }

    companion object {
        /** Convenience accessor for the Application context. */
        @Suppress("unused")
        fun from(context: Context): AiSandboxApplication =
            context.applicationContext as AiSandboxApplication
    }
}
