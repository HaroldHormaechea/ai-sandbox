package com.aisandbox.android.notifications

import com.aisandbox.android.net.SessionSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-69 — unit coverage for the PURE [PendingQuestionNotifier] decision core,
 * exercised through a fake [PendingQuestionNotifier.Gateway] (no Android, no
 * Robolectric, no emulator). The notifier is the seam that turns the server's
 * pending-question signal (carried on [SessionSummary]) into at-most-one
 * notification per episode, deep-linkable per session, cancelled on clear.
 *
 * <h2>Criterion → test map</h2>
 * <ul>
 *   <li>AC1 — a pending+running session posts exactly one notification
 *       ({@link #rising_edge_posts_exactly_one_notification()}).</li>
 *   <li>AC2 — title prefers conversationName, then label, then ai-sandbox-&lt;n&gt;
 *       ({@link #title_prefers_conversation_name_then_label_then_session_id()}).</li>
 *   <li>AC3 — body is the first-question text, truncated; fallback when blank
 *       ({@link #body_is_the_question_text_truncated_with_fallback_when_blank()}).</li>
 *   <li>AC6 — episode dedup (no re-buzz) + silent update on a new distinct text
 *       ({@link #same_episode_is_deduped_no_rebuzz()},
 *       {@link #a_new_distinct_question_updates_silently_same_id()}).</li>
 *   <li>AC7 — cancel on answer / non-running / vanished
 *       ({@link #clearing_the_pending_state_cancels_the_notification()},
 *       {@link #a_non_running_pending_session_is_cancelled()},
 *       {@link #snapshot_reconcile_cancels_vanished_sessions()}).</li>
 *   <li>AC8 — permission-denied no-op, not tracked, re-fires on later enable
 *       ({@link #permission_denied_is_a_noop_and_not_tracked()}).</li>
 *   <li>Multiple sessions — distinct notification ids
 *       ({@link #each_session_gets_a_distinct_notification_id()}).</li>
 * </ul>
 */
class PendingQuestionNotifierTest {

    private data class Post(
        val id: Int,
        val sessionN: Int,
        val title: String,
        val body: String,
        val alertOnce: Boolean,
    )

    /** Recording fake of the platform sink. */
    private class FakeGateway(var enabled: Boolean = true) : PendingQuestionNotifier.Gateway {
        val posts = mutableListOf<Post>()
        val cancels = mutableListOf<Int>()

        override fun areNotificationsEnabled(): Boolean = enabled

        override fun post(notificationId: Int, sessionN: Int, title: String, body: String, alertOnce: Boolean) {
            posts.add(Post(notificationId, sessionN, title, body, alertOnce))
        }

        override fun cancel(notificationId: Int) {
            cancels.add(notificationId)
        }
    }

    private val fallback = "A session is waiting for your answer."

    private fun notifier(gateway: FakeGateway, maxBodyChars: Int = 140) =
        PendingQuestionNotifier(gateway, fallback, maxBodyChars)

    private fun session(
        n: Int,
        pendingQuestion: Boolean = true,
        state: String = "running",
        text: String? = "Which database should we use?",
        conversationName: String? = null,
        label: String = "",
    ) = SessionSummary(
        n = n,
        label = label,
        state = state,
        conversationName = conversationName,
        pendingQuestion = pendingQuestion,
        pendingQuestionText = text,
    )

    // ── AC1 — rising edge ─────────────────────────────────────────────────────

    @Test
    fun rising_edge_posts_exactly_one_notification() {
        val gw = FakeGateway()
        notifier(gw).onSnapshot(listOf(session(3, conversationName = "Pick a DB")))

        assertThat(gw.posts).hasSize(1)
        val p = gw.posts.single()
        assertThat(p.sessionN).isEqualTo(3)
        assertThat(p.id).isEqualTo(PendingQuestionNotifier.notificationId(3))
        assertThat(p.title).isEqualTo("Pick a DB")
        assertThat(p.body).isEqualTo("Which database should we use?")
        assertThat(p.alertOnce).`as`("the first post for a session ALERTS (alertOnce=false)").isFalse()
        assertThat(gw.cancels).isEmpty()
    }

    @Test
    fun a_non_pending_session_never_posts() {
        val gw = FakeGateway()
        notifier(gw).onSnapshot(listOf(session(1, pendingQuestion = false)))
        assertThat(gw.posts).isEmpty()
        assertThat(gw.cancels).isEmpty()
    }

    @Test
    fun a_pending_but_non_running_session_never_posts() {
        // AC1 server-gate: pending is only honoured for a running session.
        val gw = FakeGateway()
        notifier(gw).onSnapshot(listOf(session(1, state = "paused")))
        assertThat(gw.posts).isEmpty()
    }

    // ── AC2 — title fallbacks ────────────────────────────────────────────────

    @Test
    fun title_prefers_conversation_name_then_label_then_session_id() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(2, conversationName = "Refactor the row", label = "build")))
        notifier.onSnapshot(listOf(session(2, conversationName = "Refactor the row", label = "build")))
        // conversationName wins.
        assertThat(gw.posts.last().title).isEqualTo("Refactor the row")

        val gw2 = FakeGateway()
        notifier(gw2).onSnapshot(listOf(session(5, conversationName = "   ", label = "build-server")))
        // blank conversationName → label.
        assertThat(gw2.posts.single().title).isEqualTo("build-server")

        val gw3 = FakeGateway()
        notifier(gw3).onSnapshot(listOf(session(7, conversationName = null, label = "")))
        // no name, no label → ai-sandbox-<n>.
        assertThat(gw3.posts.single().title).isEqualTo("ai-sandbox-7")
    }

    // ── AC3 — body text + truncation + fallback ──────────────────────────────

    @Test
    fun body_is_the_question_text_truncated_with_fallback_when_blank() {
        // Short text → verbatim.
        val gw = FakeGateway()
        notifier(gw).onSnapshot(listOf(session(1, text = "Short question?")))
        assertThat(gw.posts.single().body).isEqualTo("Short question?")

        // Null / blank text → fallback body (still posts — AC1 honoured).
        val gwNull = FakeGateway()
        notifier(gwNull).onSnapshot(listOf(session(2, text = null)))
        assertThat(gwNull.posts.single().body).isEqualTo(fallback)
        val gwBlank = FakeGateway()
        notifier(gwBlank).onSnapshot(listOf(session(3, text = "   ")))
        assertThat(gwBlank.posts.single().body).isEqualTo(fallback)

        // Over-long text → truncated to maxBodyChars + ellipsis.
        val gwLong = FakeGateway()
        notifier(gwLong, maxBodyChars = 10).onSnapshot(listOf(session(4, text = "0123456789ABCDEF")))
        val body = gwLong.posts.single().body
        assertThat(body).endsWith("…")
        assertThat(body.removeSuffix("…").length).isLessThanOrEqualTo(10)
    }

    // ── AC6 — episode dedup + silent update ──────────────────────────────────

    @Test
    fun same_episode_is_deduped_no_rebuzz() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        val s = session(3, text = "Same question?")
        notifier.onSnapshot(listOf(s))
        notifier.onDelta(listOf(s), emptyList())
        notifier.onSnapshot(listOf(s))

        assertThat(gw.posts).`as`("a persistent SAME-text episode posts exactly once").hasSize(1)
    }

    @Test
    fun a_new_distinct_question_updates_silently_same_id() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3, text = "First question?")))
        notifier.onDelta(listOf(session(3, text = "Second distinct question?")), emptyList())

        assertThat(gw.posts).hasSize(2)
        // Same id (same session) — the body updates in place.
        assertThat(gw.posts.map { it.id }.toSet()).containsExactly(PendingQuestionNotifier.notificationId(3))
        assertThat(gw.posts[0].alertOnce).`as`("rising edge alerts").isFalse()
        assertThat(gw.posts[1].alertOnce).`as`("a within-episode text change is a SILENT update").isTrue()
        assertThat(gw.posts[1].body).isEqualTo("Second distinct question?")
    }

    // ── AC7 — cancel on clear / non-running / vanished ───────────────────────

    @Test
    fun clearing_the_pending_state_cancels_the_notification() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3)))
        // Question answered → pendingQuestion=false.
        notifier.onDelta(listOf(session(3, pendingQuestion = false)), emptyList())

        assertThat(gw.cancels).containsExactly(PendingQuestionNotifier.notificationId(3))
    }

    @Test
    fun a_non_running_pending_session_is_cancelled() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3)))
        // Went non-running while a notification was posted → cancel.
        notifier.onSnapshot(listOf(session(3, state = "stopped")))

        assertThat(gw.cancels).containsExactly(PendingQuestionNotifier.notificationId(3))
    }

    @Test
    fun re_alerting_after_a_clear_alerts_again() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3, text = "Q1?")))
        notifier.onSnapshot(listOf(session(3, pendingQuestion = false))) // clear
        notifier.onSnapshot(listOf(session(3, text = "Q2?"))) // new episode

        assertThat(gw.posts).hasSize(2)
        assertThat(gw.posts[1].alertOnce).`as`("a fresh episode after a clear is a rising edge → alerts").isFalse()
    }

    @Test
    fun delta_removed_session_is_cancelled() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3)))
        notifier.onDelta(emptyList(), listOf(3))

        assertThat(gw.cancels).containsExactly(PendingQuestionNotifier.notificationId(3))
    }

    @Test
    fun snapshot_reconcile_cancels_vanished_sessions() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3), session(4)))
        assertThat(gw.posts).hasSize(2)

        // Session 4 vanished from the authoritative snapshot → cancel only it.
        notifier.onSnapshot(listOf(session(3)))
        assertThat(gw.cancels).containsExactly(PendingQuestionNotifier.notificationId(4))
    }

    // ── AC8 — permission denied ──────────────────────────────────────────────

    @Test
    fun permission_denied_is_a_noop_and_not_tracked() {
        val gw = FakeGateway(enabled = false)
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3)))
        assertThat(gw.posts).`as`("disabled → no post").isEmpty()

        // A later enable still fires (the episode was NOT tracked while disabled).
        gw.enabled = true
        notifier.onSnapshot(listOf(session(3)))
        assertThat(gw.posts).hasSize(1)
    }

    // ── Multiple sessions — distinct ids ─────────────────────────────────────

    @Test
    fun each_session_gets_a_distinct_notification_id() {
        val gw = FakeGateway()
        notifier(gw).onSnapshot(listOf(session(3), session(8)))

        assertThat(gw.posts).hasSize(2)
        assertThat(gw.posts.map { it.id })
            .containsExactlyInAnyOrder(
                PendingQuestionNotifier.notificationId(3),
                PendingQuestionNotifier.notificationId(8),
            )
        // Ids are distinct so concurrently-pending sessions coexist.
        assertThat(gw.posts.map { it.id }.toSet()).hasSize(2)
    }

    @Test
    fun clearAll_cancels_every_tracked_notification() {
        val gw = FakeGateway()
        val notifier = notifier(gw)
        notifier.onSnapshot(listOf(session(3), session(8)))

        notifier.clearAll()

        assertThat(gw.cancels)
            .containsExactlyInAnyOrder(
                PendingQuestionNotifier.notificationId(3),
                PendingQuestionNotifier.notificationId(8),
            )
    }
}
