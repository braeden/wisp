package com.wisp.agent

import com.wisp.llm.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsCatalogTest {
    private fun provider(
        model: String,
        name: String,
    ): ToolSpec.ProviderTool? =
        AgentTools
            .catalog(model)
            .filterIsInstance<ToolSpec.ProviderTool>()
            .firstOrNull { it.name == name }

    @Test
    fun `no model means no server tools`() {
        val providers = AgentTools.catalog().filterIsInstance<ToolSpec.ProviderTool>()
        assertEquals(listOf(AgentTools.MEMORY), providers.map { it.name })
    }

    @Test
    fun `opus gets dynamic web tools but no advisor`() {
        val model = "claude-opus-4-8"
        assertEquals(AgentTools.WEB_SEARCH_TYPE, provider(model, AgentTools.WEB_SEARCH)?.type)
        assertEquals(AgentTools.WEB_FETCH_TYPE, provider(model, AgentTools.WEB_FETCH)?.type)
        assertEquals(null, provider(model, AgentTools.ADVISOR))
    }

    @Test
    fun `sonnet gets dynamic web tools and an opus advisor`() {
        val model = "claude-sonnet-5"
        assertEquals(AgentTools.WEB_SEARCH_TYPE, provider(model, AgentTools.WEB_SEARCH)?.type)
        assertEquals(AgentTools.WEB_FETCH_TYPE, provider(model, AgentTools.WEB_FETCH)?.type)
        val advisor = provider(model, AgentTools.ADVISOR)
        assertEquals(AgentTools.ADVISOR_TYPE, advisor?.type)
        assertEquals(AgentTools.ADVISOR_MODEL, advisor?.model)
    }

    @Test
    fun `haiku gets basic web search, no web fetch, and an opus advisor`() {
        val model = "claude-haiku-4-5-20251001"
        assertEquals(
            AgentTools.WEB_SEARCH_TYPE_BASIC,
            provider(model, AgentTools.WEB_SEARCH)?.type,
        )
        assertEquals(null, provider(model, AgentTools.WEB_FETCH))
        assertEquals(AgentTools.ADVISOR_MODEL, provider(model, AgentTools.ADVISOR)?.model)
    }

    @Test
    fun `server tools never collide with client tool names`() {
        val clientNames =
            AgentTools
                .catalog()
                .filterIsInstance<ToolSpec.ClientTool>()
                .map { it.name }
                .toSet()
        listOf(AgentTools.WEB_SEARCH, AgentTools.WEB_FETCH, AgentTools.ADVISOR).forEach {
            assertFalse("$it must not shadow a client tool", it in clientNames)
        }
    }

    @Test
    fun `catalog is stable for a given model`() {
        assertTrue(AgentTools.catalog("claude-sonnet-5") == AgentTools.catalog("claude-sonnet-5"))
    }
}
