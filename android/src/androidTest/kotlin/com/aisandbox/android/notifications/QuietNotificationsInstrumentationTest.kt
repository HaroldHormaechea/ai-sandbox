package com.aisandbox.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.R
import com.aisandbox.android.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UC-76 — instrumented coverage on a real device/emulator for the
 * "quiet FGS notifications + fixed question title" behaviour. Complements the
 * pure-JVM [PendingQuestionNotifierTest] (decision core) and
 * [PendingQuestionNotificationInstrumentationTest] (channels + deep-link nav).
 *
 * Two things can only be verified against the real Android resource + notification
 * stack:
 *
 * <ol>
 *   <li><b>The actually-posted question notification</b> — feeding the PRODUCTION
 *       [PendingQuestionNotifier] (wired to a platform gateway that posts exactly
 *       as {@code PendingQuestionService.NotificationGateway} does) and reading
 *       the result back via {@link NotificationManager#getActiveNotifications()}
 *       proves the rendered title is the fixed "Claude needs your input" string,
 *       the body is the first question's text, it lands on the HIGH
 *       {@code pending-questions} channel, and it auto-cancels when the question
 *       clears (UC-76 AC1, AC2).</li>
 *   <li><b>The quiet FGS copy</b> — resolving the exact resources the two
 *       {@code dataSync} FGS builders consume proves the watcher notification is
 *       generic "ai-sandbox"/blank (no "Watching for questions"/"you'll be
 *       notified") and the terminal-attached notification is generic
 *       "ai-sandbox"/"Terminal session active" (no "Attached to ai-sandbox-N ·
 *       wss…cols×rows·idle" detail) even when the session-id/url format args are
 *       supplied — i.e. the strings carry no placeholders (UC-76 AC3, AC4).</li>
 * </ol>
 *
 * <h2>Criterion → test map</h2>
 * <ul>
 *   <li>AC1 — {@link #question_notification_title_is_always_the_fixed_string()}</li>
 *   <li>AC2 — {@link #question_notification_body_is_the_first_question_on_the_high_channel()},
 *       {@link #question_notification_auto_cancels_when_the_question_clears()}</li>
 *   <li>AC3 — {@link #watcher_fgs_copy_is_quiet_generic_not_watching_for_questions()}</li>
 *   <li>AC4 — {@link #terminal_attached_fgs_copy_is_quiet_generic_not_attached_to_session()}</li>
 *   <li>AC5 — {@link #terminal_stream_channel_is_low_importance()}</li>
 * </ul>
 */
@RunWith(AndroidJUnit4::class)
class QuietNotificationsInstrumentationTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm: NotificationManager
        get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val fixedTitle: String get() = ctx.getString(R.string.pending_question_title)
    private val fallbackBody: String get() = ctx.getString(R.string.pending_question_fallback_body)

    @Before
    fun setUp() {
        // Android 13+: the watcher/notifier no-op without POST_NOTIFICATIONS.
        // Grant it for the app-under-test so notify() actually reaches the shade.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            ctx.packageName,
            "android.permission.POST_NOTIFICATIONS",
        )
        // Start from a clean shade so getActiveNotifications() is unambiguous.
        nm.cancelAll()
    }

    /**
     * Platform sink mirroring {@code PendingQuestionService.NotificationGateway}:
     * posts on the real HIGH {@code pending-questions} channel with the supplied
     * title/body. We drive it through the PRODUCTION notifier so the title/body
     * decision is the shipping code path, not a re-implementation.
     */
    private inner class PlatformGateway : PendingQuestionNotifier.Gateway {
        private val compat = NotificationManagerCompat.from(ctx)
        override fun areNotificationsEnabled(): Boolean = compat.areNotificationsEnabled()
        override fun post(notificationId: Int, sessionN: Int, title: String, body: String, alertOnce: Boolean) {
            val n = androidx.core.app.NotificationCompat.Builder(
                ctx,
                ctx.getString(R.string.pending_question_channel_id),
            )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(alertOnce)
                .build()
            compat.notify(notificationId, n)
        }

        override fun cancel(notificationId: Int) = compat.cancel(notificationId)
    }

    private fun runningPending(n: Int, text: String?) = SessionSummary(
        n = n,
        label = "build-server",
        state = "running",
        conversationName = "Pick a database",
        pendingQuestion = true,
        pendingQuestionText = text,
    )

    private fun activeFor(n: Int): Notification? =
        nm.activeNotifications.firstOrNull { it.id == PendingQuestionNotifier.notificationId(n) }?.notification

    /**
     * notify()/cancel() are asynchronous to NotificationManagerService, so the
     * shade reflects them with a small delay — poll rather than read once.
     */
    private fun awaitActiveFor(n: Int, timeoutMs: Long = 5000): Notification {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            activeFor(n)?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("no active notification for session $n within ${timeoutMs}ms")
    }

    private fun awaitClearedFor(n: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (activeFor(n) == null) return
            Thread.sleep(50)
        }
        throw AssertionError("notification for session $n was not cleared within ${timeoutMs}ms")
    }

    // ── AC1 — fixed title on the actually-posted notification ─────────────────

    @Test
    fun question_notification_title_is_always_the_fixed_string() {
        val notifier = PendingQuestionNotifier(PlatformGateway(), fixedTitle, fallbackBody)
        // Session HAS a conversationName + label that the old code would have used
        // as the title — the rendered title must still be the fixed string.
        notifier.onSnapshot(listOf(runningPending(7, "Which database should we use?")))

        val posted = awaitActiveFor(7)
        assertEquals(
            "Claude needs your input",
            posted.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    // ── AC2 — body is the first question; HIGH channel; auto-cancel ───────────

    @Test
    fun question_notification_body_is_the_first_question_on_the_high_channel() {
        val notifier = PendingQuestionNotifier(PlatformGateway(), fixedTitle, fallbackBody)
        notifier.onSnapshot(listOf(runningPending(7, "Which database should we use?")))

        val posted = awaitActiveFor(7)
        assertEquals(
            "Which database should we use?",
            posted.extras.getString(Notification.EXTRA_TEXT),
        )
        // It lands on the HIGH pending-questions channel (the heads-up).
        assertEquals(ctx.getString(R.string.pending_question_channel_id), posted.channelId)
        val channel = nm.getNotificationChannel(ctx.getString(R.string.pending_question_channel_id))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }

    @Test
    fun question_notification_auto_cancels_when_the_question_clears() {
        val notifier = PendingQuestionNotifier(PlatformGateway(), fixedTitle, fallbackBody)
        notifier.onSnapshot(listOf(runningPending(7, "Which database should we use?")))
        awaitActiveFor(7) // posted while pending

        // Question answered → pending clears → notification is cancelled.
        notifier.onDelta(
            listOf(runningPending(7, "Which database should we use?").copy(pendingQuestion = false)),
            emptyList(),
        )
        awaitClearedFor(7) // cleared when the question is answered
    }

    // ── AC3 — watcher FGS copy is quiet/generic ──────────────────────────────

    @Test
    fun watcher_fgs_copy_is_quiet_generic_not_watching_for_questions() {
        val title = ctx.getString(R.string.question_watcher_title)
        val text = ctx.getString(R.string.question_watcher_text)

        assertEquals("ai-sandbox", title)
        assertTrue("watcher body is blank/quiet", text.isBlank())
        // The old always-on "watching" copy must be gone.
        assertFalse(title.contains("Watching", ignoreCase = true))
        assertFalse(text.contains("Watching", ignoreCase = true))
        assertFalse(text.contains("notified", ignoreCase = true))
    }

    // ── AC4 — terminal-attached FGS copy is quiet/generic ────────────────────

    @Test
    fun terminal_attached_fgs_copy_is_quiet_generic_not_attached_to_session() {
        // The terminal FGS calls getString(notification_title, sessionN) and
        // getString(notification_body, wssUrl, cols, rows, idleSec). The quiet
        // strings carry NO placeholders, so the format args are ignored and the
        // session id / url never leak into the copy.
        val title = ctx.getString(R.string.notification_title, 7)
        val body = ctx.getString(R.string.notification_body, "wss://server/ws", 80, 24, 30)

        assertEquals("ai-sandbox", title)
        assertEquals("Terminal session active", body)
        assertFalse("no 'Attached to' copy", title.contains("Attached", ignoreCase = true))
        assertFalse("session id does not leak into title", title.contains("7"))
        assertFalse("wss url does not leak into body", body.contains("wss://"))
    }

    // ── AC5 — terminal-stream channel stays LOW ──────────────────────────────

    @Test
    fun terminal_stream_channel_is_low_importance() {
        val terminal = nm.getNotificationChannel(ctx.getString(R.string.notification_channel_id))
        assertNotNull("the terminal-stream channel must be created", terminal)
        assertEquals(NotificationManager.IMPORTANCE_LOW, terminal!!.importance)
    }
}
