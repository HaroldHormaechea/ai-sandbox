package com.aisandbox.android.net

import com.aisandbox.android.conversation.AnswerItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * UC-100 — the `conversation`-channel adapter's wire contract over the single
 * multiplexed connection. [ConversationClient] delegates every `send*` to
 * [MuxConnectionManager] on the `conversation` channel; these tests assert the
 * exact **payload JSON** (AC2 — the existing typed models carried unchanged,
 * incl. UC-43 multi-answer batches and JSON escaping) and that inbound
 * conversation frames surface on [ConversationClient.incoming]. Deterministic
 * (mocked manager) — no MockWebServer socket collector.
 */
class ConversationClientControlFrameTest {

    private val n = 7

    private class Harness(val n: Int) {
        val manager: MuxConnectionManager = mock(MuxConnectionManager::class.java)
        val stateFlow = MutableStateFlow<MuxConnection.State>(MuxConnection.State.Open)
        val textFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

        /** Every conversation-channel payload the adapter handed the manager, in order. */
        val sentPayloads = java.util.concurrent.CopyOnWriteArrayList<String>()

        @Volatile
        var returnValue = true

        init {
            `when`(manager.state).thenReturn(stateFlow)
            `when`(manager.textFrames(MuxEnvelope.CHANNEL_CONVERSATION, n)).thenReturn(textFlow)
            // Record the payload (arg 2) rather than verify with eq()/captor — those
            // return null and trip Kotlin's non-null checks on a mocked Kotlin method.
            `when`(manager.sendText(anyString(), any(), anyString())).thenAnswer { inv ->
                sentPayloads.add(inv.getArgument<String>(2))
                returnValue
            }
        }

        fun client() = ConversationClient(manager, sessionN = n)
    }

    /** The single payload the adapter emitted this test. */
    private fun sent(h: Harness): String = h.sentPayloads.single()

    @Test
    fun `sendComposer emits a composer-input frame`() {
        val h = Harness(n)
        assertThat(h.client().sendComposer("hello")).isTrue
        assertThat(sent(h)).isEqualTo("""{"type":"composer-input","text":"hello"}""")
    }

    @Test
    fun `sendComposer escapes newlines so multiline survives the wire`() {
        val h = Harness(n)
        h.client().sendComposer("line a\nline b")
        assertThat(sent(h)).isEqualTo("""{"type":"composer-input","text":"line a\nline b"}""")
    }

    @Test
    fun `sendAnswer emits an answer frame with selections and free text`() {
        val h = Harness(n)
        assertThat(h.client().sendAnswer("tuQ", 0, listOf(0, 2), "custom")).isTrue
        assertThat(sent(h)).isEqualTo(
            """{"type":"answer","questionUuid":"tuQ","questionIndex":0,"selections":[0,2],"freeText":"custom"}""",
        )
    }

    @Test
    fun `sendAnswer json-escapes the free text and question id`() {
        val h = Harness(n)
        h.client().sendAnswer("u\"q", 0, emptyList(), "a\"b\\c")
        val frame = sent(h)
        assertThat(frame).contains(""""questionUuid":"u\"q"""")
        assertThat(frame).contains(""""freeText":"a\"b\\c"""")
        assertThat(frame).contains(""""selections":[]""")
    }

    @Test
    fun `sendAnswerBatch emits one answer-batch frame with every item in index order`() {
        val h = Harness(n)
        assertThat(
            h.client().sendAnswerBatch(
                "tuQ",
                listOf(
                    AnswerItem(0, listOf(0, 2), ""),
                    AnswerItem(1, listOf(1), "x"),
                ),
            ),
        ).isTrue
        assertThat(sent(h)).isEqualTo(
            """{"type":"answer-batch","questionUuid":"tuQ","answers":[""" +
                """{"questionIndex":0,"selections":[0,2],"freeText":""},""" +
                """{"questionIndex":1,"selections":[1],"freeText":"x"}]}""",
        )
    }

    @Test
    fun `sendAnswerBatch json-escapes the free text and question id`() {
        val h = Harness(n)
        h.client().sendAnswerBatch("u\"q", listOf(AnswerItem(0, emptyList(), "a\"b\\c")))
        val frame = sent(h)
        assertThat(frame).contains(""""type":"answer-batch"""")
        assertThat(frame).contains(""""questionUuid":"u\"q"""")
        assertThat(frame).contains(""""freeText":"a\"b\\c"""")
        assertThat(frame).contains(""""selections":[]""")
    }

    @Test
    fun `sendEnumerate and sendInterrupt emit their control frames`() {
        val h1 = Harness(n)
        assertThat(h1.client().sendEnumerate()).isTrue
        assertThat(sent(h1)).isEqualTo("""{"type":"enumerate-targets"}""")

        val h2 = Harness(n)
        assertThat(h2.client().sendInterrupt()).isTrue
        assertThat(sent(h2)).isEqualTo("""{"type":"interrupt"}""")
    }

    @Test
    fun `sendSelectTarget emits a select-target frame with the id`() {
        val h = Harness(n)
        h.client().sendSelectTarget("swarm:main:0.1")
        assertThat(sent(h)).isEqualTo("""{"type":"select-target","targetId":"swarm:main:0.1"}""")
    }

    @Test
    fun `sendFetchDetail emits a fetch-detail frame with the tool id and uuid`() {
        val h = Harness(n)
        assertThat(h.client().sendFetchDetail("tu9", "u-line")).isTrue
        assertThat(sent(h)).isEqualTo("""{"type":"fetch-detail","toolUseId":"tu9","uuid":"u-line"}""")
    }

    @Test
    fun `sendFetchDetail json-escapes the ids`() {
        val h = Harness(n)
        h.client().sendFetchDetail("t\"u", "u\\1")
        val frame = sent(h)
        assertThat(frame).contains(""""toolUseId":"t\"u"""")
        assertThat(frame).contains(""""uuid":"u\\1"""")
    }

    @Test
    fun `sends reflect the manager result when the connection is down`() {
        val h = Harness(n)
        `when`(h.manager.sendText(anyString(), any(), anyString())).thenReturn(false)
        val c = h.client()
        assertThat(c.sendComposer("hi")).isFalse
        assertThat(c.sendEnumerate()).isFalse
        assertThat(c.sendInterrupt()).isFalse
    }

    @Test
    fun `inbound conversation frame is surfaced on incoming`() = runTest {
        val h = Harness(n)
        val c = h.client()
        val assistantFrame =
            """{"type":"assistant-text","uuid":"u1","source":"main","isSidechain":false,"text":"hi"}"""
        h.textFlow.emit(assistantFrame)
        val received = withTimeout(2_000) { c.incoming.first() }
        assertThat(received).isEqualTo(assistantFrame)
    }

    @Test
    fun `mux subprotocol constant is stable`() {
        assertThat(ConversationClient.SUBPROTOCOL).isEqualTo("ai-sandbox.mux.v1")
    }
}
