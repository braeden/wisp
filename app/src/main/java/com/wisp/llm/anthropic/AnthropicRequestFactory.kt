package com.wisp.llm.anthropic

import com.wisp.llm.ContentBlock
import com.wisp.llm.ContextManagement
import com.wisp.llm.Effort
import com.wisp.llm.LlmMessage
import com.wisp.llm.LlmRequest
import com.wisp.llm.Role
import com.wisp.llm.Speed
import com.wisp.llm.SystemBlock
import com.wisp.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds Anthropic Messages-API request bodies (JSON) from the model-agnostic
 * [LlmRequest]. All Anthropic wire shapes live here and in [AnthropicLlmClient];
 * nothing leaks into `com.wisp.llm`.
 */
internal object AnthropicRequestFactory {
    /**
     * All beta headers this [request] needs: context-management edits plus
     * fast-mode (phase-12), deduped.
     */
    fun betaHeaders(request: LlmRequest): List<String> =
        (
            betaHeaders(request.contextManagement) +
                fastModeBetas(request) +
                advisorBetas(request)
        ).distinct()

    /** Beta headers required for the request's context-management edits, if any. */
    fun betaHeaders(cm: ContextManagement?): List<String> {
        if (cm == null || cm.isEmpty) return emptyList()
        val betas = mutableListOf<String>()
        if (cm.clearToolUses || cm.keepLastToolUses != null) betas += BETA_CONTEXT_MANAGEMENT
        if (cm.compact) betas += BETA_COMPACT
        return betas
    }

    /** Fast-mode beta header when [fastModeApplies] (phase-12). */
    fun fastModeBetas(request: LlmRequest): List<String> =
        if (fastModeApplies(request)) listOf(BETA_FAST_MODE) else emptyList()

    /** Advisor-tool beta header when an `advisor_*` provider tool is advertised. */
    fun advisorBetas(request: LlmRequest): List<String> =
        if (request.tools.any { it is ToolSpec.ProviderTool && it.type.startsWith("advisor_") }) {
            listOf(BETA_ADVISOR_TOOL)
        } else {
            emptyList()
        }

    /**
     * Fast mode is only sent for [Speed.FAST] on Opus 4.8/4.7; on any other model
     * `speed:"fast"` is a 400, so we degrade to standard silently.
     */
    fun fastModeApplies(request: LlmRequest): Boolean =
        request.speed == Speed.FAST && request.model in FAST_MODE_MODELS

    /** Body for `POST /v1/messages`. */
    fun messagesBody(
        json: Json,
        request: LlmRequest,
        stream: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            if (stream) put("stream", true)
            if (fastModeApplies(request)) put("speed", "fast")
            putThinking(request.thinkingAdaptive)
            putEffort(request.effort)
            putSystem(request.system)
            putTools(json, request.tools)
            put("messages", messagesArray(json, request.messages))
            putContextManagement(request.contextManagement)
        }

    /** Body for `POST /v1/messages/count_tokens` (no `max_tokens` / `stream`). */
    fun countTokensBody(
        json: Json,
        request: LlmRequest,
    ): JsonObject =
        buildJsonObject {
            put("model", request.model)
            putThinking(request.thinkingAdaptive)
            putSystem(request.system)
            putTools(json, request.tools)
            put("messages", messagesArray(json, request.messages))
        }

    private fun JsonObjectBuilder.putThinking(adaptive: Boolean) {
        putJsonObject("thinking") {
            if (adaptive) {
                put("type", "adaptive")
                // Surface reasoning so ThinkingDelta events carry text.
                put("display", "summarized")
            } else {
                put("type", "disabled")
            }
        }
    }

    private fun JsonObjectBuilder.putEffort(effort: Effort?) {
        if (effort == null) return
        putJsonObject("output_config") { put("effort", effort.wire) }
    }

    private fun JsonObjectBuilder.putSystem(system: List<SystemBlock>) {
        if (system.isEmpty()) return
        // Cache the stable prefix: put one cache_control breakpoint on the last
        // block flagged cacheable (caches tools+system up to that point).
        val lastCacheable = system.indexOfLast { it.cacheable }
        putJsonArray("system") {
            system.forEachIndexed { i, block ->
                addJsonObject {
                    put("type", "text")
                    put("text", block.text)
                    if (i == lastCacheable) putEphemeralCacheControl()
                }
            }
        }
    }

