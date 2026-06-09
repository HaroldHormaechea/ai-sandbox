package com.aisandbox.android.conversation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-37 AC6/AC22 + UC-41 AC4 — the [ConversationItem.key] dedupe + merge contract.
 *
 * <p>UC-37: a single transcript line can carry several blocks (thinking + text +
 * tool_use) sharing one `uuid`, so the key must fold in the item KIND + payload,
 * not the uuid alone, to dedupe backfill/reconnect overlap without collapsing
 * distinct blocks.
 *
 * <p>UC-41: a tool call is ONE merged [ConversationItem.ToolActivity] row keyed on
 * its `toolUseId` (uuid-INDEPENDENT) so the `tool_use` and its `tool_result` —
 * which carry DIFFERENT transcript uuids — fold into a single bubble (AC4). Pure
 * data — no Android deps.
 */
class ConversationModelTest {

    private fun activity(
        uuid: String = "u1",
        toolName: String = "Bash",
        toolUseId: String = "tu1",
        inputSummary: String = "ls",
        primaryText: String = "ls",
        result: ToolResultData? = null,
    ) = ConversationItem.ToolActivity(uuid, "main", false, toolName, toolUseId, inputSummary, primaryText, result)

    @Test
    fun `blocks sharing one uuid get distinct keys per kind`() {
        val uuid = "uMix"
        val thinking = ConversationItem.Thinking(uuid, "main", false, "hmm")
        val text = ConversationItem.AssistantMessage(uuid, "main", false, "answer")
        val tool = activity(uuid = uuid, toolUseId = "tu1")

        val keys = setOf(thinking.key, text.key, tool.key)
        assertThat(keys).hasSize(3) // none collide despite the shared uuid
    }

    @Test
    fun `an identical assistant block dedupes to the same key`() {
        // Backfill replays a line already shown live → same uuid + same text →
        // same key → the LinkedHashMap drops the duplicate.
        val a = ConversationItem.AssistantMessage("u1", "main", false, "hello")
        val b = ConversationItem.AssistantMessage("u1", "main", false, "hello")
        assertThat(a.key).isEqualTo(b.key)
    }

    @Test
    fun `different text under the same uuid yields different keys`() {
        val a = ConversationItem.AssistantMessage("u1", "main", false, "hello")
        val b = ConversationItem.AssistantMessage("u1", "main", false, "world")
        assertThat(a.key).isNotEqualTo(b.key)
    }

    // ──────────────────────── UC-41 AC4 — merge key ──────────────────────────

    @Test
    fun `tool activity keys off the toolUseId and is uuid-independent`() {
        // The tool_use and its tool_result carry DIFFERENT uuids but the SAME
        // toolUseId — the key must fold them into one merged row (AC4).
        val use = activity(uuid = "uUse", toolUseId = "tuX", inputSummary = "summary one")
        val result = activity(uuid = "uResult", toolUseId = "tuX", inputSummary = "DIFFERENT summary")
        assertThat(use.key).isEqualTo(result.key)
        assertThat(use.key).isEqualTo("toolactivity|tuX")
    }

    @Test
    fun `different tool calls get different keys`() {
        assertThat(activity(toolUseId = "tuA").key).isNotEqualTo(activity(toolUseId = "tuB").key)
    }

    @Test
    fun `the merged result data carries the error flag and summary (AC7)`() {
        val ok = ToolResultData(isError = false, summary = "done")
        val err = ToolResultData(isError = true, summary = "boom")
        assertThat(ok.isError).isFalse
        assertThat(err.isError).isTrue
        assertThat(err.summary).isEqualTo("boom")

        // A row holding a result still keys only on the toolUseId (the result folds in).
        val awaiting = activity(toolUseId = "tuM", result = null)
        val resolved = activity(toolUseId = "tuM", result = err)
        assertThat(awaiting.key).isEqualTo(resolved.key)
    }

    @Test
    fun `question and plan keys are distinct from each other and from tools`() {
        val q = ConversationItem.Question("u1", "main", false, "tuQ", emptyList())
        val p = ConversationItem.PlanApproval("u1", "main", false, "tuP", "the plan")
        val tool = activity(uuid = "u1", toolUseId = "tuQ")
        assertThat(setOf(q.key, p.key, tool.key)).hasSize(3)
    }

