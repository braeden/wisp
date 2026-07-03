package com.wisp.data

/**
 * Estimates a session's live context size and assembles a [ContextStatus].
 *
 * Token counts are **estimates** — there is no on-device tokenizer, so text is
 * approximated at ~[CHARS_PER_TOKEN] characters per token and each live
 * screenshot at a flat [IMAGE_TOKENS]. Dropped media (see
 * [SessionRepository.markScreenshotsDropped]) is excluded, which is the whole
 * point: dropping screenshots must visibly shrink `usedTokens`.
 */
class ContextTracker(
    private val db: WispDatabase,
    private val costCalculator: CostCalculator,
) {
    /** Compute the current [ContextStatus] for [sessionId]. */
    suspend fun contextStatus(sessionId: Long): ContextStatus {
        val session = db.sessionDao().getById(sessionId)
        val model = session?.modelDefault ?: DEFAULT_MODEL

        val messages = db.messageDao().getForSession(sessionId)
        val mediaById = db.mediaDao().getForSession(sessionId).associateBy { it.id }

        var tokens = 0
        for (message in messages) {
            tokens += estimateBlocks(MessageContentCodec.decode(message.contentJson), mediaById)
        }

        val screenshotCount = db.mediaDao().countLive(sessionId, MediaKind.SCREENSHOT)
        val cost = db.usageDao().sessionCost(sessionId)

        return ContextStatus(
            usedTokens = tokens,
            windowTokens = costCalculator.contextWindow(model),
            costUsd = cost,
            screenshotCount = screenshotCount,
        )
    }

    private fun estimateBlocks(
        blocks: List<StoredBlock>,
        mediaById: Map<Long, MediaEntity>,
    ): Int {
        var tokens = 0
        for (block in blocks) {
            tokens +=
                when (block) {
                    is StoredBlock.Text -> estimateText(block.text)
                    is StoredBlock.Thinking -> estimateText(block.text)
                    is StoredBlock.ToolUse ->
                        estimateText(block.name) + estimateText(block.inputJson)
                    is StoredBlock.ToolResult -> estimateBlocks(block.content, mediaById)
                    is StoredBlock.ImageRef -> {
                        val media = mediaById[block.mediaId]
                        if (media != null && !media.dropped) IMAGE_TOKENS else 0
                    }
                    is StoredBlock.Raw -> estimateText(block.json)
                }
        }
        return tokens
    }

    private fun estimateText(text: String): Int =
        (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    private companion object {
        const val CHARS_PER_TOKEN = 4
        const val IMAGE_TOKENS = 1600
        const val DEFAULT_MODEL = "claude-opus-4-8"
    }
}
