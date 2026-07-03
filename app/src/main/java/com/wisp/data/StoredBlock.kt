package com.wisp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk representation of a [com.wisp.llm.ContentBlock], persisted in
 * [MessageEntity.contentJson]. It mirrors the model-agnostic content contract
 * but differs in one crucial way: images are stored as a **reference**
 * ([ImageRef.mediaId]) to a [MediaEntity] file, never as inline base64. That is
 * what makes "drop screenshots" cheap — the rebuild honors the media row's
 * `dropped` flag without ever loading the bytes.
 */
@Serializable
sealed interface StoredBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : StoredBlock

    /** Reference to a screenshot/media file (see [MediaEntity]). */
    @Serializable
    @SerialName("image_ref")
    data class ImageRef(
        val mediaId: Long,
        val mediaType: String = "image/png",
    ) : StoredBlock

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val signature: String? = null,
    ) : StoredBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : StoredBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String,
        val content: List<StoredBlock>,
        val isError: Boolean = false,
    ) : StoredBlock

    /**
     * Provider-owned wire block (server_tool_use / web_search_tool_result /
     * advisor_tool_result, …) preserved verbatim for faithful replay.
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val json: String,
    ) : StoredBlock
}
