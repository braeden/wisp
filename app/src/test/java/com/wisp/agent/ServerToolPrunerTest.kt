package com.wisp.agent

import com.wisp.llm.ContentBlock
import com.wisp.llm.LlmMessage
import com.wisp.llm.Role
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerToolPrunerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val advisorUse =
        ContentBlock.Raw(
            """{"type":"server_tool_use","id":"s1","name":"advisor","input":{"query":"plan?"}}""",
        )
    private val advisorResult =
        ContentBlock.Raw("""{"type":"advisor_tool_result","tool_use_id":"s1","content":[]}""")
    private val webFetchResult =
        ContentBlock.Raw("""{"type":"web_fetch_tool_result","tool_use_id":"s2","content":{}}""")

    private fun assistant(vararg blocks: ContentBlock) = LlmMessage(Role.ASSISTANT, blocks.toList())

    @Test
    fun `advisor blocks are dropped when advisor is not advertised`() {
        val messages =
            listOf(assistant(ContentBlock.Text("thinking about it"), advisorUse, advisorResult))
        val pruned = ServerToolPruner.prune(json, messages, setOf("tap", "web_search"))
        assertEquals(
            listOf<ContentBlock>(ContentBlock.Text("thinking about it")),
            pruned.single().content,
        )
    }

    @Test
    fun `advisor blocks are kept when advisor is advertised`() {
        val messages = listOf(assistant(advisorUse, advisorResult))
        val pruned = ServerToolPruner.prune(json, messages, setOf("advisor"))
        assertEquals(2, pruned.single().content.size)
    }

    @Test
    fun `a message left empty by pruning is dropped entirely`() {
        val messages =
            listOf(
                LlmMessage(Role.USER, listOf(ContentBlock.Text("do it"))),
                assistant(webFetchResult),
                LlmMessage(Role.USER, listOf(ContentBlock.Text("ok"))),
            )
        val pruned = ServerToolPruner.prune(json, messages, setOf("web_search"))
        assertEquals(2, pruned.size)
        assertTrue(pruned.all { it.role == Role.USER })
    }

    @Test
    fun `unknown raw blocks and core blocks are untouched`() {
        val mystery = ContentBlock.Raw("""{"type":"compaction","content":"…"}""")
        val messages =
            listOf(assistant(ContentBlock.Text("t"), mystery, ContentBlock.Thinking("h")))
        val pruned = ServerToolPruner.prune(json, messages, emptySet())
        assertEquals(3, pruned.single().content.size)
    }

    @Test
    fun `malformed raw json is kept`() {
        val broken = ContentBlock.Raw("not json")
        val pruned = ServerToolPruner.prune(json, listOf(assistant(broken)), emptySet())
        assertEquals(1, pruned.single().content.size)
    }
}