    @Test
    fun `subagent source and sidechain flag are carried on items`() {
        // AC17 — the renderer distinguishes teammate activity by source/isSidechain.
        val item = ConversationItem.AssistantMessage("u9", "subagent:agent-3", true, "from a teammate")
        assertThat(item.source).isEqualTo("subagent:agent-3")
        assertThat(item.isSidechain).isTrue
    }

    // ──────────────────────── UC-42 AC4 — SystemNote item ─────────────────────

    private fun note(
        uuid: String = "u1",
        source: String = "main",
        sidechain: Boolean = false,
        label: String = "Command: /clear",
        detail: String = "<command-name>/clear</command-name>",
    ) = ConversationItem.SystemNote(uuid, source, sidechain, label, detail)

    @Test
    fun `an identical system note dedupes to the same key (backfill overlap)`() {
        // AC8 — a replayed injected line folds onto the live one (same uuid + label).
        assertThat(note().key).isEqualTo(note().key)
        assertThat(note().key).isEqualTo("u1|systemnote|${"Command: /clear".hashCode()}")
    }

    @Test
    fun `system notes with different labels under one uuid get different keys`() {
        assertThat(note(label = "Command: /clear").key)
            .isNotEqualTo(note(label = "Command output").key)
    }

    @Test
    fun `a system note key never collides with the other item kinds sharing its uuid`() {
        val uuid = "uShared"
        val n = note(uuid = uuid)
        val tool = activity(uuid = uuid, toolUseId = "tuShared")
        val text = ConversationItem.AssistantMessage(uuid, "main", false, "answer")
        val user = ConversationItem.UserMessage(uuid, "user", false, "a real prompt")
        assertThat(setOf(n.key, tool.key, text.key, user.key)).hasSize(4)
    }

    @Test
    fun `system note carries its source, sidechain flag, label and inline detail (AC9)`() {
        val n = note(source = "subagent:agent-5", sidechain = true, label = "System note", detail = "housekeeping")
        assertThat(n.source).isEqualTo("subagent:agent-5")
        assertThat(n.isSidechain).isTrue
        assertThat(n.label).isEqualTo("System note")
        assertThat(n.detail).isEqualTo("housekeeping") // inline — no fetch-detail round-trip
    }

    // ──────────────────────── UC-45 — optimistic local-echo key ───────────────

    @Test
    fun `an optimistic user message keys off localSeq, stable across the reconcile copy`() {
        // AC3 — the optimistic bubble's key derives from localSeq ONLY, so reconciling the
        // server's uuid/text/source in place (localSeq kept) leaves the key UNCHANGED.
        // Compose then updates the existing row instead of remove+re-add → no flicker.
        val optimistic = ConversationItem.UserMessage(
            uuid = "", source = "main", isSidechain = false, text = "hello", localSeq = 0L,
        )
        assertThat(optimistic.key).isEqualTo("localuser|0")
        val reconciled = optimistic.copy(uuid = "u1", text = "hello (server-normalized)", source = "main")
        assertThat(reconciled.key).isEqualTo(optimistic.key) // unchanged despite uuid+text change
        assertThat(reconciled.key).isEqualTo("localuser|0")
    }

    @Test
    fun `a server-origin user message keys off uuid+text, distinct from the optimistic key`() {
        // A confirmed/server line (localSeq == null) uses the original uuid+text key — and that
        // is exactly the form the controller records in reconciledServerKeys to dedupe a replay.
        val server = ConversationItem.UserMessage("u1", "main", false, "hello") // localSeq defaults null
        assertThat(server.key).isEqualTo("u1|user|${"hello".hashCode()}")
        val optimistic = ConversationItem.UserMessage("", "main", false, "hello", localSeq = 0L)
        assertThat(server.key).isNotEqualTo(optimistic.key)
    }

    @Test
    fun `distinct submissions get distinct optimistic keys`() {
        // AC5 — two rapid submits with the SAME text must not collide: the monotonic localSeq
        // disambiguates them so both bubbles survive.
        val a = ConversationItem.UserMessage("", "main", false, "x", localSeq = 0L)
        val b = ConversationItem.UserMessage("", "main", false, "x", localSeq = 1L)
        assertThat(a.key).isNotEqualTo(b.key)
    }
}
