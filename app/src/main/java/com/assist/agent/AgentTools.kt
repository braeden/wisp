package com.assist.agent

import com.assist.llm.ToolSpec

/**
 * The single source of truth for the model's action space (ARCHITECTURE.md tool
 * catalog), expressed as [ToolSpec.ClientTool]s fed to `LlmRequest.tools`. Grouped
 * perception/control, user-interaction, and context/economy. Gesture tools are
 * marked `strict` so the provider enforces the argument schema. [ToolRouter]
 * executes each by [name]; keep the names here and there in lockstep.
 *
 * The learned-task memory tool (phase-12) is advertised as a
 * [ToolSpec.ProviderTool] (`memory_20250818`, name `memory`); Anthropic owns its
 * schema and injects the "always view your memory first" protocol. [ToolRouter]
 * executes `memory` `tool_use` blocks against `MemoryStore`.
 */
object AgentTools {

    // Perception & control
    const val GET_SCREEN_STATE = "get_screen_state"
    const val TAKE_SCREENSHOT = "take_screenshot"
    const val TAP = "tap"
    const val TAP_XY = "tap_xy"
    const val LONG_PRESS = "long_press"
    const val LONG_PRESS_XY = "long_press_xy"
    const val SWIPE = "swipe"
    const val SWIPE_XY = "swipe_xy"
    const val SCROLL = "scroll"
    const val SET_TEXT = "set_text"
    const val PRESS_KEY = "press_key"
    const val OPEN_APP = "open_app"
    const val WAIT = "wait"

    // User interaction
    const val SAY = "say"
    const val ASK = "ask"
    const val FINISH = "finish"

    // Context / economy
    const val DROP_OLD_SCREENSHOTS = "drop_old_screenshots"
    const val COMPACT_CONVERSATION = "compact_conversation"
    const val NOTE = "note"

    // Learned task memory (provider tool — Anthropic-owned schema)
    const val MEMORY = "memory"
    const val MEMORY_TYPE = "memory_20250818"

