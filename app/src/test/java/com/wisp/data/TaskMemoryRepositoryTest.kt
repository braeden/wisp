package com.wisp.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.wisp.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskMemoryRepositoryTest {
    private lateinit var db: WispDatabase
    private lateinit var memDir: File
    private lateinit var store: MemoryStore
    private lateinit var repo: TaskMemoryRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, WispDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        memDir =
            File.createTempFile("mem", "").apply {
                delete()
                mkdirs()
            }
        store = MemoryStore(rootDir = File(memDir, "memories"))
        repo =
            TaskMemoryRepository(db.taskRecipeDao(), store, Dispatchers.Unconfined, now = { clock })
    }

    @After
    fun tearDown() {
        db.close()
        memDir.deleteRecursively()
    }

    private fun writeRecipe(
        path: String,
        body: String,
    ) {
        store.execute(
            buildJsonObject {
                put("command", "create")
                put("path", path)
                put("file_text", body)
            },
        )
    }

    private fun createInput(path: String): JsonObject =
        buildJsonObject {
            put("command", "create")
            put("path", path)
        }

    @Test
    fun `indexing a tasks recipe surfaces it in listRecipes with a derived title`() =
        runTest {
            val path = "/memories/tasks/youtube-playback-speed-2x.md"
            writeRecipe(
                path,
                "# YouTube playback speed 2x\nApp: com.google.android.youtube\nSteps...",
            )
            repo.onMemoryMutation(createInput(path))

            // Room's Flow emits on its query executor (real time); generous
            // timeout so a slow CI runner doesn't flake it.
            repo.listRecipes().test(timeout = 10.seconds) {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals("YouTube playback speed 2x", rows.single().title)
                assertEquals("com.google.android.youtube", rows.single().appPackage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `recallHint matches by intent keywords`() =
        runTest {
            writeRecipe(
                "/memories/tasks/youtube-playback-speed-2x.md",
                "# YouTube playback speed 2x",
            )
            repo.onMemoryMutation(createInput("/memories/tasks/youtube-playback-speed-2x.md"))
            writeRecipe("/memories/tasks/set-alarm.md", "# Set an alarm")
            repo.onMemoryMutation(createInput("/memories/tasks/set-alarm.md"))

            val hits = repo.recallHint("please set youtube playback speed to 2x")
            assertTrue(hits.isNotEmpty())
            assertEquals("YouTube playback speed 2x", hits.first().title)

            assertTrue(repo.recallHint("call mom on the phone").isEmpty())
        }

    @Test
    fun `viewing a recipe increments its use count`() =
        runTest {
            val path = "/memories/tasks/x.md"
            writeRecipe(path, "# X")
            repo.onMemoryMutation(createInput(path))
            assertEquals(0, db.taskRecipeDao().getByPath(path)!!.useCount)

            repo.onMemoryMutation(
                buildJsonObject {
                    put("command", "view")
                    put("path", path)
                },
            )
            assertEquals(1, db.taskRecipeDao().getByPath(path)!!.useCount)
        }

    @Test
    fun `deleteRecipe removes both the row and the memory file`() =
        runTest {
            val path = "/memories/tasks/x.md"
            writeRecipe(path, "# X")
            repo.onMemoryMutation(createInput(path))
            val id = db.taskRecipeDao().getByPath(path)!!.id

            repo.deleteRecipe(id)

            assertNull(db.taskRecipeDao().getById(id))
            assertNull(store.readRaw(path))
        }

    @Test
    fun `non-tasks memory writes are not indexed`() =
        runTest {
            val path = "/memories/scratch.md"
            writeRecipe(path, "just notes")
            repo.onMemoryMutation(createInput(path))
            assertTrue(db.taskRecipeDao().getAll().isEmpty())
        }
}
