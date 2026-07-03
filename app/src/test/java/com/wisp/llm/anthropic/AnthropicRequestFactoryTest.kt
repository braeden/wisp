package com.wisp.llm.anthropic

import com.wisp.llm.ContentBlock
import com.wisp.llm.LlmMessage
import com.wisp.llm.LlmRequest
import com.wisp.llm.Role
import com.wisp.llm.Speed
import com.wisp.llm.SystemBlock
import com.wisp.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicRequestFactoryTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun request(
        model: String = "claude-opus-4-8",
        speed: Speed = Speed.STANDARD,
        tools: List<ToolSpec> = emptyList(),
        messages: List<LlmMessage> =
            listOf(
                LlmMessage(Role.USER, listOf(ContentBlock.Text("hi"))),
            ),
    ) = LlmRequest(
        model = model,
        system = listOf(SystemBlock("sys", cacheable = true)),
        messages = messages,
        tools = tools,
        maxTokens = 100,
        speed = speed,
    )

    private fun body(request: LlmRequest): JsonObject =
        AnthropicRequestFactory.messagesBody(json, request, stream = true)

    @Test
    fun `fast mode sends speed and beta on opus-4-8`() {
        val req = request(model = "claude-opus-4-8", speed = Speed.FAST)
        assertEquals("fast", body(req)["speed"]?.jsonPrimitive?.content)
        assertTrue(
            AnthropicRequestFactory
                .betaHeaders(
                    req,
                ).contains(AnthropicRequestFactory.BETA_FAST_MODE),
        )
    }

    @Test
    fun `fast mode is dropped on non-opus models`() {
        val req = request(model = "claude-sonnet-5", speed = Speed.FAST)
        assertNull(body(req)["speed"])
        assertFalse(
            AnthropicRequestFactory
                .betaHeaders(
                    req,
                ).contains(AnthropicRequestFactory.BETA_FAST_MODE),
        )
    }

    @Test
    fun `standard speed sends no speed field`() {
        assertNull(body(request(speed = Speed.STANDARD))["speed"])
    }

    @Test
    fun `client tool maps to a schema tool and honors strict`() {
        val req =
            request(
                tools =
                    listOf(
                        ToolSpec.ClientTool(
                            name = "tap",
                            description = "tap it",
                            inputSchemaJson = """{"type":"object","properties":{},"required":[]}""",
                            strict = true,
                        ),
                    ),
            )
        val tool = (body(req)["tools"] as JsonArray).single().jsonObject
        assertEquals("tap", tool["name"]?.jsonPrimitive?.content)
        assertTrue(tool.containsKey("input_schema"))
        assertEquals(true, tool["strict"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `provider tool maps to type and name passthrough`() {
        val req =
            request(
                tools = listOf(ToolSpec.ProviderTool(type = "memory_20250818", name = "memory")),
            )
        val tool = (body(req)["tools"] as JsonArray).single().jsonObject
        assertEquals("memory_20250818", tool["type"]?.jsonPrimitive?.content)
        assertEquals("memory", tool["name"]?.jsonPrimitive?.content)
        assertFalse(tool.containsKey("input_schema"))
    }

    @Test
    fun `advisor provider tool carries its model and beta header`() {
        val req =
            request(
                model = "claude-sonnet-5",
                tools =
                    listOf(
                        ToolSpec.ProviderTool(
                            type = "advisor_20260301",
                            name = "advisor",
                            model = "claude-opus-4-8",
                        ),
                    ),
            )
        val tool = (body(req)["tools"] as JsonArray).single().jsonObject
        assertEquals("advisor_20260301", tool["type"]?.jsonPrimitive?.content)
        assertEquals("claude-opus-4-8", tool["model"]?.jsonPrimitive?.content)
        assertTrue(
            AnthropicRequestFactory
                .betaHeaders(req)
                .contains(AnthropicRequestFactory.BETA_ADVISOR_TOOL),
        )
    }

    @Test
    fun `non-advisor provider tools add no advisor beta and no model field`() {
        val req =
            request(
                tools =
                    listOf(
                        ToolSpec.ProviderTool(type = "web_search_20260209", name = "web_search"),
                    ),
            )
        val tool = (body(req)["tools"] as JsonArray).single().jsonObject
        assertFalse(tool.containsKey("model"))
        assertFalse(
            AnthropicRequestFactory
                .betaHeaders(req)
                .contains(AnthropicRequestFactory.BETA_ADVISOR_TOOL),
        )
    }

    @Test
    fun `raw content block is replayed verbatim`() {
        val rawJson =
            """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search",""" +
                """"input":{"query":"weather"}}"""
        val req =
            request(
                messages =
                    listOf(
                        LlmMessage(Role.ASSISTANT, listOf(ContentBlock.Raw(rawJson))),
                    ),
            )
        val block =
            (body(req)["messages"] as JsonArray)
                .single()
                .jsonObject["content"]!!
                .let { it as JsonArray }
                .single()
                .jsonObject
        assertEquals("server_tool_use", block["type"]?.jsonPrimitive?.content)
        assertEquals("srvtoolu_1", block["id"]?.jsonPrimitive?.content)
        assertEquals(
            "weather",
            block["input"]
                ?.jsonObject
                ?.get("query")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `system role message maps to role system`() {
        val req =
            request(
                messages =
                    listOf(
                        LlmMessage(Role.USER, listOf(ContentBlock.Text("hi"))),
                        LlmMessage(
                            Role.SYSTEM,
                            listOf(ContentBlock.Text("New input arrived: stop")),
                        ),
                    ),
            )
        val roles =
            (body(req)["messages"] as JsonArray).map {
                it.jsonObject["role"]?.jsonPrimitive?.content
            }
        assertEquals(listOf("user", "system"), roles)
    }
}
