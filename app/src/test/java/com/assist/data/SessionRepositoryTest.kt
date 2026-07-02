package com.assist.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.assist.llm.ContentBlock
import com.assist.llm.Role
import com.assist.llm.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRepositoryTest {

    private lateinit var db: AssistDatabase
    private lateinit var repo: SessionRepository
    private lateinit var tracker: ContextTracker
    private lateinit var screenshotDir: File

    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AssistDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        screenshotDir = File.createTempFile("screens", "").let {
            it.delete(); it.mkdirs(); it
        }
        val cost = CostCalculator()
        repo = SessionRepository(
            db = db,
            screenshotStore = ScreenshotStore(screenshotDir),
            costCalculator = cost,
            ioDispatcher = Dispatchers.Unconfined,
            now = { clock },
        )
        tracker = ContextTracker(db, cost)
    }

    @After
    fun tearDown() {
        db.close()
        screenshotDir.deleteRecursively()
    }

    @Test
    fun `create append and reconstruct a coherent conversation`() = runTest {
        val session = repo.createSession(title = "Test")
        assertEquals(SessionStatus.ACTIVE, session.status)

        repo.appendMessage(
            session.id,
            Role.USER,
            listOf(ContentBlock.Text("open the settings app")),
        )
        val assistantMsg = repo.appendMessage(
            session.id,
            Role.ASSISTANT,
            listOf(
                ContentBlock.Text("Opening settings."),
                ContentBlock.ToolUse(id = "tu_1", name = "open_app", inputJson = "{\"app\":\"settings\"}"),
            ),
        )
        repo.appendToolCall(
            sessionId = session.id,
            messageId = assistantMsg.id,
            name = "open_app",
            argsJson = "{\"app\":\"settings\"}",
            resultJson = "{\"ok\":true}",
            success = true,
            durationMs = 42,
        )
        repo.appendMessage(
            session.id,
            Role.USER,
            listOf(
                ContentBlock.ToolResult(
                    toolUseId = "tu_1",
                    content = listOf(ContentBlock.Text("settings opened")),
                ),
            ),
            kind = MessageKind.TOOL_RESULT,
        )

        val rebuilt = repo.buildLlmMessages(session.id)
        assertEquals(3, rebuilt.size)
        assertEquals(Role.USER, rebuilt[0].role)
        assertEquals(Role.ASSISTANT, rebuilt[1].role)
        assertEquals(Role.USER, rebuilt[2].role)

        val toolUse = rebuilt[1].content.filterIsInstance<ContentBlock.ToolUse>().single()
        assertEquals("open_app", toolUse.name)

        val toolResult = rebuilt[2].content.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals("tu_1", toolResult.toolUseId)
        assertEquals(
            "settings opened",
            (toolResult.content.single() as ContentBlock.Text).text,
        )
    }

    @Test
    fun `screenshots are stored as files not base64 in the row`() = runTest {
        val session = repo.createSession(title = "S")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val base64 = Base64.getEncoder().encodeToString(bytes)

        repo.appendMessage(
            session.id,
            Role.USER,
            listOf(ContentBlock.Image(base64 = base64, mediaType = "image/png")),
        )

        // The row must reference media, not embed the base64.
        val row = db.messageDao().getForSession(session.id).single()
        assertTrue(row.contentJson.contains("image_ref"))
        assertTrue(!row.contentJson.contains(base64))

        // The file exists and round-trips through reconstruction.
        val media = db.mediaDao().getForSession(session.id).single()
        assertTrue(File(media.path).exists())

        val rebuilt = repo.buildLlmMessages(session.id)
        val image = rebuilt.single().content.single() as ContentBlock.Image
        assertEquals(base64, image.base64)
    }

    @Test
    fun `dropped screenshots are placeholdered on rebuild`() = runTest {
        val session = repo.createSession(title = "S")
        val base64 = Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
        repo.appendMessage(
            session.id,
            Role.USER,
            listOf(ContentBlock.Image(base64 = base64)),
        )

        repo.markScreenshotsDropped(session.id, keepLast = 0)

        val rebuilt = repo.buildLlmMessages(session.id)
        val block = rebuilt.single().content.single()
        assertTrue(block is ContentBlock.Text)
        assertEquals(
            SessionRepository.DROPPED_SCREENSHOT_PLACEHOLDER,
            (block as ContentBlock.Text).text,
        )
    }

    @Test
    fun `markScreenshotsDropped keepLast retains the newest`() = runTest {
        val session = repo.createSession(title = "S")
        clock = 100
        repo.saveScreenshot(session.id, byteArrayOf(1))
        clock = 200
        repo.saveScreenshot(session.id, byteArrayOf(2))
        clock = 300
        val newest = repo.saveScreenshot(session.id, byteArrayOf(3))

        repo.markScreenshotsDropped(session.id, keepLast = 1)

        val media = db.mediaDao().getForSession(session.id)
        val live = media.filter { !it.dropped }
        assertEquals(1, live.size)
        assertEquals(newest.id, live.single().id)
    }

    @Test
    fun `sessionCost and contextStatus compute correctly against fixtures`() = runTest {
        val session = repo.createSession(title = "S", model = "claude-opus-4-8")

        repo.appendMessage(session.id, Role.USER, listOf(ContentBlock.Text("12345678"))) // 8 chars -> 2 tokens
        repo.appendMessage(
            session.id,
            Role.USER,
            listOf(ContentBlock.Image(base64 = Base64.getEncoder().encodeToString(byteArrayOf(7)))),
        )

        repo.recordUsage(
            session.id,
            messageId = null,
            model = "claude-opus-4-8",
            usage = Usage(inputTokens = 1000, outputTokens = 500),
        )

        assertEquals(0.0175, repo.sessionCost(session.id), 1e-9)

        val status = tracker.contextStatus(session.id)
        assertEquals(1_000_000, status.windowTokens)
        assertEquals(1, status.screenshotCount)
        assertEquals(0.0175, status.costUsd, 1e-9)
        // 2 text tokens + 1600 image tokens
        assertEquals(1602, status.usedTokens)

        // Dropping the screenshot shrinks used tokens and screenshot count.
        repo.markScreenshotsDropped(session.id, keepLast = 0)
        val after = tracker.contextStatus(session.id)
        assertEquals(0, after.screenshotCount)
        assertEquals(2, after.usedTokens)
    }

    @Test
    fun `summarizeAndCompact replaces messages with a summary and keeps notes`() = runTest {
        val session = repo.createSession(title = "S")
        repo.appendMessage(session.id, Role.USER, listOf(ContentBlock.Text("one")))
        repo.appendMessage(session.id, Role.ASSISTANT, listOf(ContentBlock.Text("two")))
        repo.addNote(session.id, "remember the wifi password")

        repo.summarizeAndCompact(session.id, summary = "user asked about X")

        val rebuilt = repo.buildLlmMessages(session.id)
        assertEquals(1, rebuilt.size)
        val text = (rebuilt.single().content.single() as ContentBlock.Text).text
        assertTrue(text.contains("user asked about X"))

        // Notes survive compaction.
        val notes = repo.listNotes(session.id)
        assertEquals(1, notes.size)
        assertEquals("remember the wifi password", notes.single().text)
    }

    @Test
    fun `todaySpend sums only today's usage`() = runTest {
        val session = repo.createSession(title = "S")
        // Yesterday.
        clock = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        repo.recordUsage(session.id, null, "claude-opus-4-8", Usage(inputTokens = 1_000_000))
        // Now.
        clock = System.currentTimeMillis()
        repo.recordUsage(session.id, null, "claude-opus-4-8", Usage(inputTokens = 1_000_000))

        // Only today's $5 input cost counts.
        assertEquals(5.0, repo.todaySpend(), 1e-9)
    }

    @Test
    fun `listSessions emits on change`() = runTest {
        repo.listSessions().test {
            assertEquals(emptyList<SessionEntity>(), awaitItem())

            repo.createSession(title = "A")
            assertEquals(1, awaitItem().size)

            repo.createSession(title = "B")
            assertEquals(2, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
