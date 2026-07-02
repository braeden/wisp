package com.assist.data

import com.assist.llm.ContentBlock
import com.assist.llm.LlmMessage
import com.assist.llm.Role
import com.assist.llm.Usage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * The session store: create / resume / list sessions, append messages, tool
 * calls, usage, notes, and screenshots, and rebuild the conversation for the
 * next LLM request.
 *
 * Screenshots are persisted as **files** ([ScreenshotStore]); message rows hold
 * only [StoredBlock.ImageRef] pointers to [MediaEntity]. [buildLlmMessages] is
 * the seam that makes context shrink real — it honors each media row's `dropped`
 * flag, so the next request is rebuilt cheaper without mutating message text.
 *
 * Consumed by phases 06 (agent loop / tool router) and 07 (overlay HUD).
 */
class SessionRepository(
    private val db: AssistDatabase,
    private val screenshotStore: ScreenshotStore,
    private val costCalculator: CostCalculator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val sessions get() = db.sessionDao()
    private val messages get() = db.messageDao()
    private val toolCalls get() = db.toolCallDao()
    private val usage get() = db.usageDao()
    private val media get() = db.mediaDao()
    private val notes get() = db.noteDao()

    // --- Session lifecycle -------------------------------------------------

    suspend fun createSession(
        title: String,
        model: String = DEFAULT_MODEL,
        systemPromptVersion: Int = 1,
    ): SessionEntity {
        val ts = now()
        val entity = SessionEntity(
            title = title,
            createdAt = ts,
            updatedAt = ts,
            modelDefault = model,
            status = SessionStatus.ACTIVE,
            systemPromptVersion = systemPromptVersion,
        )
        val id = sessions.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun getSession(id: Long): SessionEntity? = sessions.getById(id)

    fun listSessions(): Flow<List<SessionEntity>> = sessions.observeAll()

    /** Mark [id] as the most-recently-touched session and return it. */
    suspend fun resumeSession(id: Long): SessionEntity? {
        sessions.touch(id, now())
        return sessions.getById(id)
    }

    suspend fun renameSession(id: Long, title: String) = sessions.rename(id, title, now())

    suspend fun endSession(id: Long) = sessions.setStatus(id, SessionStatus.ENDED, now())

    // --- Appends -----------------------------------------------------------

    /**
     * Append a user/assistant turn. Any [ContentBlock.Image] in [content] (or in
     * a nested tool result) is written to disk as a [MediaEntity] and stored as
     * an [StoredBlock.ImageRef] — base64 never lands in the row.
     */
    suspend fun appendMessage(
        sessionId: Long,
        role: Role,
        content: List<ContentBlock>,
        kind: String = MessageKind.MESSAGE,
    ): MessageEntity {
        val roleString = if (role == Role.ASSISTANT) MessageRole.ASSISTANT else MessageRole.USER
        return insertMessage(sessionId, roleString, content, kind)
    }

    suspend fun appendToolCall(
        sessionId: Long,
        messageId: Long?,
        name: String,
        argsJson: String,
        resultJson: String?,
        success: Boolean,
        durationMs: Long,
    ): ToolCallEntity {
        val entity = ToolCallEntity(
            sessionId = sessionId,
            messageId = messageId,
            name = name,
            argsJson = argsJson,
            resultJson = resultJson,
            success = success,
            durationMs = durationMs,
            createdAt = now(),
        )
        val id = toolCalls.insert(entity)
        touch(sessionId)
        return entity.copy(id = id)
    }

    /** Record token [usage] for [model], pricing it via [CostCalculator]. */
    suspend fun recordUsage(
        sessionId: Long,
        messageId: Long?,
        model: String,
        usage: Usage,
    ): UsageEntity {
        val entity = UsageEntity(
            sessionId = sessionId,
            messageId = messageId,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheReadTokens = usage.cacheReadTokens,
            cacheWriteTokens = usage.cacheWriteTokens,
            costUsd = costCalculator.usageCost(model, usage),
            createdAt = now(),
        )
        val id = this.usage.insert(entity)
        touch(sessionId)
        return entity.copy(id = id)
    }

    suspend fun addNote(sessionId: Long, text: String): NoteEntity {
        val entity = NoteEntity(sessionId = sessionId, text = text, createdAt = now())
        val id = notes.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun listNotes(sessionId: Long): List<NoteEntity> = notes.getForSession(sessionId)

    /** Persist screenshot [bytes] as a file + [MediaEntity] row and return it. */
    suspend fun saveScreenshot(
        sessionId: Long,
        bytes: ByteArray,
        mediaType: String = "image/png",
    ): MediaEntity {
        val path = withContext(ioDispatcher) {
            screenshotStore.write(sessionId, bytes, extensionFor(mediaType))
        }
        val entity = MediaEntity(
            sessionId = sessionId,
            path = path,
            kind = MediaKind.SCREENSHOT,
            createdAt = now(),
            dropped = false,
        )
        val id = media.insert(entity)
        return entity.copy(id = id)
    }

    // --- Reconstruction ----------------------------------------------------

    /**
     * Rebuild the conversation as a `List<LlmMessage>` for a fresh request.
     * Dropped screenshots are replaced with a short placeholder text block; live
     * ones are reloaded from disk and re-encoded to base64. A message whose only
     * content was a dropped image still yields the placeholder, so ordering is
     * preserved.
     */
    suspend fun buildLlmMessages(sessionId: Long): List<LlmMessage> {
        val rows = messages.getForSession(sessionId)
        val mediaById = media.getForSession(sessionId).associateBy { it.id }

        return rows.map { row ->
            val blocks = MessageContentCodec.decode(row.contentJson)
            val content = withContext(ioDispatcher) {
                blocks.map { toContentBlock(it, mediaById) }
            }
            val role = if (row.role == MessageRole.ASSISTANT) Role.ASSISTANT else Role.USER
            LlmMessage(role = role, content = content)
        }
    }

    // --- Context accounting ------------------------------------------------

    suspend fun sessionCost(sessionId: Long): Double = usage.sessionCost(sessionId)

    /** Total spend across all sessions since local midnight. */
    suspend fun todaySpend(): Double {
        val startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return usage.costSince(startOfDay)
    }

    // --- Context shrink ----------------------------------------------------

    /**
     * Mark all screenshots for [sessionId] as dropped except the most recent
     * [keepLast]. Dropped rows are excluded from [buildLlmMessages] (placeholdered)
     * and from [ContextTracker] token/screenshot counts.
     */
    suspend fun markScreenshotsDropped(sessionId: Long, keepLast: Int = 0) {
        val liveNewestFirst = media.liveIdsNewestFirst(sessionId, MediaKind.SCREENSHOT)
        val toDrop = liveNewestFirst.drop(keepLast.coerceAtLeast(0))
        if (toDrop.isNotEmpty()) media.markDropped(toDrop)
    }

    /**
     * Local compaction: replace the current message span with a single summary
     * note. Keeps the newest [keepLast] messages (default 0 = compact everything)
     * and inserts the [summary] as a `system-note` message ahead of them.
     * [NoteEntity] rows are never touched.
     */
    suspend fun summarizeAndCompact(sessionId: Long, summary: String, keepLast: Int = 0) {
        val rows = messages.getForSession(sessionId)
        val keep = keepLast.coerceIn(0, rows.size)
        val cutoffSeq = if (keep == 0) Int.MAX_VALUE else rows[rows.size - keep].seq

        messages.deleteBeforeSeq(sessionId, cutoffSeq)

        // Insert the summary just before the retained tail (or at 0 if none kept).
        val summarySeq = if (cutoffSeq == Int.MAX_VALUE) 0 else cutoffSeq - 1
        val json = MessageContentCodec.encode(listOf(StoredBlock.Text(SUMMARY_PREFIX + summary)))
        messages.insert(
            MessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM_NOTE,
                seq = summarySeq,
                createdAt = now(),
                contentJson = json,
                kind = MessageKind.SUMMARY,
            ),
        )
        touch(sessionId)
    }

    // --- Internals ---------------------------------------------------------

    private suspend fun insertMessage(
        sessionId: Long,
        roleString: String,
        content: List<ContentBlock>,
        kind: String,
    ): MessageEntity {
        val seq = (messages.maxSeq(sessionId) ?: -1) + 1
        val stored = content.map { toStored(sessionId, it) }
        val json = MessageContentCodec.encode(stored)
        val entity = MessageEntity(
            sessionId = sessionId,
            role = roleString,
            seq = seq,
            createdAt = now(),
            contentJson = json,
            kind = kind,
        )
        val id = messages.insert(entity)
        touch(sessionId)
        return entity.copy(id = id)
    }

    /** Convert an inbound [ContentBlock] to its [StoredBlock] form, persisting images to disk. */
    private suspend fun toStored(sessionId: Long, block: ContentBlock): StoredBlock = when (block) {
        is ContentBlock.Text -> StoredBlock.Text(block.text)
        is ContentBlock.Thinking -> StoredBlock.Thinking(block.text, block.signature)
        is ContentBlock.ToolUse -> StoredBlock.ToolUse(block.id, block.name, block.inputJson)
        is ContentBlock.ToolResult -> StoredBlock.ToolResult(
            toolUseId = block.toolUseId,
            content = block.content.map { toStored(sessionId, it) },
            isError = block.isError,
        )
        is ContentBlock.Image -> {
            val bytes = Base64.getDecoder().decode(block.base64)
            val saved = saveScreenshot(sessionId, bytes, block.mediaType)
            StoredBlock.ImageRef(mediaId = saved.id, mediaType = block.mediaType)
        }
    }

    /** Convert a stored block back to a [ContentBlock], honoring dropped media. */
    private fun toContentBlock(
        block: StoredBlock,
        mediaById: Map<Long, MediaEntity>,
    ): ContentBlock = when (block) {
        is StoredBlock.Text -> ContentBlock.Text(block.text)
        is StoredBlock.Thinking -> ContentBlock.Thinking(block.text, block.signature)
        is StoredBlock.ToolUse -> ContentBlock.ToolUse(block.id, block.name, block.inputJson)
        is StoredBlock.ToolResult -> ContentBlock.ToolResult(
            toolUseId = block.toolUseId,
            content = block.content.map { toContentBlock(it, mediaById) },
            isError = block.isError,
        )
        is StoredBlock.ImageRef -> {
            val entity = mediaById[block.mediaId]
            val bytes = if (entity != null && !entity.dropped) {
                screenshotStore.readBytes(entity.path)
            } else {
                null
            }
            if (bytes != null) {
                ContentBlock.Image(
                    base64 = Base64.getEncoder().encodeToString(bytes),
                    mediaType = block.mediaType,
                )
            } else {
                ContentBlock.Text(DROPPED_SCREENSHOT_PLACEHOLDER)
            }
        }
    }

    private suspend fun touch(sessionId: Long) = sessions.touch(sessionId, now())

    private fun extensionFor(mediaType: String): String = when (mediaType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-4-8"
        const val DROPPED_SCREENSHOT_PLACEHOLDER = "[screenshot dropped to save context]"
        private const val SUMMARY_PREFIX = "[Earlier conversation summarized]\n"
    }
}
