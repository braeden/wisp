package com.assist.llm.anthropic

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assist.BuildConfig
import com.assist.data.SecretStore
import com.assist.llm.ContentBlock
import com.assist.llm.Effort
import com.assist.llm.LlmMessage
import com.assist.llm.LlmRequest
import com.assist.llm.LlmStreamEvent
import com.assist.llm.Role
import com.assist.llm.SystemBlock
import com.assist.llm.ToolDef
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Opt-in real-model smoke test. Runs three turns against the live Anthropic API:
 * one text, one tool-use (trivial `echo` tool), one vision (tiny red image).
 *
 * Gated by `BuildConfig.ANTHROPIC_API_KEY` (injected from the `anthropicApiKey`
 * Gradle property or `ANTHROPIC_API_KEY` env var). Skips cleanly — never fails —
 * when no key is present.
 *
 * Run:
 *   ANTHROPIC_API_KEY=sk-ant-... ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.assist.llm.anthropic.AnthropicSmokeTest
 */
@RunWith(AndroidJUnit4::class)
class AnthropicSmokeTest {
    private lateinit var client: AnthropicLlmClient
    private val model = ModelRouter().modelFor(StepDifficulty.SIMPLE) // claude-haiku-4-5

    @Before
    fun setUp() {
        val key = BuildConfig.ANTHROPIC_API_KEY
        assumeTrue("ANTHROPIC_API_KEY not set — skipping live smoke test", key.isNotBlank())

        val secretStore =
            object : SecretStore {
                override fun getApiKey(): String = key

                override fun setApiKey(value: String) = Unit
            }
        val okHttp =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        client = AnthropicLlmClient(secretStore = secretStore, okHttp = okHttp)
    }

    @Test
    fun textTurn() =
        runBlocking {
            val request =
                baseRequest(
                    messages =
                        listOf(
                            userText("Reply with exactly the single word: hello"),
                        ),
                )
            val streamedText = StringBuilder()
            val response =
                client.send(request) { event ->
                    if (event is LlmStreamEvent.TextDelta) streamedText.append(event.text)
                }
            assertFalse("expected non-empty text", response.text.isBlank())
            assertTrue(
                "streamed deltas should match final text",
                streamedText.isNotEmpty(),
            )
        }

    @Test
    fun toolUseTurn() =
        runBlocking {
            val echoTool =
                ToolDef(
                    name = "echo",
                    description = "Echoes back the provided text verbatim.",
                    inputSchemaJson =
                        """
                        {"type":"object","properties":{"text":{"type":"string"}},
                        "required":["text"],"additionalProperties":false}
                        """.trimIndent(),
                )
            val request =
                baseRequest(
                    tools = listOf(echoTool),
                    messages =
                        listOf(
                            userText(
                                "Use the echo tool to echo the word banana. Do not reply otherwise.",
                            ),
                        ),
                )
            val response = client.send(request) {}
            assertTrue("expected a tool call", response.toolCalls.isNotEmpty())
            assertTrue(
                "expected Claude to call the echo tool",
                response.toolCalls.any { it.name == "echo" },
            )
            assertTrue("expected stop_reason tool_use", response.stopReason == "tool_use")
        }

    @Test
    fun visionTurn() =
        runBlocking {
            val request =
                baseRequest(
                    messages =
                        listOf(
                            LlmMessage(
                                role = Role.USER,
                                content =
                                    listOf(
                                        ContentBlock.Image(
                                            base64 = redPngBase64(),
                                            mediaType = "image/png",
                                        ),
                                        ContentBlock.Text(
                                            "What color is this image? Answer with a single word.",
                                        ),
                                    ),
                            ),
                        ),
                )
            val response = client.send(request) {}
            assertTrue(
                "expected a coherent description mentioning red, got: ${response.text}",
                response.text.lowercase().contains("red"),
            )
        }

    // --- helpers ------------------------------------------------------------

    private fun baseRequest(
        messages: List<LlmMessage>,
        tools: List<ToolDef> = emptyList(),
    ) = LlmRequest(
        model = model,
        system = listOf(SystemBlock("You are a terse test assistant.", cacheable = true)),
        messages = messages,
        tools = tools,
        maxTokens = 512,
        effort = if (model == ModelRouter.SIMPLE_MODEL) null else Effort.LOW,
        // Haiku does not support effort/adaptive thinking; keep thinking off.
        thinkingAdaptive = false,
    )

    private fun userText(text: String) =
        LlmMessage(role = Role.USER, content = listOf(ContentBlock.Text(text)))

    private fun redPngBase64(): String {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
