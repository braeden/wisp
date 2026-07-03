package com.assist.agent

import com.assist.llm.SystemBlock
import com.assist.prompt.PromptContext
import javax.inject.Inject
import com.assist.prompt.SystemPromptProvider as PromptSystemPromptProvider

/**
 * Adapts the phase-10 [com.assist.prompt.SystemPromptProvider] (the real prompt)
 * to the agent loop's [SystemPromptProvider] seam. The loop supplies a
 * [SystemPromptContext]; we forward the device descriptor into phase-10's
 * [PromptContext] dynamic tail.
 *
 * The user intent is intentionally **not** injected into the system prompt — it
 * lives in the first user turn of the conversation. Keeping the system core
 * byte-stable across sessions maximizes prompt-cache reuse, which is exactly why
 * phase-10's [PromptContext] omits it.
 */
class Phase10SystemPromptProvider
    @Inject
    constructor(
        private val delegate: PromptSystemPromptProvider,
    ) : SystemPromptProvider {
        override fun system(context: SystemPromptContext): List<SystemBlock> =
            delegate.system(PromptContext(deviceModel = context.deviceInfo))
    }
