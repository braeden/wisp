package com.assist.service

/**
 * Result of a [DeviceController] action. The agent's ToolRouter (phase-06) maps
 * this straight into a `tool_result` block, so [message] is model-facing text.
 */
data class ToolOutcome(
    val success: Boolean,
    val message: String,
) {
    companion object {
        fun ok(message: String = "ok"): ToolOutcome = ToolOutcome(true, message)

        fun fail(message: String): ToolOutcome = ToolOutcome(false, message)
    }
}
