package com.assist.llm

/**
 * One assistant-turn request. [system] + [tools] form the cacheable stable
 * prefix. [thinkingAdaptive] selects `thinking: {type:"adaptive"}` in the impl;
 * [effort] maps to `output_config.effort`.
 */
data class LlmRequest(
    val model: String,
    val system: List<SystemBlock>,
    val messages: List<LlmMessage>,
    val tools: List<ToolDef>,
    val maxTokens: Int,
    val effort: Effort? = null,
    val thinkingAdaptive: Boolean = true,
    /**
     * Optional in-turn context management (context editing / compaction). Null =
     * none. Additive; existing call sites are unaffected.
     */
    val contextManagement: ContextManagement? = null,
)