    /** The full catalog advertised to the model, in a stable order (cache-friendly). */
    fun catalog(): List<ToolSpec> = listOf(
        // --- Perception & control ---
        client(
            GET_SCREEN_STATE,
            "Return the current foreground screen as a compact accessibility outline " +
                "(one line per element with a stable #id). Your default way to perceive.",
            objectSchema(),
        ),
        client(
            TAKE_SCREENSHOT,
            "Capture a PNG screenshot of the current screen. Use only when the a11y " +
                "outline is insufficient (canvas/WebView/visual judgement).",
            objectSchema(),
        ),
        client(
            TAP,
            "Tap the element with the given id from the latest screen outline.",
            objectSchema(
                required = listOf("element_id"),
                props = """"element_id":{"type":"integer","description":"#id from the outline"}""",
            ),
            strict = true,
        ),
        client(
            TAP_XY,
            "Tap absolute screen coordinates. Prefer tap(element_id) when an element fits.",
            objectSchema(
                required = listOf("x", "y"),
                props = """"x":{"type":"integer"},"y":{"type":"integer"}""",
            ),
            strict = true,
        ),
        client(
            LONG_PRESS,
            "Long-press the element with the given id.",
            objectSchema(
                required = listOf("element_id"),
                props = """"element_id":{"type":"integer"}""",
            ),
            strict = true,
        ),
        client(
            LONG_PRESS_XY,
            "Long-press absolute screen coordinates.",
            objectSchema(
                required = listOf("x", "y"),
                props = """"x":{"type":"integer"},"y":{"type":"integer"}""",
            ),
            strict = true,
        ),
        client(
            SWIPE,
            "Swipe the screen in a direction (the finger travels that way; content " +
                "moves opposite). Optional distance fraction of the screen (0..1).",
            objectSchema(
                required = listOf("direction"),
                props = """"direction":{"type":"string","enum":["up","down","left","right"]},""" +
                    """"distance":{"type":"number","minimum":0,"maximum":1}""",
            ),
        ),
        client(
            SWIPE_XY,
            "Swipe from (x1,y1) to (x2,y2) over an optional duration in ms.",
            objectSchema(
                required = listOf("x1", "y1", "x2", "y2"),
                props = """"x1":{"type":"integer"},"y1":{"type":"integer"},""" +
                    """"x2":{"type":"integer"},"y2":{"type":"integer"},""" +
                    """"duration_ms":{"type":"integer","minimum":0}""",
            ),
        ),
        client(
            SCROLL,
            "Scroll a scrollable element by id, or the screen in a direction. Provide " +
                "either element_id or direction.",
            objectSchema(
                props = """"element_id":{"type":"integer"},""" +
                    """"direction":{"type":"string","enum":["up","down","left","right"]},""" +
                    """"forward":{"type":"boolean","description":"for element_id scroll"}""",
            ),
        ),
        client(
            SET_TEXT,
            "Focus an editable element by id and set its text (replaces existing text).",
            objectSchema(
                required = listOf("element_id", "text"),
                props = """"element_id":{"type":"integer"},"text":{"type":"string"}""",
            ),
            strict = true,
        ),
        client(
            PRESS_KEY,
            "Press a global navigation key.",
            objectSchema(
                required = listOf("key"),
                props = """"key":{"type":"string","enum":["back","home","recents",""" +
                    """"notifications","quick_settings","enter"]}""",
            ),
            strict = true,
        ),
        client(
            OPEN_APP,
            "Launch an app by package name or human label (e.g. \"Clock\" or " +
                "\"com.google.android.deskclock\").",
            objectSchema(
                required = listOf("app"),
                props = """"app":{"type":"string"}""",
            ),
            strict = true,
        ),
        client(
            WAIT,
            "Wait for the UI to settle / animations / loading. Milliseconds (capped).",
            objectSchema(
                required = listOf("ms"),
                props = """"ms":{"type":"integer","minimum":0}""",
            ),
        ),
        // --- User interaction ---
        client(
            SAY,
            "Speak/display a short message to the user. One-way; does not block.",
            objectSchema(required = listOf("text"), props = """"text":{"type":"string"}"""),
        ),
        client(
            ASK,
            "Ask the user a question and wait for their reply. Use when you need a " +
                "decision or missing information.",
            objectSchema(required = listOf("question"), props = """"question":{"type":"string"}"""),
        ),
        client(
            FINISH,
            "End the task with a one-line summary. Call this when the task is complete " +
                "or cannot proceed. Do not call more tools after finishing.",
            objectSchema(required = listOf("summary"), props = """"summary":{"type":"string"}"""),
        ),
        // --- Context / economy ---
        client(
            DROP_OLD_SCREENSHOTS,
            "Drop old screenshots/tool results from context to save tokens, optionally " +
                "keeping the most recent few.",
            objectSchema(
                props = """"keep_last":{"type":"integer","minimum":0}""",
            ),
        ),
        client(
            COMPACT_CONVERSATION,
            "Summarize and compact earlier conversation to free up context.",
            objectSchema(),
        ),
        client(
            NOTE,
            "Write a durable scratch note into the session (survives compaction).",
            objectSchema(required = listOf("text"), props = """"text":{"type":"string"}"""),
        ),
        // --- Learned task memory (provider tool) ---
        ToolSpec.ProviderTool(type = MEMORY_TYPE, name = MEMORY),
    )

    private fun client(
        name: String,
        description: String,
        inputSchemaJson: String,
        strict: Boolean = false,
    ) = ToolSpec.ClientTool(name, description, inputSchemaJson, strict)

    /**
     * Build a JSON-Schema object. [props] is the raw comma-separated property
     * entries (may be empty). `additionalProperties:false` is required when
     * `strict` is used and is harmless otherwise.
     */
    private fun objectSchema(
        required: List<String> = emptyList(),
        props: String = "",
    ): String {
        val requiredJson = required.joinToString(",") { "\"$it\"" }
        return """{"type":"object","properties":{$props},""" +
            """"required":[$requiredJson],"additionalProperties":false}"""
    }
}
