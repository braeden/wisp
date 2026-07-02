package com.assist.data

import com.assist.llm.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

class CostCalculatorTest {

    private val calc = CostCalculator()

    @Test
    fun `opus input and output priced against fixtures`() {
        // 1000 input @ $5/1M + 500 output @ $25/1M = 0.005 + 0.0125 = 0.0175
        val cost = calc.usageCost(
            "claude-opus-4-8",
            Usage(inputTokens = 1000, outputTokens = 500),
        )
        assertEquals(0.0175, cost, 1e-9)
    }

    @Test
    fun `opus cache tokens priced at write 1_25x and read 0_1x input`() {
        // cacheWrite 2000 @ $6.25/1M = 0.0125 ; cacheRead 4000 @ $0.5/1M = 0.002
        val cost = calc.usageCost(
            "claude-opus-4-8",
            Usage(cacheWriteTokens = 2000, cacheReadTokens = 4000),
        )
        assertEquals(0.0145, cost, 1e-9)
    }

    @Test
    fun `haiku priced at 1 and 5 per million`() {
        val cost = calc.usageCost(
            "claude-haiku-4-5",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000),
        )
        assertEquals(6.0, cost, 1e-9)
    }

    @Test
    fun `sonnet priced at 3 and 15 per million`() {
        val cost = calc.usageCost(
            "claude-sonnet-5",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000),
        )
        assertEquals(18.0, cost, 1e-9)
    }

    @Test
    fun `context windows match fixtures`() {
        assertEquals(1_000_000, calc.contextWindow("claude-opus-4-8"))
        assertEquals(1_000_000, calc.contextWindow("claude-sonnet-5"))
        assertEquals(200_000, calc.contextWindow("claude-haiku-4-5"))
    }

    @Test
    fun `unknown model falls back to default pricing`() {
        val unknown = calc.usageCost("some-future-model", Usage(inputTokens = 1000))
        val opus = calc.usageCost("claude-opus-4-8", Usage(inputTokens = 1000))
        assertEquals(opus, unknown, 1e-12)
    }

    @Test
    fun `fast-mode opus priced at the premium 10 and 50 per million`() {
        // 1M in @ $10/1M + 1M out @ $50/1M = 60.0 (2x standard opus).
        val cost = calc.usageCost(
            "claude-opus-4-8",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, speed = "fast"),
        )
        assertEquals(60.0, cost, 1e-9)
    }

    @Test
    fun `fast-mode opus-4-7 priced at 30 and 150 per million`() {
        val cost = calc.usageCost(
            "claude-opus-4-7",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, speed = "fast"),
        )
        assertEquals(180.0, cost, 1e-9)
    }

    @Test
    fun `standard speed still uses standard pricing`() {
        val cost = calc.usageCost(
            "claude-opus-4-8",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, speed = "standard"),
        )
        assertEquals(30.0, cost, 1e-9)
    }

    @Test
    fun `fast speed on a model without a fast row falls back to standard`() {
        val cost = calc.usageCost(
            "claude-haiku-4-5",
            Usage(inputTokens = 1_000_000, outputTokens = 1_000_000, speed = "fast"),
        )
        assertEquals(6.0, cost, 1e-9)
    }
}
