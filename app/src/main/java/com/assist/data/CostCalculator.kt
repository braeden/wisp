package com.assist.data

import com.assist.llm.Usage

/**
 * Per-model pricing, USD per **million** tokens. Cache-write is the 5-minute
 * ephemeral rate (1.25× input) and cache-read is 0.1× input, per Anthropic's
 * standard prompt-caching multipliers. [contextWindow] is the model's max input
 * token window, used by [ContextTracker] for the HUD gauge.
 */
data class ModelPricing(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cacheWritePerMillion: Double,
    val cacheReadPerMillion: Double,
    val contextWindow: Int,
)

/**
 * Prices token usage per model. Pricing is a fixed fixture table (current as of
 * 2026-07); update it from the pricing docs as models change. Unknown models
 * fall back to the default model's pricing so cost is never silently zero.
 */
class CostCalculator(
    private val defaultModel: String = "claude-opus-4-8",
    private val pricing: Map<String, ModelPricing> = DEFAULT_PRICING,
) {

    /** Pricing for [model], falling back to the default model, then to opus-4-8. */
    fun pricingFor(model: String): ModelPricing =
        pricing[model]
            ?: pricing[defaultModel]
            ?: DEFAULT_PRICING.getValue("claude-opus-4-8")

    /** The model's context window in tokens. */
    fun contextWindow(model: String): Int = pricingFor(model).contextWindow

    /** Cost in USD of a single [Usage] row for [model]. */
    fun usageCost(model: String, usage: Usage): Double {
        val p = pricingFor(model)
        return (
            usage.inputTokens * p.inputPerMillion +
                usage.outputTokens * p.outputPerMillion +
                usage.cacheWriteTokens * p.cacheWritePerMillion +
                usage.cacheReadTokens * p.cacheReadPerMillion
            ) / 1_000_000.0
    }

    companion object {
        /**
         * Fixture pricing (USD / 1M tokens). Input/output from the pricing docs;
         * cache-write = 1.25× input, cache-read = 0.1× input.
         */
        val DEFAULT_PRICING: Map<String, ModelPricing> = mapOf(
            "claude-opus-4-8" to ModelPricing(
                inputPerMillion = 5.0,
                outputPerMillion = 25.0,
                cacheWritePerMillion = 6.25,
                cacheReadPerMillion = 0.5,
                contextWindow = 1_000_000,
            ),
            "claude-sonnet-5" to ModelPricing(
                inputPerMillion = 3.0,
                outputPerMillion = 15.0,
                cacheWritePerMillion = 3.75,
                cacheReadPerMillion = 0.3,
                contextWindow = 1_000_000,
            ),
            "claude-haiku-4-5" to ModelPricing(
                inputPerMillion = 1.0,
                outputPerMillion = 5.0,
                cacheWritePerMillion = 1.25,
                cacheReadPerMillion = 0.1,
                contextWindow = 200_000,
            ),
        )
    }
}
