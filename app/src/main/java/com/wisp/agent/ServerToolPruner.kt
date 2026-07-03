package com.wisp.agent

import com.wisp.llm.ContentBlock
import com.wisp.llm.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drops provider-owned blocks from replayed history when the server tool that
 * produced them is no longer advertised — the API rejects e.g. an
 * `advisor_tool_result` in history while the advisor tool is absent from
 * `tools`. This is what makes a mid-session model swap safe (sonnet→opus loses
 * the advisor; opus→haiku loses web_fetch): the orphaned activity is pruned
 * instead of 400ing the whole session. Unknown raw block types are kept.
 */
object ServerToolPruner {
    fun prune(
        json: Json,
        messages: List<LlmMessage>,
        advertisedToolNames: Set<String>,
    ): List<LlmMessage> =
        messages
            .map { message ->
                message.copy(
                    content =
                        message.content.filterNot {
                            it is ContentBlock.Raw && isOrphaned(json, it, advertisedToolNames)
                        },
                )
            }
            // A turn that was purely server-tool activity can end up empty;
            // an empty content array is invalid, so drop the message.
            .filter { it.content.isNotEmpty() }

    private fun isOrphaned(
        json: Json,
        block: ContentBlock.Raw,
        advertised: Set<String>,
    ): Boolean {
        val name = serverToolName(json, block) ?: return false
        return name !in advertised
    }

    /** The server tool a raw block belongs to, or null when unrecognizable. */
    private fun serverToolName(
        json: Json,
        block: ContentBlock.Raw,
    ): String? {
        val obj = runCatching { json.parseToJsonElement(block.json).jsonObject }.getOrNull()
        val type = obj?.get("type")?.jsonPrimitive?.content
        return when {
            type == "server_tool_use" -> obj["name"]?.jsonPrimitive?.content
            type != null && type.endsWith("_tool_result") -> type.removeSuffix("_tool_result")
            else -> null
        }
    }
}
