package com.aisandbox.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aisandbox.android.notifications.PendingQuestionService

/**
 * Single Activity for the ai-sandbox client (launchMode = singleTask, AC1).
 *
 * Hosts the navigation graph defined under [com.aisandbox.android.ui.AiSandboxApp].
 * Configuration changes are handled by Compose / WindowSizeClass — the
 * Activity opts out of the system's recreation for the changes listed in
 * the manifest so rotation does not destroy the WebSocket-backed terminal
 * state on the foreground service side (AC18).
 *
 * <p>UC-69 — this foreground Activity is also (a) the legal foreground context
 * that starts the app-wide [PendingQuestionService] watcher, and (b) the
 * deep-link entry point: a tapped pending-question notification opens this
 * single-task Activity carrying [EXTRA_DEEP_LINK_SESSION_N]; the id is handed to
 * the process-scoped [com.aisandbox.android.net.DeepLinkEvents] for the app shell
 * to route on once its nav graph has settled.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // UC-69 — (re)start the app-wide pending-question watcher from this
        // foreground context (FGS background-start is rejected on Android 12+).
        PendingQuestionService.start(this)
        // Cold start FROM a notification carries the deep-link extra here.
        handleDeepLinkIntent(intent)
        setContent {
            com.aisandbox.android.ui.theme.AiSandboxTheme {
                com.aisandbox.android.ui.AiSandboxApp()
            }
        }
    }

    /** singleTask warm start: a notification tap while the Activity already exists. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val n = intent?.getIntExtra(EXTRA_DEEP_LINK_SESSION_N, NO_SESSION) ?: NO_SESSION
        if (n >= 0) {
            requireContainer(this).deepLinkEvents.request(n)
        }
    }

    companion object {
        /** Intent extra carrying the session id a tapped pending-question notification opens. */
        const val EXTRA_DEEP_LINK_SESSION_N: String = "com.aisandbox.android.extra.DEEP_LINK_SESSION_N"

        private const val NO_SESSION: Int = -1
    }
}
