package com.aisandbox.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

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
        container = AppContainer(this)
        registerTerminalStreamNotificationChannel()
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

    companion object {
        /** Convenience accessor for the Application context. */
        @Suppress("unused")
        fun from(context: Context): AiSandboxApplication =
            context.applicationContext as AiSandboxApplication
    }
}
