package com.aisandbox.android.gate

import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.conversation.ConversationController
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.net.ConversationClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * UC-85 — shared harness for the deterministic, LLM-free on-device functional gate
 * (`com.aisandbox.android.gate`). This IS the gate `android-gate.yml` runs via
 * `am instrument -e package com.aisandbox.android.gate` against the REAL mTLS management server
 * booted under the `replay` Spring profile (committed protocol fixtures + synthetic sessions +
 * answer-echo).
 *
 * <p>Design — faithful end-to-end, no shortcuts:
 *
 * <ul>
 *   <li><b>Enrollment</b> is the reused UC-83 QR-from-file route — {@code gate.sh} runs
 *       {@code E2eQrFileEnrollmentTest} first, persisting a {@link com.aisandbox.android.net.ServerProfile}
 *       to the app's profile store; this suite relies on that persisted enrollment (and SKIPS,
 *       never fails, if it is absent — e.g. when run standalone without the gate harness).</li>
 *   <li><b>The driver is the REAL stack:</b> a real {@link ConversationController} (built with the
 *       app container's real http + conversation-client factories, so it talks mTLS/OkHttp +
 *       WebSocket to the live replay server) attaches to a synthetic replay session, backfills the
 *       fixture transcript, and surfaces the {@code AskUserQuestion} as a pending sheet — exactly
 *       as the production UI does. The tests then render the REAL {@code QuestionSheet} /
 *       {@code ConversationContent} composables and drive them by stable {@code testTag} only (no
 *       coordinate taps, no screenshot eyeballing).</li>
 *   <li><b>UC-57 / UC-43 are proven on the wire:</b> the answer the user taps is read back off the
 *       server's {@code answer-echo} frame on the SAME WebSocket ({@link ConversationClient#getIncoming()}),
 *       so the assertion is "the selected option is the one actually transmitted", not "the one
 *       first visible".</li>
 * </ul>
 *
 * <p>The capturing {@code clientFactory} below is the only seam: it wraps the container's real
 * {@code conversationClient} factory and records the live {@link ConversationClient} the controller
 * created, so the test can observe the raw frames the production code path sends/receives — without
 * altering that path.
 */
object GateHarness {

    /** Synthetic replay session numbers — must match {@code fixtures/replay/manifest.json}. */
    const val N_SINGLE_SELECT = 1
    const val N_MULTI_SELECT = 2
    const val N_OTHER_FREE_TEXT = 3
    const val N_MULTI_QUESTION = 4
    const val N_TRANSCRIPT = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun app(): AiSandboxApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AiSandboxApplication

    /**
     * Skip (do not fail) the whole suite when the device is not enrolled — the gate is only
     * meaningful against a live server it has a client identity for. {@code gate.sh} guarantees
     * enrollment ran first; a bare `connectedAndroidTest` without the server will SKIP cleanly.
     */
    fun assumeEnrolled() {
        val profile = runBlocking { app().container.profileStore.current() }
        Assume.assumeTrue(
            "gate suite requires prior UC-83 enrollment + a live replay server (run via android/gate.sh)",
            profile != null,
        )
    }

    /** One open synthetic replay session: the live controller plus the captured WS client. */
    class GateSession(
        val controller: ConversationController,
        private val clientHolder: MutableStateFlow<ConversationClient?>,
    ) {
        /** The live conversation WS client the controller is using (captured via the factory). */
        fun client(): ConversationClient =
            clientHolder.value ?: error("conversation client not created yet — controller did not connect")

        fun close() = controller.close("gate-test-done")
    }

    /**
     * Build a real controller for [n] (container http + conversation-client factories → live
     * mTLS/WS to the replay server), capturing the [ConversationClient] it creates, and start it.
     */
    fun open(n: Int): GateSession {
        val container = app().container
        val holder = MutableStateFlow<ConversationClient?>(null)
        val controller = ConversationController(
            sessionN = n,
            profileStore = container.profileStore,
            httpClientFactory = container::httpClient,
            clientFactory = { http, sn -> container.conversationClient(http, sn).also { holder.value = it } },
            onClosed = {},
        )
        controller.attach(n)
        return GateSession(controller, holder)
    }

    /** Block until the replayed fixture raises an in-app-answerable question sheet for the session. */
    fun awaitQuestionSheet(session: GateSession, timeoutMs: Long = 45_000): PendingSheet.Questions =
        runBlocking {
            withTimeout(timeoutMs) {
                session.controller.pendingSheet.first { it is PendingSheet.Questions } as PendingSheet.Questions
            }
        }

    /** A decoded server→client {@code answer-echo} frame (UC-57 / UC-43 evidence). */
    data class AnswerEcho(val questionUuid: String, val questionIndex: Int, val selections: List<Int>, val freeText: String)

    /**
     * Subscribes to the live WS frames and records every {@code answer-echo} the server emits.
     * Start BEFORE submitting an answer; the short settle gives the subscription time to register
     * (the production {@code incoming} flow has no replay, so a pre-subscription emit would be lost).
     */
    class EchoCollector(client: ConversationClient) {
        private val received = CopyOnWriteArrayList<AnswerEcho>()
        private val scope = CoroutineScope(Dispatchers.IO)
        private val job: Job = scope.launch { client.incoming.collect { onText(it) } }

        init {
            // Let the SharedFlow subscription register before any answer is submitted.
            Thread.sleep(500)
        }

        private fun onText(text: String) {
            val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "answer-echo") return
            val selections = (obj["selections"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
            received.add(
                AnswerEcho(
                    questionUuid = obj["questionUuid"]?.jsonPrimitive?.contentOrNull ?: "",
                    questionIndex = obj["questionIndex"]?.jsonPrimitive?.intOrNull ?: -1,
                    selections = selections,
                    freeText = obj["freeText"]?.jsonPrimitive?.contentOrNull ?: "",
                ),
            )
        }

        fun received(): List<AnswerEcho> = received.toList()

        fun stop() = job.cancel()
    }
}
