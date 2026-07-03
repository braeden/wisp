package com.assist.llm.anthropic

/** Difficulty of an agent step, used to route to a cheaper/faster model. */
enum class StepDifficulty {
    /** Trivial UI reads, label lookups, confirmations. */
    SIMPLE,

    /** The common case: a normal perceive→act turn. */
    STANDARD,

    /** Multi-step reasoning, ambiguous screens, visual judgement. */
    COMPLEX,
}

/**
 * Maps a [StepDifficulty] to a Claude model id. Default policy (overridable via
 * the constructor):
 *
 * - [StepDifficulty.SIMPLE] → `claude-haiku-4-5`
 * - [StepDifficulty.STANDARD] → `claude-sonnet-5`
 * - [StepDifficulty.COMPLEX] → `claude-opus-4-8` (also the overall default)
 *
 * The agent (phase-06) picks a difficulty per step; the concrete model ids are
 * settings-overridable here so cost/quality can be tuned without touching the
 * agent loop.
 */
class ModelRouter(
    val default: String = DEFAULT_MODEL,
    private val simpleModel: String = SIMPLE_MODEL,
    private val standardModel: String = STANDARD_MODEL,
    private val complexModel: String = DEFAULT_MODEL,
) {
    /** Resolve the model id for [difficulty]. */
    fun modelFor(difficulty: StepDifficulty): String =
        when (difficulty) {
            StepDifficulty.SIMPLE -> simpleModel
            StepDifficulty.STANDARD -> standardModel
            StepDifficulty.COMPLEX -> complexModel
        }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-4-8"
        const val STANDARD_MODEL = "claude-sonnet-5"
        const val SIMPLE_MODEL = "claude-haiku-4-5"
    }
}
