package com.assist.agent

import android.graphics.Bitmap
import android.util.Log
import com.assist.data.SessionRepository
import com.assist.data.TaskMemoryRepository
import com.assist.llm.ContentBlock
import com.assist.llm.ContextManagement
import com.assist.llm.ToolCall
import com.assist.memory.MemoryStore
import com.assist.service.DeviceController
import com.assist.service.DeviceKey
import com.assist.service.ScreenState
import com.assist.service.SwipeDirection
import com.assist.service.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a model [ToolCall] against the device / user / session. Maps
 * control+perception tools to [DeviceController] (phase-03), `say`/`ask`/`finish`
 * to [UserIo] (phase-08 stub here), context tools to [SessionRepository] +
 * client-side context edits (phase-04/05), and `get_screen_state`/`take_screenshot`
 * to capture-and-return-as-`tool_result`. The [AgentLoop] owns gating and
 * persistence; this class only runs the action and reports a [ToolExecution].
 */
@Singleton
class ToolRouter
    @Inject
    constructor(
        private val device: DeviceController,
        private val repository: SessionRepository,
        private val userIo: UserIo,
        private val memoryStore: MemoryStore,
        private val taskMemory: TaskMemoryRepository,
        private val json: Json,
    ) {
        suspend fun execute(
            sessionId: Long,
            call: ToolCall,
        ): ToolExecution {
            val args = parseArgs(call.argumentsJson)
            return try {
                when (call.name) {
                    AgentTools.GET_SCREEN_STATE -> getScreenState(call)
                    AgentTools.TAKE_SCREENSHOT -> takeScreenshot(call)
                    AgentTools.TAP -> control(call, device.tap(args.int("element_id")))
                    AgentTools.TAP_XY -> control(call, device.tapXy(args.int("x"), args.int("y")))
                    AgentTools.LONG_PRESS -> control(call, device.longPress(args.int("element_id")))
                    AgentTools.LONG_PRESS_XY ->
                        control(
                            call,
                            device.longPressXy(args.int("x"), args.int("y")),
                        )
                    AgentTools.SWIPE -> swipe(call, args)
                    AgentTools.SWIPE_XY -> swipeXy(call, args)
                    AgentTools.SCROLL -> scroll(call, args)
                    AgentTools.SET_TEXT ->
                        control(
                            call,
                            device.setText(args.int("element_id"), args.str("text")),
                        )
                    AgentTools.PRESS_KEY -> pressKey(call, args)
                    AgentTools.OPEN_APP ->
                        control(
                            call,
                            device.openApp(args.str("app")),
                            settle = true,
                        )
                    AgentTools.WAIT -> nonActing(call, device.wait(args.long("ms")))
                    AgentTools.SAY -> say(call, args)
                    AgentTools.ASK -> ask(call, args)
                    AgentTools.FINISH -> finish(call, args)
                    AgentTools.DROP_OLD_SCREENSHOTS -> dropOldScreenshots(sessionId, call, args)
                    AgentTools.COMPACT_CONVERSATION -> compact(sessionId, call)
                    AgentTools.NOTE -> note(sessionId, call, args)
                    AgentTools.MEMORY -> memory(call)
                    else -> error(call, "Unknown tool '${call.name}'")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tool '${call.name}' failed", t)
                error(call, "Tool '${call.name}' failed: ${t.message ?: t::class.simpleName}")
            }
        }

        // --- Perception ---------------------------------------------------------

        private suspend fun getScreenState(call: ToolCall): ToolExecution {
            val state = device.getScreenState()
            return ToolExecution(
                // Prefixed so rebuild-time pruning recognizes (and later stubs) it.
                resultBlock =
                    textResult(
                        call,
                        SessionRepository.SCREEN_OUTLINE_PREFIX + state.toOutline(),
                    ),
                success = true,
                message = "screen: ${state.elements.size} elements",
                producedPerception = true,
                screenState = state,
            )
        }

        private suspend fun takeScreenshot(call: ToolCall): ToolExecution {
            val bitmap =
                device.takeScreenshot()
                    ?: return error(
                        call,
                        "Screenshot unavailable (service not connected or rate-limited)",
                    )
            val base64 = withContext(Dispatchers.IO) { encodePng(bitmap) }
            val block =
                ContentBlock.ToolResult(
                    toolUseId = call.id,
                    content = listOf(ContentBlock.Image(base64 = base64, mediaType = "image/png")),
                )
            return ToolExecution(
                resultBlock = block,
                success = true,
                message = "screenshot ${bitmap.width}x${bitmap.height}",
                producedPerception = true,
            )
        }

        // --- Control ------------------------------------------------------------

        private fun control(
            call: ToolCall,
            outcome: ToolOutcome,
            settle: Boolean = true,
        ): ToolExecution =
            ToolExecution(
                resultBlock = outcomeResult(call, outcome),
                success = outcome.success,
                message = outcome.message,
                didAct = settle && outcome.success,
            )

        /** A non-UI-changing action (e.g. wait): no settle/auto-perception needed. */
        private fun nonActing(
            call: ToolCall,
            outcome: ToolOutcome,
        ): ToolExecution =
            ToolExecution(
                resultBlock = outcomeResult(call, outcome),
                success = outcome.success,
                message = outcome.message,
            )

        private suspend fun swipe(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val dir =
                SwipeDirection.fromString(args.str("direction"))
                    ?: return error(call, "Invalid direction '${args.strOrNull("direction")}'")
            val fraction = args.doubleOrNull("distance") ?: 0.6
            return control(call, device.swipe(dir, fraction))
        }

        private suspend fun swipeXy(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val duration = args.longOrNull("duration_ms")
            val outcome =
                if (duration != null) {
                    device.swipeXy(
                        args.int("x1"),
                        args.int("y1"),
                        args.int("x2"),
                        args.int("y2"),
                        duration,
                    )
                } else {
                    device.swipeXy(args.int("x1"), args.int("y1"), args.int("x2"), args.int("y2"))
                }
            return control(call, outcome)
        }

        private suspend fun scroll(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val elementId = args.intOrNull("element_id")
            val outcome =
                if (elementId != null) {
                    device.scroll(elementId, args.boolOrNull("forward") ?: true)
                } else {
                    val dir =
                        args.strOrNull("direction")?.let { SwipeDirection.fromString(it) }
                            ?: return error(call, "scroll needs element_id or a valid direction")
                    device.scroll(dir)
                }
            return control(call, outcome)
        }

        private suspend fun pressKey(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val key =
                DeviceKey.fromString(args.str("key"))
                    ?: return error(call, "Invalid key '${args.strOrNull("key")}'")
            return control(call, device.pressKey(key))
        }

        // --- User interaction ---------------------------------------------------

        private suspend fun say(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val text = args.str("text")
            userIo.say(text)
            return textResultExec(call, "spoke", "ok")
        }

        private suspend fun ask(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val answer = userIo.ask(args.str("question"))
            return ToolExecution(
                resultBlock = textResult(call, "User replied: $answer"),
                success = true,
                message = "asked; reply=${answer.take(40)}",
            )
        }

        private suspend fun finish(
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val summary = args.strOrNull("summary").orEmpty()
            // NOTE: finish does NOT speak here. The agent loop speaks the summary once,
            // and only if the model didn't already `say` something this turn — otherwise
            // a `say(...)` + `finish(...)` pair (a very common completion pattern) would
            // produce two near-identical TTS outputs. See AgentLoop's finished branch.
            return ToolExecution(
                resultBlock = textResult(call, "Task finished."),
                success = true,
                message = summary.ifBlank { "finished" },
                finished = true,
                finishSummary = summary,
            )
        }

        // --- Context / economy --------------------------------------------------

        private suspend fun dropOldScreenshots(
            sessionId: Long,
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val keepLast = args.intOrNull("keep_last") ?: 0
            repository.markScreenshotsDropped(sessionId, keepLast)
            return ToolExecution(
                resultBlock = textResult(call, "Dropped old screenshots (kept last $keepLast)."),
                success = true,
                message = "dropped screenshots keepLast=$keepLast",
                contextEdit =
                    ContextManagement(
                        clearToolUses = true,
                        keepLastToolUses =
                            keepLast.takeIf {
                                it >
                                    0
                            },
                    ),
            )
        }

        private fun compact(
            sessionId: Long,
            call: ToolCall,
        ): ToolExecution =
            ToolExecution(
                resultBlock = textResult(call, "Compacting earlier conversation."),
                success = true,
                message = "compact requested",
                contextEdit = ContextManagement(compact = true),
            )

        private suspend fun note(
            sessionId: Long,
            call: ToolCall,
            args: Args,
        ): ToolExecution {
            val text = args.str("text")
            repository.addNote(sessionId, text)
            return textResultExec(call, "noted", "ok")
        }

        // --- Learned task memory (provider tool) --------------------------------

        /**
         * Execute an Anthropic memory `tool_use` against [MemoryStore] and return its
         * result verbatim. On a successful mutation of a `/memories/tasks/<slug>.md`
         * recipe, refresh the [TaskMemoryRepository] index so the UI/recall stays in
         * sync. The `input` object is Anthropic-owned (command + path/args).
         */
        private suspend fun memory(call: ToolCall): ToolExecution {
            val input =
                runCatching {
                    json
                        .parseToJsonElement(
                            call.argumentsJson,
                        ).jsonObject
                }.getOrNull()
            if (input == null) {
                return ToolExecution(
                    resultBlock = textResult(call, "Error: invalid memory command", isError = true),
                    success = false,
                    message = "memory: bad input",
                )
            }
            val result = withContext(Dispatchers.IO) { memoryStore.execute(input) }
            if (!result.isError) {
                runCatching { taskMemory.onMemoryMutation(input) }
                    .onFailure { Log.w(TAG, "recipe index update failed", it) }
            }
            val command = input["command"]?.jsonPrimitive?.content ?: "memory"
            return ToolExecution(
                resultBlock = textResult(call, result.content, isError = result.isError),
                success = !result.isError,
                message = "memory $command ${if (result.isError) "error" else "ok"}",
            )
        }

        // --- Result helpers -----------------------------------------------------

        private fun outcomeResult(
            call: ToolCall,
            outcome: ToolOutcome,
        ): ContentBlock.ToolResult =
            ContentBlock.ToolResult(
                toolUseId = call.id,
                content = listOf(ContentBlock.Text(outcome.message)),
                isError = !outcome.success,
            )

        private fun textResult(
            call: ToolCall,
            text: String,
            isError: Boolean = false,
        ): ContentBlock.ToolResult =
            ContentBlock.ToolResult(
                toolUseId = call.id,
                content = listOf(ContentBlock.Text(text)),
                isError = isError,
            )

        private fun textResultExec(
            call: ToolCall,
            message: String,
            resultText: String,
        ) = ToolExecution(
            resultBlock = textResult(call, resultText),
            success = true,
            message = message,
        )

        private fun error(
            call: ToolCall,
            message: String,
        ): ToolExecution =
            ToolExecution(
                resultBlock = textResult(call, message, isError = true),
                success = false,
                message = message,
            )

        private fun encodePng(bitmap: Bitmap): String {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            return Base64.getEncoder().encodeToString(stream.toByteArray())
        }

        // --- Argument parsing ---------------------------------------------------

        private fun parseArgs(raw: String): Args =
            runCatching { Args(json.parseToJsonElement(raw).jsonObject, json) }
                .getOrElse { Args(kotlinx.serialization.json.JsonObject(emptyMap()), json) }

        /** Convenience typed accessors over a tool call's JSON arguments. */
        private class Args(
            private val obj: kotlinx.serialization.json.JsonObject,
            @Suppress("unused") private val json: Json,
        ) {
            fun intOrNull(key: String): Int? = obj[key]?.jsonPrimitive?.intOrNull

            fun int(key: String): Int =
                intOrNull(key) ?: throw IllegalArgumentException("missing int '$key'")

            fun longOrNull(key: String): Long? = obj[key]?.jsonPrimitive?.content?.toLongOrNull()

            fun long(key: String): Long =
                longOrNull(key) ?: throw IllegalArgumentException("missing '$key'")

            fun doubleOrNull(key: String): Double? = obj[key]?.jsonPrimitive?.doubleOrNull

            fun boolOrNull(key: String): Boolean? = obj[key]?.jsonPrimitive?.booleanOrNull

            fun strOrNull(key: String): String? = obj[key]?.jsonPrimitive?.content

            fun str(key: String): String =
                strOrNull(key) ?: throw IllegalArgumentException("missing string '$key'")
        }

        private companion object {
            const val TAG = "ToolRouter"
        }
    }

/** Local extension to resolve gate target text for an element id in a screen. */
internal fun ScreenState.elementText(elementId: Int?): Pair<String?, Boolean> {
    if (elementId == null) return null to false
    val el = elements.firstOrNull { it.id == elementId } ?: return null to false
    val text = listOfNotNull(el.text, el.contentDesc).joinToString(" ").ifBlank { el.role }
    return text to el.password
}
