package com.aisandbox.android.terminal.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * dataSync foreground service that keeps the terminal WebSocket attached
 * across lock-screen + task-switch (UC04 AC21, UC04-4 design).
 *
 * NOTE — this is a Checkpoint-1 stub. The full implementation lands later
 * in the UC04 build-out: the service will own a [com.aisandbox.android.net.StreamClient]
 * instance, post the design's UC04-4 ongoing notification, observe the
 * shared reconnect schedule, and stop itself + dismiss the notification
 * when the AC25 five-minute cap trips.
 *
 * For now we satisfy the manifest reference + Android lifecycle contract
 * so the module compiles and AGP recognises a valid foreground service
 * of type dataSync.
 */
class TerminalForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if Android kills us under memory pressure, it
        // will recreate the service. The recreated instance will see a
        // null intent and decide what to do based on persisted state
        // (the disk-resident server profile + a snapshot of the last
        // attached session id).
        return START_STICKY
    }
}
