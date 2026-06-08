package com.aisandbox.android.conversation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-37 AC6/AC22 — the [ConversationItem.key] dedupe contract that lets the
 * controller drop backfill/reconnect overlap without losing or double-rendering
 * messages. A single transcript line can carry several blocks (thinking + text +
 * tool_use) that share one `uuid`, so the key must fold in the item KIND +
 * payload, not the uuid alone. Pure data — no Android deps.
 */
class ConversationModelTest {

    @Test
    fun `blocks sharing one uuid get distinct keys per kind`() {
        val uuid = "uMix"
        val thinking = ConversationItem.Thinking(uuid, "main", false, "hmm")
        val text = ConversationItem.AssistantMessage(uuid, "main", false, "answer")
        val tool = ConversationItem.ToolUse(uuid, "main", false, "Bash", "tu1", "ls")

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

    @Test
    fun `tool use and tool result key off the tool-use id`() {
        val use = ConversationItem.ToolUse("u1", "main", false, "Edit", "tuX", "summary one")
        val use2 = ConversationItem.ToolUse("u1", "main", false, "Edit", "tuX", "DIFFERENT summary")
        // The input summary is not part of the key — the same tool_use line never
        // double-renders even if the summary text is recomputed.
        assertThat(use.key).isEqualTo(use2.key)

        val result = ConversationItem.ToolResult("u1", "main", false, "tuX", false, "ok")
        // A tool_use and its tool_result share uuid + toolUseId but are distinct items.
        assertThat(use.key).isNotEqualTo(result.key)
    }

    @Test
    fun `question and plan keys are distinct from each other and from tools`() {
        val q = ConversationItem.Question("u1", "main", false, "tuQ", emptyList())
        val p = ConversationItem.PlanApproval("u1", "main", false, "tuP", "the plan")
        val tool = ConversationItem.ToolUse("u1", "main", false, "Bash", "tuQ", "x")
        assertThat(setOf(q.key, p.key, tool.key)).hasSize(3)
    }

    @Test
    fun `subagent source and sidechain flag are carried on items`() {
        // AC17 — the renderer distinguishes teammate activity by source/isSidechain.
        val item = ConversationItem.AssistantMessage("u9", "subagent:agent-3", true, "from a teammate")
        assertThat(item.source).isEqualTo("subagent:agent-3")
        assertThat(item.isSidechain).isTrue
    }
}
