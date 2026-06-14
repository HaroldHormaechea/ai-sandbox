package com.aisandbox.android.net

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-32 — wire-decode contract for the [SessionEventMessage] sealed hierarchy
 * (the server→client frames on {@code /v1/sessions/events}).
 *
 * <p>Mirrors [SessionEventsClient]'s decoder: a lenient [Json]
 * ({@code ignoreUnknownKeys = true; isLenient = true}) polymorphic on the
 * {@code "type"} discriminator, so the server's Jackson
 * {@code @JsonTypeInfo(property = "type")} frames decode to the matching Kotlin
 * subtype. The row type is the existing [SessionSummary] verbatim, so the
 * payload feeds the coordinator with zero extra mapping (AC2).
 */
class SessionEventMessageTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun snapshot_frame_decodes_with_all_session_fields() {
        val text = """
            {"type":"snapshot","sessions":[
              {"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":"2026-06-05T10:15:30Z"},
              {"n":2,"label":"","tmuxTitle":"(idle)","state":"provisioning","uptimeSec":0,"activeStreams":0,"startedAt":null}
            ]}
        """.trimIndent()

        val msg = json.decodeFromString<SessionEventMessage>(text)

        assertThat(msg).isInstanceOf(SessionEventMessage.Snapshot::class.java)
        val snapshot = msg as SessionEventMessage.Snapshot
        assertThat(snapshot.sessions).hasSize(2)
        val first = snapshot.sessions[0]
        assertThat(first.n).isEqualTo(1)
        assertThat(first.label).isEqualTo("build")
        assertThat(first.tmuxTitle).isEqualTo("vim")
        assertThat(first.state).isEqualTo("running")
        assertThat(first.uptimeSec).isEqualTo(42L)
        assertThat(first.activeStreams).isEqualTo(2)
        assertThat(first.startedAt).isEqualTo("2026-06-05T10:15:30Z")
        assertThat(snapshot.sessions[1].state).isEqualTo("provisioning")
    }

    @Test
    fun delta_frame_decodes_upserts_and_removed() {
        val text = """
            {"type":"delta",
             "upserts":[{"n":1,"label":"build","tmuxTitle":"vim","state":"stopped","uptimeSec":7,"activeStreams":0,"startedAt":null}],
             "removed":[3,4]}
        """.trimIndent()

        val msg = json.decodeFromString<SessionEventMessage>(text)

        assertThat(msg).isInstanceOf(SessionEventMessage.Delta::class.java)
        val delta = msg as SessionEventMessage.Delta
        assertThat(delta.upserts).hasSize(1)
        assertThat(delta.upserts.single().n).isEqualTo(1)
        assertThat(delta.upserts.single().state).isEqualTo("stopped")
        assertThat(delta.removed).containsExactly(3, 4)
    }

    @Test
    fun empty_snapshot_decodes_to_an_empty_list() {
        val msg = json.decodeFromString<SessionEventMessage>("""{"type":"snapshot","sessions":[]}""")
        assertThat((msg as SessionEventMessage.Snapshot).sessions).isEmpty()
    }

    @Test
    fun delta_with_omitted_lists_defaults_to_empty() {
        // The kotlinx defaults (emptyList) tolerate a server that omits an empty
        // upserts/removed array entirely.
        val msg = json.decodeFromString<SessionEventMessage>("""{"type":"delta","removed":[5]}""")
        val delta = msg as SessionEventMessage.Delta
        assertThat(delta.upserts).isEmpty()
        assertThat(delta.removed).containsExactly(5)
    }

    @Test
    fun unknown_future_field_is_tolerated() {
        // AC parity with SessionSummary's lenient decode: a field the client
        // does not know about must never break decode.
        val text = """
            {"type":"delta","upserts":[],"removed":[],"serverIssuedAt":"2026-06-05T00:00:00Z","cursor":99}
        """.trimIndent()
        val msg = json.decodeFromString<SessionEventMessage>(text)
        assertThat(msg).isInstanceOf(SessionEventMessage.Delta::class.java)
    }

    /**
     * UC-47 AC2 / AC4 — the conversation name rides the UC-32 push frames (the
     * row type is the same [SessionSummary]). A snapshot row carries the field;
     * a delta upsert carries the UPDATED name (the live-update path); a row that
     * omits it decodes to null (fallback to tmux title client-side).
     */
    @Test
    fun snapshot_and_delta_frames_carry_the_conversation_name_field() {
        val snapshotText = """
            {"type":"snapshot","sessions":[
              {"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null,"conversationName":"Refactor the SessionRow"},
              {"n":2,"label":"","tmuxTitle":"(idle)","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}
            ]}
        """.trimIndent()
        val snapshot = json.decodeFromString<SessionEventMessage>(snapshotText) as SessionEventMessage.Snapshot
        assertThat(snapshot.sessions[0].conversationName).isEqualTo("Refactor the SessionRow")
        // Omitted field → null (server @JsonInclude(NON_NULL)); row falls back.
        assertThat(snapshot.sessions[1].conversationName).isNull()

        val deltaText = """
            {"type":"delta",
             "upserts":[{"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":50,"activeStreams":2,"startedAt":null,"conversationName":"Now testing UC-47"}],
             "removed":[]}
        """.trimIndent()
        val delta = json.decodeFromString<SessionEventMessage>(deltaText) as SessionEventMessage.Delta
        assertThat(delta.upserts.single().conversationName).isEqualTo("Now testing UC-47")
    }

    /**
     * UC-48 AC3 / AC4 — the `working` flag rides the UC-32 push frames (same
     * [SessionSummary] row type). A snapshot row carries it; a delta upsert carries
     * the UPDATED flag (the live working↔idle transition path — no manual refresh);
     * a row that omits it decodes to false (older server / no spinner).
     */
    @Test
    fun snapshot_and_delta_frames_carry_the_working_flag() {
        val snapshotText = """
            {"type":"snapshot","sessions":[
              {"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null,"working":true},
              {"n":2,"label":"","tmuxTitle":"(idle)","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}
            ]}
        """.trimIndent()
        val snapshot = json.decodeFromString<SessionEventMessage>(snapshotText) as SessionEventMessage.Snapshot
        assertThat(snapshot.sessions[0].working).isTrue()
        // Omitted field → false (older server payload); row shows no spinner.
        assertThat(snapshot.sessions[1].working).isFalse()

        val deltaText = """
            {"type":"delta",
             "upserts":[{"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":50,"activeStreams":2,"startedAt":null,"working":false}],
             "removed":[]}
        """.trimIndent()
        val delta = json.decodeFromString<SessionEventMessage>(deltaText) as SessionEventMessage.Delta
        // The live transition working→idle reaches the client over a delta upsert.
        assertThat(delta.upserts.single().working).isFalse()
    }

    /**
     * UC-49 AC3 / AC6 — the `pendingQuestion` flag rides the UC-32 push frames
     * (same [SessionSummary] row type). A snapshot row carries it; a delta upsert
     * carries the UPDATED flag (the live "?" appear/clear path — no manual
     * refresh); a row that omits it decodes to false (older server / no badge).
     */
    @Test
    fun snapshot_and_delta_frames_carry_the_pending_question_flag() {
        val snapshotText = """
            {"type":"snapshot","sessions":[
              {"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null,"working":false,"pendingQuestion":true},
              {"n":2,"label":"","tmuxTitle":"(idle)","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}
            ]}
        """.trimIndent()
        val snapshot = json.decodeFromString<SessionEventMessage>(snapshotText) as SessionEventMessage.Snapshot
        assertThat(snapshot.sessions[0].pendingQuestion).isTrue()
        // Omitted field → false (older server payload); row shows no badge.
        assertThat(snapshot.sessions[1].pendingQuestion).isFalse()

        val deltaText = """
            {"type":"delta",
             "upserts":[{"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":50,"activeStreams":2,"startedAt":null,"working":false,"pendingQuestion":false}],
             "removed":[]}
        """.trimIndent()
        val delta = json.decodeFromString<SessionEventMessage>(deltaText) as SessionEventMessage.Delta
        // The live transition pending→answered reaches the client over a delta upsert.
        assertThat(delta.upserts.single().pendingQuestion).isFalse()
    }

    /**
     * UC-69 AC3 — the `pendingQuestionText` (notification body) rides the UC-32 push
     * frames (same [SessionSummary] row type). A snapshot row carries it; a delta
     * upsert carries the UPDATED body (the live body-change path the always-on
     * watcher posts from); a row that omits it decodes to null (older server / no
     * body). This is the field the app-wide PendingQuestionService builds the
     * notification body from while the app is backgrounded (AC5).
     */
    @Test
    fun snapshot_and_delta_frames_carry_the_pending_question_text() {
        val snapshotText = """
            {"type":"snapshot","sessions":[
              {"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":42,"activeStreams":2,"startedAt":null,"working":false,"pendingQuestion":true,"pendingQuestionText":"Which database should we use?"},
              {"n":2,"label":"","tmuxTitle":"(idle)","state":"running","uptimeSec":0,"activeStreams":0,"startedAt":null}
            ]}
        """.trimIndent()
        val snapshot = json.decodeFromString<SessionEventMessage>(snapshotText) as SessionEventMessage.Snapshot
        assertThat(snapshot.sessions[0].pendingQuestionText).isEqualTo("Which database should we use?")
        // Omitted field → null (server @JsonInclude(NON_NULL)); no notification body.
        assertThat(snapshot.sessions[1].pendingQuestionText).isNull()

        val deltaText = """
            {"type":"delta",
             "upserts":[{"n":1,"label":"build","tmuxTitle":"vim","state":"running","uptimeSec":50,"activeStreams":2,"startedAt":null,"working":false,"pendingQuestion":true,"pendingQuestionText":"A new distinct question?"}],
             "removed":[]}
        """.trimIndent()
        val delta = json.decodeFromString<SessionEventMessage>(deltaText) as SessionEventMessage.Delta
        // The live body change reaches the client over a delta upsert → notification body updates.
        assertThat(delta.upserts.single().pendingQuestionText).isEqualTo("A new distinct question?")
    }
}
