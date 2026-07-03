package com.wisp.llm.anthropic

import com.wisp.data.SecretStore
import com.wisp.llm.ContentBlock
import com.wisp.llm.ContextManagement
import com.wisp.llm.LlmClient
import com.wisp.llm.LlmRequest
import com.wisp.llm.LlmResponse
import com.wisp.llm.LlmStreamEvent
import com.wisp.llm.ToolCall
import com.wisp.llm.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Concrete Claude implementation of [LlmClient] over the Anthropic Messages API,
 * via direct HTTPS (OkHttp + kotlinx.serialization). See `README.md` in this
 * package for the SDK-vs-REST decision.
 *
 * Auth reads the key from [SecretStore] per request (`x-api-key`); the key is
 * never logged. Streaming uses SSE with cooperative cancellation — cancelling
 * the calling coroutine aborts the underlying HTTP call promptly (phase-08).
 */
class AnthropicLlmClient(
    private val secretStore: SecretStore,
    private val okHttp: OkHttpClient,
    private val json: Json = defaultJson,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LlmClient {
    override suspend fun send(
        request: LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        val body = AnthropicRequestFactory.messagesBody(json, request, stream = true)
        val httpRequest =
            buildHttpRequest(
                path = "/v1/messages",
                body = body.toString(),
                betas = AnthropicRequestFactory.betaHeaders(request),
            )
        return executeStreaming(httpRequest, onEvent)
    }

    override suspend fun countTokens(request: LlmRequest): Int {
        val body = AnthropicRequestFactory.countTokensBody(json, request)
        val httpRequest =
            buildHttpRequest(
                path = "/v1/messages/count_tokens",
                body = body.toString(),
                betas = AnthropicRequestFactory.betaHeaders(request),
            )
        val payload = executeForString(httpRequest)
        val obj = json.parseToJsonElement(payload).jsonObject
        return obj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    }

    /**
     * Convenience wrapper for the agent's `drop_old_screenshots` tool: runs the
     * turn with a context-editing edit that clears stale tool uses (screenshots),
     * optionally keeping [keepLast] most-recent ones.
     */
    suspend fun dropOldScreenshots(
        request: LlmRequest,
        keepLast: Int? = null,
        onEvent: (LlmStreamEvent) -> Unit = {},
    ): LlmResponse =
        send(
            request.copy(
                contextManagement =
                    (request.contextManagement ?: ContextManagement()).copy(
                        clearToolUses = true,
                        keepLastToolUses = keepLast,
                    ),
            ),
            onEvent,
        )

    /**
     * Convenience wrapper for the agent's `compact_conversation` tool: runs the
     * turn with server-side compaction enabled. The caller must append the full
     * response [LlmResponse.content] back to history so compaction state is
     * preserved on the next turn.
     */
    suspend fun compactConversation(
        request: LlmRequest,
        onEvent: (LlmStreamEvent) -> Unit = {},
    ): LlmResponse =
        send(
            request.copy(
                contextManagement =
                    (request.contextManagement ?: ContextManagement()).copy(
                        compact = true,
                    ),
            ),
            onEvent,
        )

    // --- HTTP ---------------------------------------------------------------

    private fun buildHttpRequest(
        path: String,
        body: String,
        betas: List<String>,
    ): Request {
        val apiKey =
            secretStore.getApiKey()
                ?: throw AnthropicAuthException("No Anthropic API key configured.")
        val builder =
            Request
                .Builder()
                .url(baseUrl + path)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
        if (betas.isNotEmpty()) builder.header("anthropic-beta", betas.joinToString(","))
        return builder.build()
    }

    private suspend fun executeForString(httpRequest: Request): String =
        withContext(Dispatchers.IO) {
            val call = okHttp.newCall(httpRequest)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw mapError(response, text)
                    text
                }
            } catch (io: IOException) {
                coroutineContext.ensureActive() // rethrow CancellationException if cancelled
                throw AnthropicNetworkException("Network error calling Anthropic API", io)
            } finally {
                cancelHandle?.dispose()
            }
        }

    private suspend fun executeStreaming(
        httpRequest: Request,
        onEvent: (LlmStreamEvent) -> Unit,
    ): LlmResponse =
        withContext(Dispatchers.IO) {
            val call = okHttp.newCall(httpRequest)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw mapError(response, response.body?.string().orEmpty())
                    }
                    parseStream(response, onEvent)
                }
            } catch (io: IOException) {
                coroutineContext.ensureActive()
                throw AnthropicNetworkException("Network error streaming from Anthropic API", io)
            } finally {
                cancelHandle?.dispose()
            }
        }

    // --- SSE parsing --------------------------------------------------------

    private suspend fun parseStream(
        response: Response,
        onEvent: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        val source =
            response.body?.source()
                ?: throw AnthropicNetworkException("Empty streaming response body")
        val accumulator = StreamAccumulator()

        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith(DATA_PREFIX)) continue
            val payload = line.substring(DATA_PREFIX.length).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val event =
                runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            handleEvent(event, accumulator, onEvent)
            if (accumulator.done) break
        }
        return accumulator.toResponse()
    }

    private fun handleEvent(
        event: JsonObject,
        acc: StreamAccumulator,
        onEvent: (LlmStreamEvent) -> Unit,
    ) {
        when (event.type) {
            "message_start" -> {
                event["message"]?.jsonObject?.get("usage")?.jsonObject?.let { usage ->
                    acc.usage = mergeUsage(acc.usage, usage)
                    onEvent(LlmStreamEvent.UsageUpdate(acc.usage))
                }
            }

            "content_block_start" -> {
                val index = event.intOrNull("index") ?: return
                val block = event["content_block"]?.jsonObject ?: return
                val builder = acc.blockAt(index, block.type ?: "")
                when (block.type) {
                    "tool_use" -> {
                        builder.toolId = block.stringOrNull("id")
                        builder.toolName = block.stringOrNull("name")
                        onEvent(
                            LlmStreamEvent.ToolUseStart(
                                id = builder.toolId.orEmpty(),
                                name = builder.toolName.orEmpty(),
                            ),
                        )
                    }
                    // A server-executed tool call (web_search / web_fetch /
                    // advisor): its input streams via input_json_delta like a
                    // client tool, but it is provider-owned — accumulated into a
                    // Raw block, never routed to the ToolRouter.
                    "server_tool_use" -> {
                        builder.toolId = block.stringOrNull("id")
                        builder.toolName = block.stringOrNull("name")
                    }
                    "text", "thinking" -> Unit
                    // Any other type (web_search_tool_result, advisor_tool_result,
                    // …) arrives complete in the start event; keep it verbatim.
                    else -> builder.raw = block
                }
            }

            "content_block_delta" -> {
                val index = event.intOrNull("index") ?: return
                val delta = event["delta"]?.jsonObject ?: return
                val builder = acc.blocks[index] ?: return
                when (delta.type) {
                    "text_delta" ->
                        delta.stringOrNull("text")?.let {
                            builder.text.append(it)
                            onEvent(LlmStreamEvent.TextDelta(it))
                        }

                    "thinking_delta" ->
                        delta.stringOrNull("thinking")?.let {
                            builder.thinking.append(it)
                            onEvent(LlmStreamEvent.ThinkingDelta(it))
                        }

                    "signature_delta" ->
                        delta.stringOrNull("signature")?.let {
                            builder.signature = (builder.signature ?: "") + it
                        }

                    "input_json_delta" ->
                        delta.stringOrNull("partial_json")?.let {
                            builder.toolInput.append(it)
                            onEvent(
                                LlmStreamEvent.ToolUseArgsDelta(
                                    id = builder.toolId.orEmpty(),
                                    partialJson = it,
                                ),
                            )
                        }
                }
            }

            "message_delta" -> {
                event["delta"]?.jsonObject?.stringOrNull("stop_reason")?.let { acc.stopReason = it }
                event["usage"]?.jsonObject?.let { usage ->
                    acc.usage = mergeUsage(acc.usage, usage)
                    onEvent(LlmStreamEvent.UsageUpdate(acc.usage))
                }
            }

            "message_stop" -> {
                acc.done = true
                onEvent(LlmStreamEvent.Done(acc.stopReason))
            }

            "error" -> {
                val err = event["error"]?.jsonObject
                throw AnthropicServerException(
                    message = err?.stringOrNull("message") ?: "Streaming error",
                    statusCode = 500,
                    errorType = err?.stringOrNull("type"),
                )
            }
        }
    }

    private fun mergeUsage(
        prev: Usage,
        usage: JsonObject,
    ): Usage =
        prev.copy(
            inputTokens = usage.intOrNull("input_tokens") ?: prev.inputTokens,
            outputTokens = usage.intOrNull("output_tokens") ?: prev.outputTokens,
            cacheReadTokens = usage.intOrNull("cache_read_input_tokens") ?: prev.cacheReadTokens,
            cacheWriteTokens =
                usage.intOrNull("cache_creation_input_tokens") ?: prev.cacheWriteTokens,
            // Fast mode (phase-12): "fast" | "standard", when the provider reports it.
            speed = usage.stringOrNull("speed") ?: prev.speed,
        )

    private fun mapError(
        response: Response,
        body: String,
    ): AnthropicException {
        val errObj =
            runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            }.getOrNull()
        val type = errObj?.stringOrNull("type")
        val message = errObj?.stringOrNull("message") ?: "HTTP ${response.code}"
        val retryAfter = response.header("retry-after")?.toLongOrNull()
        return AnthropicErrorMapper.map(response.code, type, message, retryAfter)
    }

    // --- accumulator --------------------------------------------------------

    private class BlockBuilder(
        val type: String,
    ) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null
        var toolId: String? = null
        var toolName: String? = null
        val toolInput = StringBuilder()

        /** Verbatim wire JSON for provider-owned blocks (server tool results). */
        var raw: JsonObject? = null
    }

    private class StreamAccumulator {
        val blocks = sortedMapOf<Int, BlockBuilder>()
        var usage = Usage()
        var stopReason = ""
        var done = false

        private fun parseObjectOrEmpty(raw: String): JsonObject =
            runCatching { defaultJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())

        fun blockAt(
            index: Int,
            type: String,
        ): BlockBuilder = blocks.getOrPut(index) { BlockBuilder(type) }

        fun toResponse(): LlmResponse {
            val content = mutableListOf<ContentBlock>()
            val toolCalls = mutableListOf<ToolCall>()
            val textBuilder = StringBuilder()
            for (builder in blocks.values) {
                when (builder.type) {
                    "text" -> {
                        val t = builder.text.toString()
                        if (textBuilder.isNotEmpty() && t.isNotEmpty()) textBuilder.append('\n')
                        textBuilder.append(t)
                        content += ContentBlock.Text(t)
                    }

                    "thinking" ->
                        content +=
                            ContentBlock.Thinking(
                                text = builder.thinking.toString(),
                                signature = builder.signature,
                            )

                    "tool_use" -> {
                        val id = builder.toolId.orEmpty()
                        val name = builder.toolName.orEmpty()
                        val inputJson = builder.toolInput.toString().ifEmpty { "{}" }
                        content += ContentBlock.ToolUse(id, name, inputJson)
                        toolCalls += ToolCall(id, name, inputJson)
                    }

                    // Server-executed tool call: rebuilt as a Raw block for
                    // faithful replay; intentionally NOT surfaced as a ToolCall.
                    "server_tool_use" ->
                        content +=
                            ContentBlock.Raw(
                                buildJsonObject {
                                    put("type", "server_tool_use")
                                    put("id", builder.toolId.orEmpty())
                                    put("name", builder.toolName.orEmpty())
                                    put(
                                        "input",
                                        parseObjectOrEmpty(builder.toolInput.toString()),
                                    )
                                }.toString(),
                            )

                    // Provider-owned result blocks: preserved verbatim.
                    else -> builder.raw?.let { content += ContentBlock.Raw(it.toString()) }
                }
            }
            return LlmResponse(
                text = textBuilder.toString(),
                toolCalls = toolCalls,
                stopReason = stopReason,
                usage = usage,
                content = content,
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DATA_PREFIX = "data:"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        val defaultJson: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

// --- small JSON read helpers (Anthropic-scoped) -----------------------------

private val JsonObject.type: String?
    get() = this["type"]?.jsonPrimitive?.content

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
