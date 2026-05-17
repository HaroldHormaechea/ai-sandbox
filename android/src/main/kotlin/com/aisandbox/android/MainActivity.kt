package com.aisandbox.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Single Activity for the ai-sandbox client (launchMode = singleTask, AC1).
 *
 * Hosts the navigation graph defined under [com.aisandbox.android.ui.AiSandboxApp].
 * Configuration changes are handled by Compose / WindowSizeClass — the
 * Activity opts out of the system's recreation for the changes listed in
 * the manifest so rotation does not destroy the WebSocket-backed terminal
 * state on the foreground service side (AC18).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.aisandbox.android.ui.theme.AiSandboxTheme {
                com.aisandbox.android.ui.AiSandboxApp()
            }
        }
    }
}
