package com.aisandbox.android.gate

import androidx.test.platform.app.InstrumentationRegistry
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.conversation.ConversationController
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.net.ConversationClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
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

    /** One open synthetic replay session: the live controller plus a flow of its CURRENT WS client. */
    class GateSession(
        val controller: ConversationController,
        /**
         * The controller's current [ConversationClient], updated every time the controller
         * (re)creates one. The controller swaps this on reconnect, so an [EchoCollector] MUST
         * follow this flow rather than capture a single instance — otherwise a reconnect leaves it
         * subscribed to a dead client and it silently misses the echo (the CI/local flake root cause).
         */
        val clientFlow: StateFlow<ConversationClient?>,
    ) {
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
        return GateSession(controller, holder.asStateFlow())
    }

    /** Block until the replayed fixture raises an in-app-answerable question sheet for the session. */
    fun awaitQuestionSheet(session: GateSession, timeoutMs: Long = 60_000): PendingSheet.Questions =
        runBlocking {
            withTimeout(timeoutMs) {
                session.controller.pendingSheet.first { it is PendingSheet.Questions } as PendingSheet.Questions
            }
        }

    /** A decoded server→client {@code answer-echo} frame (UC-57 / UC-43 evidence). */
    data class AnswerEcho(val questionUuid: String, val questionIndex: Int, val selections: List<Int>, val freeText: String)

    /**
     * Captures the server's {@code answer-echo} frames off the live WS into a thread-safe list the
     * test polls via {@code composeTestRule.waitUntil} (which keeps the Compose/main loop pumping —
     * proven reliable; a {@code runBlocking} await on the instrumentation thread does NOT pump it).
     *
     * <p>Robustness fixes for the CI/local flake (AC-10):
     *
     * <ol>
     *   <li><b>Follows the controller's CURRENT client.</b> It collects {@code session.clientFlow}
     *       with {@code collectLatest}, so when the controller swaps its {@link ConversationClient}
     *       on a reconnect, the collector re-subscribes to the new one. Capturing a single client
     *       instance was the flake root cause: a reconnect left the collector on a dead client and it
     *       silently missed the echo (the server still recorded the answer, so it looked like a hang).</li>
     *   <li><b>No lost echo on first subscribe.</b> Each per-client subscription uses
     *       {@code onSubscription}; the constructor blocks (ms) until the FIRST one registers, so an
     *       echo can't be dropped before we're listening ({@code incoming} has {@code replay = 0}).
     *       Construct BEFORE submitting, then poll {@link #received} with a generous timeout.</li>
     * </ol>
     */
    class EchoCollector(session: GateSession) {
        private val received = CopyOnWriteArrayList<AnswerEcho>()
        private val scope = CoroutineScope(Dispatchers.IO)
        private val job: Job

        init {
            val subscribed = CompletableDeferred<Unit>()
            job = scope.launch {
                session.clientFlow.filterNotNull().collectLatest { client ->
                    client.incoming
                        .onSubscription { if (!subscribed.isCompleted) subscribed.complete(Unit) }
                        .collect { onText(it) }
                }
            }
            // Briefly block until the FIRST subscription registers (ms): no echo emitted after this
            // point can be lost. This replaces the racy fixed sleep; it does NOT wait for echoes.
            runBlocking { withTimeout(15_000) { subscribed.await() } }
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

        /** Every {@code answer-echo} received so far (oldest first). Poll via {@code waitUntil}. */
        fun received(): List<AnswerEcho> = received.toList()

        fun stop() = job.cancel()
    }
}