    private fun JsonObjectBuilder.putTools(
        json: Json,
        tools: List<ToolSpec>,
    ) {
        if (tools.isEmpty()) return
        val last = tools.lastIndex
        putJsonArray("tools") {
            tools.forEachIndexed { i, tool ->
                addJsonObject {
                    when (tool) {
                        is ToolSpec.ClientTool -> {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.parseToJsonElement(tool.inputSchemaJson))
                            if (tool.strict) put("strict", true)
                        }
                        // Provider tools (e.g. memory_20250818, web_search_20260209)
                        // are passthrough: {type, name}; the provider owns their
                        // schema. The advisor tool additionally names its model.
                        is ToolSpec.ProviderTool -> {
                            put("type", tool.type)
                            put("name", tool.name)
                            tool.model?.let { put("model", it) }
                        }
                    }
                    // Cache the (deterministic) tool prefix.
                    if (i == last) putEphemeralCacheControl()
                }
            }
        }
    }

    private fun messagesArray(
        json: Json,
        messages: List<LlmMessage>,
    ): JsonArray =
        buildJsonArray {
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role.wire)
                    put("content", contentArray(json, message.content))
                }
            }
        }

    private fun contentArray(
        json: Json,
        blocks: List<ContentBlock>,
    ): JsonArray =
        buildJsonArray {
            blocks.forEach { add(contentBlock(json, it)) }
        }

    private fun contentBlock(
        json: Json,
        block: ContentBlock,
    ): JsonObject =
        when (block) {
            is ContentBlock.Text ->
                buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }

            is ContentBlock.Image ->
                buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", block.mediaType)
                        put("data", block.base64)
                    }
                }

            is ContentBlock.Thinking ->
                buildJsonObject {
                    put("type", "thinking")
                    put("thinking", block.text)
                    // Signature must be replayed verbatim on the same model.
                    block.signature?.let { put("signature", it) }
                }

            is ContentBlock.ToolUse ->
                buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    // inputJson is a raw JSON object string; embed it as JSON, not a string.
                    put("input", parseObjectOrEmpty(json, block.inputJson))
                }

            is ContentBlock.ToolResult ->
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", block.toolUseId)
                    put("content", contentArray(json, block.content))
                    if (block.isError) put("is_error", true)
                }

            // Provider-owned block (server_tool_use / *_tool_result): replay the
            // wire JSON verbatim so server-tool turns continue faithfully.
            is ContentBlock.Raw -> parseObjectOrEmpty(json, block.json)
        }

    private fun JsonObjectBuilder.putContextManagement(cm: ContextManagement?) {
        if (cm == null || cm.isEmpty) return
        putJsonObject("context_management") {
            putJsonArray("edits") {
                if (cm.clearToolUses || cm.keepLastToolUses != null) {
                    addJsonObject {
                        put("type", "clear_tool_uses_20250919")
                        cm.keepLastToolUses?.let { keep ->
                            putJsonObject("keep") {
                                put("type", "tool_uses")
                                put("value", keep)
                            }
                        }
                    }
                }
                if (cm.compact) {
                    addJsonObject { put("type", "compact_20260112") }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putEphemeralCacheControl() {
        putJsonObject("cache_control") { put("type", "ephemeral") }
    }

    private fun parseObjectOrEmpty(
        json: Json,
        raw: String,
    ): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private val Effort.wire: String
        get() =
            when (this) {
                Effort.LOW -> "low"
                Effort.MEDIUM -> "medium"
                Effort.HIGH -> "high"
                Effort.XHIGH -> "xhigh"
                Effort.MAX -> "max"
            }

    private val Role.wire: String
        get() =
            when (this) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
                // Mid-conversation system turn (phase-12).
                Role.SYSTEM -> "system"
            }

    const val BETA_CONTEXT_MANAGEMENT = "context-management-2025-06-27"
    const val BETA_COMPACT = "compact-2026-01-12"
    const val BETA_FAST_MODE = "fast-mode-2026-02-01"
    const val BETA_ADVISOR_TOOL = "advisor-tool-2026-03-01"

    /** Models on which fast mode is accepted (phase-12). */
    val FAST_MODE_MODELS = setOf("claude-opus-4-8", "claude-opus-4-7")
}
