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
        val entity =
            SessionEntity(
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

    suspend fun renameSession(
        id: Long,
        title: String,
    ) = sessions.rename(id, title, now())

    /** Switch [id]'s current model; the agent loop re-reads it every step. */
    suspend fun setSessionModel(
        id: Long,
        model: String,
    ) = sessions.setModel(id, model, now())

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
        val roleString =
            when (role) {
                Role.ASSISTANT -> MessageRole.ASSISTANT
                Role.SYSTEM -> MessageRole.SYSTEM
                Role.USER -> MessageRole.USER
            }
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
        val entity =
            ToolCallEntity(
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
        val entity =
            UsageEntity(
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

    suspend fun addNote(
        sessionId: Long,
        text: String,
    ): NoteEntity {
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
        val path =
            withContext(ioDispatcher) {
                screenshotStore.write(sessionId, bytes, extensionFor(mediaType))
            }
        val entity =
            MediaEntity(
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

        val rebuilt =
            rows.map { row ->
                val blocks = MessageContentCodec.decode(row.contentJson)
                val content =
                    withContext(ioDispatcher) {
                        blocks.map { toContentBlock(it, mediaById) }
                    }
                val role =
                    when (row.role) {
                        MessageRole.ASSISTANT -> Role.ASSISTANT
                        // Transient mid-conversation system turns (phase-12) replay as a
                        // system role; SYSTEM_NOTE (compaction summaries) stay user context.
                        MessageRole.SYSTEM -> Role.SYSTEM
                        else -> Role.USER
                    }
                LlmMessage(role = role, content = content)
            }
        return pruneStaleScreenOutlines(repairDanglingToolUses(rebuilt))
    }

    /**
     * Screen outlines go stale the moment a newer one exists — the agent is
     * told to act only on the latest screen — yet each one is up to ~150 lines
     * resent on every request. Keep only the newest full outline (top-level
     * text or inside a `get_screen_state` tool result) and shrink every older
     * one to a one-line stub. Runs at rebuild time, so the stored rows keep the
     * full history for the transcript UI, and the tiny "(unchanged)" markers
     * the agent loop emits are never touched (they don't carry the prefix).
     */
    private fun pruneStaleScreenOutlines(messages: List<LlmMessage>): List<LlmMessage> {
        var newestKept = false

        fun prune(block: ContentBlock): ContentBlock =
            when {
                block is ContentBlock.Text && block.text.startsWith(SCREEN_OUTLINE_PREFIX) ->
                    if (newestKept) {
                        ContentBlock.Text(STALE_SCREEN_PLACEHOLDER)
                    } else {
                        newestKept = true
                        block
                    }
                block is ContentBlock.ToolResult ->
                    block.copy(
                        content =
                            block.content
                                .asReversed()
                                .map(::prune)
                                .asReversed(),
                    )
                else -> block
            }

        // Newest-to-oldest so the survivor is the most recent outline.
        return messages
            .asReversed()
            .map { msg ->
                if (msg.role == Role.ASSISTANT) {
                    msg
                } else {
                    msg.copy(
                        content =
                            msg.content
                                .asReversed()
                                .map(::prune)
                                .asReversed(),
                    )
                }
            }.asReversed()
    }

    /**
     * The API requires every `tool_use` in an assistant turn to be answered by a
     * `tool_result` in the next user turn. An interrupt (or crash) between
     * persisting the assistant message and persisting its results leaves dangling
     * `tool_use` ids, which would fail the whole request on session resume —
     * synthesize placeholder results so the conversation always replays. Runs at
     * rebuild time so it also heals sessions that were truncated in the past.
     */
    private fun repairDanglingToolUses(messages: List<LlmMessage>): List<LlmMessage> {
        val repaired = mutableListOf<LlmMessage>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            repaired += msg
            i++
            val next = messages.getOrNull(i)
            val placeholders = missingToolResults(msg, next)
            if (placeholders.isNotEmpty()) {
                if (next != null && next.role == Role.USER) {
                    // Merge into the existing follow-up user turn (results lead).
                    repaired += next.copy(content = placeholders + next.content)
                    i++
                } else {
                    // Interrupted at the very tail: answer w/ a synthetic user turn.
                    repaired += LlmMessage(role = Role.USER, content = placeholders)
                }
            }
        }
        return repaired
    }

    /** Placeholder results for [msg]'s tool_use ids that [next] doesn't answer. */
    private fun missingToolResults(
        msg: LlmMessage,
        next: LlmMessage?,
    ): List<ContentBlock> {
        val toolUseIds =
            if (msg.role == Role.ASSISTANT) {
                msg.content.filterIsInstance<ContentBlock.ToolUse>().map { it.id }
            } else {
                emptyList()
            }
        val present =
            next
                ?.takeIf { it.role == Role.USER }
                ?.content
                ?.filterIsInstance<ContentBlock.ToolResult>()
                ?.mapTo(mutableSetOf()) { it.toolUseId }
                .orEmpty()
        return toolUseIds.filterNot(present::contains).map { id ->
            ContentBlock.ToolResult(
                toolUseId = id,
                content = listOf(ContentBlock.Text(INTERRUPTED_TOOL_RESULT)),
                isError = false,
            )
        }
    }

    // --- Read models for the session-management UI (phase-12) --------------

    /** Sessions with message count + running cost, most-recent first. */
    fun observeSessionSummaries(): Flow<List<SessionSummary>> = sessions.observeSummaries()

    /** Observe a single session row (for the detail header). */
    fun observeSession(sessionId: Long): Flow<SessionEntity?> = sessions.observeById(sessionId)

    /** Observe a session's messages (drives transcript re-render). */
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> =
        messages.observeForSession(sessionId)

    /** Recorded tool calls for a session, oldest first. */
    suspend fun listToolCalls(sessionId: Long): List<ToolCallEntity> =
        toolCalls.getForSession(sessionId)

    /**
     * Decode the full conversation into a UI-friendly [TranscriptMessage] list
     * (text/thinking/tool_use/tool_result/image), honoring dropped screenshots.
     */
    suspend fun getTranscript(sessionId: Long): List<TranscriptMessage> {
        val rows = messages.getForSession(sessionId)
        val mediaById = media.getForSession(sessionId).associateBy { it.id }
        return rows.map { row ->
            val blocks =
                MessageContentCodec
                    .decode(
                        row.contentJson,
                    ).map { toTranscriptBlock(it, mediaById) }
            TranscriptMessage(
                id = row.id,
                seq = row.seq,
                role = transcriptRole(row.role, row.kind),
                kind = row.kind,
                createdAt = row.createdAt,
                blocks = blocks,
            )
        }
    }

    /** Per-model token/cost totals for a session's usage rows. */
    suspend fun aggregateUsage(sessionId: Long): UsageSummary {
        val rows = usage.getForSession(sessionId)
        val perModel =
            rows
                .groupBy { it.model }
                .map { (model, list) ->
                    ModelUsage(
                        model = model,
                        inputTokens = list.sumOf { it.inputTokens },
                        outputTokens = list.sumOf { it.outputTokens },
                        cacheReadTokens = list.sumOf { it.cacheReadTokens },
                        cacheWriteTokens = list.sumOf { it.cacheWriteTokens },
                        costUsd = list.sumOf { it.costUsd },
                        turns = list.size,
                    )
                }.sortedByDescending { it.costUsd }
        return UsageSummary(
            totalCostUsd = rows.sumOf { it.costUsd },
            totalInputTokens = rows.sumOf { it.inputTokens },
            totalOutputTokens = rows.sumOf { it.outputTokens },
            totalCacheReadTokens = rows.sumOf { it.cacheReadTokens },
            totalCacheWriteTokens = rows.sumOf { it.cacheWriteTokens },
            perModel = perModel,
        )
    }

    /** Delete a session (cascades rows) plus its screenshot files on disk. */
    suspend fun deleteSession(sessionId: Long) {
        withContext(ioDispatcher) { screenshotStore.deleteSession(sessionId) }
        sessions.deleteById(sessionId)
    }

    private fun transcriptRole(
        role: String,
        kind: String,
    ): TranscriptRole =
        when {
            role == MessageRole.ASSISTANT -> TranscriptRole.ASSISTANT
            role == MessageRole.SYSTEM -> TranscriptRole.SYSTEM
            role == MessageRole.SYSTEM_NOTE -> TranscriptRole.SYSTEM_NOTE
            kind == MessageKind.TOOL_RESULT -> TranscriptRole.TOOL_RESULT
            else -> TranscriptRole.USER
        }

    private fun toTranscriptBlock(
        block: StoredBlock,
        mediaById: Map<Long, MediaEntity>,
    ): TranscriptBlock =
        when (block) {
            is StoredBlock.Text -> TranscriptBlock.Text(block.text)
            is StoredBlock.Thinking -> TranscriptBlock.Thinking(block.text)
            is StoredBlock.ToolUse -> TranscriptBlock.ToolUse(block.id, block.name, block.inputJson)
            is StoredBlock.ToolResult ->
                TranscriptBlock.ToolResult(
                    toolUseId = block.toolUseId,
                    text = storedToText(block.content),
                    isError = block.isError,
                )
            is StoredBlock.ImageRef ->
                TranscriptBlock.Image(
                    dropped =
                        mediaById[block.mediaId]?.dropped ?: true,
                )
        }

    private fun storedToText(blocks: List<StoredBlock>): String =
        blocks.joinToString("\n") {
            when (it) {
                is StoredBlock.Text -> it.text
                is StoredBlock.Thinking -> it.text
                is StoredBlock.ToolUse -> "${it.name}(${it.inputJson})"
                is StoredBlock.ToolResult -> storedToText(it.content)
                is StoredBlock.ImageRef -> "[screenshot]"
            }
        }

    // --- Context accounting ------------------------------------------------

    suspend fun sessionCost(sessionId: Long): Double = usage.sessionCost(sessionId)

    /** Total spend across all sessions since local midnight. */
    suspend fun todaySpend(): Double {
        val startOfDay =
            LocalDate
                .now(zoneId)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        return usage.costSince(startOfDay)
    }

    // --- Context shrink ----------------------------------------------------

    /**
     * Mark all screenshots for [sessionId] as dropped except the most recent
     * [keepLast]. Dropped rows are excluded from [buildLlmMessages] (placeholdered)
     * and from [ContextTracker] token/screenshot counts.
     */
    suspend fun markScreenshotsDropped(
        sessionId: Long,
        keepLast: Int = 0,
    ) {
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
    suspend fun summarizeAndCompact(
        sessionId: Long,
        summary: String,
        keepLast: Int = 0,
    ) {
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
        val entity =
            MessageEntity(
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
    private suspend fun toStored(
        sessionId: Long,
        block: ContentBlock,
    ): StoredBlock =
        when (block) {
            is ContentBlock.Text -> StoredBlock.Text(block.text)
            is ContentBlock.Thinking -> StoredBlock.Thinking(block.text, block.signature)
            is ContentBlock.ToolUse -> StoredBlock.ToolUse(block.id, block.name, block.inputJson)
            is ContentBlock.ToolResult ->
                StoredBlock.ToolResult(
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
    ): ContentBlock =
        when (block) {
            is StoredBlock.Text -> ContentBlock.Text(block.text)
            is StoredBlock.Thinking -> ContentBlock.Thinking(block.text, block.signature)
            is StoredBlock.ToolUse -> ContentBlock.ToolUse(block.id, block.name, block.inputJson)
            is StoredBlock.ToolResult ->
                ContentBlock.ToolResult(
                    toolUseId = block.toolUseId,
                    content = block.content.map { toContentBlock(it, mediaById) },
                    isError = block.isError,
                )
            is StoredBlock.ImageRef -> {
                val entity = mediaById[block.mediaId]
                val bytes =
                    if (entity != null && !entity.dropped) {
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

    private fun extensionFor(mediaType: String): String =
        when (mediaType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-4-8"
        const val DROPPED_SCREENSHOT_PLACEHOLDER = "[screenshot dropped to save context]"
        const val INTERRUPTED_TOOL_RESULT = "[interrupted by the user before this tool call ran]"

        /** Every full screen outline sent to the model carries this prefix. */
        const val SCREEN_OUTLINE_PREFIX = "Current screen:\n"

        /** Sent instead of a full outline when the screen is byte-identical. */
        const val UNCHANGED_SCREEN_MARKER =
            "Current screen: (unchanged from the last outline)"

        /** What a stale outline is rewritten to at rebuild time. */
        const val STALE_SCREEN_PLACEHOLDER =
            "[stale screen outline removed — the newest outline below is current]"
        private const val SUMMARY_PREFIX = "[Earlier conversation summarized]\n"
    }
}
