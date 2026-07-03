package com.assist.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Encodes / decodes a message's `List<StoredBlock>` to and from the JSON string
 * held in [MessageEntity.contentJson]. Shared by [SessionRepository] (write) and
 * [ContextTracker] (read) so both agree on the wire shape.
 */
internal object MessageContentCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val serializer = ListSerializer(StoredBlock.serializer())

    fun encode(blocks: List<StoredBlock>): String = json.encodeToString(serializer, blocks)

    fun decode(contentJson: String): List<StoredBlock> =
        json.decodeFromString(serializer, contentJson)
}
