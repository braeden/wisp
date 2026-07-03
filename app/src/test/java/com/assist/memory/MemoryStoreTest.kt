package com.assist.memory

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for the memory tool store (phase-12): the exact success/error
 * strings for each command, path-traversal rejection, and the size cap. Runs on
 * the plain JVM against a temp directory — no Android/Robolectric needed.
 */
class MemoryStoreTest {
    private lateinit var baseDir: File
    private lateinit var root: File
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        baseDir =
            File.createTempFile("mem", "").apply {
                delete()
                mkdirs()
            }
        root = File(baseDir, "memories")
        store = MemoryStore(rootDir = root)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun create(
        path: String,
        text: String,
    ) = store.execute(
        buildJsonObject {
            put("command", "create")
            put("path", path)
            put("file_text", text)
        },
    )

    // --- create -------------------------------------------------------------

    @Test
    fun `create returns success string and view round-trips with line numbers`() {
        val r = create("/memories/notes.txt", "Hello World\nline two")
        assertFalse(r.isError)
        assertEquals("File created successfully at: /memories/notes.txt", r.content)

        val view =
            store.execute(
                buildJsonObject {
                    put("command", "view")
                    put("path", "/memories/notes.txt")
                },
            )
        assertFalse(view.isError)
        assertEquals(
            "Here's the content of /memories/notes.txt with line numbers:\n" +
                "     1\tHello World\n" +
                "     2\tline two",
            view.content,
        )
    }

    @Test
    fun `create on existing file errors`() {
        create("/memories/a.txt", "x")
        val r = create("/memories/a.txt", "y")
        assertTrue(r.isError)
        assertEquals("Error: File /memories/a.txt already exists", r.content)
    }

    @Test
    fun `create enforces the size cap`() {
        val small = MemoryStore(rootDir = File(baseDir, "capped"), maxFileChars = 10)
        val r =
            small.execute(
                buildJsonObject {
                    put("command", "create")
                    put("path", "/memories/big.txt")
                    put("file_text", "0123456789X")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "Error: File /memories/big.txt exceeds the maximum size of 10 characters.",
            r.content,
        )
    }

    // --- view ---------------------------------------------------------------

    @Test
    fun `view of a missing path errors`() {
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "view")
                    put("path", "/memories/none.txt")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "The path /memories/none.txt does not exist. Please provide a valid path.",
            r.content,
        )
    }

    @Test
    fun `view of a directory lists entries with the header`() {
        create("/memories/tasks/a.md", "one")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "view")
                    put("path", "/memories")
                },
            )
        assertFalse(r.isError)
        assertTrue(
            r.content.startsWith(
                "Here're the files and directories up to 2 levels deep in /memories, " +
                    "excluding hidden items and node_modules:",
            ),
        )
        assertTrue(r.content.contains("/memories/tasks/a.md"))
    }

    @Test
    fun `view with a range slices and keeps real line numbers`() {
        create("/memories/n.txt", "l1\nl2\nl3\nl4")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "view")
                    put("path", "/memories/n.txt")
                    putJsonArray("view_range") {
                        add(2)
                        add(3)
                    }
                },
            )
        assertEquals(
            "Here's the content of /memories/n.txt with line numbers:\n" +
                "     2\tl2\n" +
                "     3\tl3",
            r.content,
        )
    }

    // --- str_replace --------------------------------------------------------

    @Test
    fun `str_replace edits the single occurrence`() {
        create("/memories/p.txt", "Favorite color: blue")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "str_replace")
                    put("path", "/memories/p.txt")
                    put("old_str", "blue")
                    put("new_str", "green")
                },
            )
        assertFalse(r.isError)
        assertEquals("The memory file has been edited.", r.content)
        assertTrue(store.readRaw("/memories/p.txt")!!.contains("green"))
    }

    @Test
    fun `str_replace reports text not found`() {
        create("/memories/p.txt", "abc")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "str_replace")
                    put("path", "/memories/p.txt")
                    put("old_str", "zzz")
                    put("new_str", "y")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "No replacement was performed, old_str `zzz` did not appear verbatim in /memories/p.txt.",
            r.content,
        )
    }

    @Test
    fun `str_replace reports duplicate occurrences with line numbers`() {
        create("/memories/p.txt", "foo\nfoo")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "str_replace")
                    put("path", "/memories/p.txt")
                    put("old_str", "foo")
                    put("new_str", "bar")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "No replacement was performed. Multiple occurrences of old_str `foo` in lines: 1, 2. Please ensure it is unique",
            r.content,
        )
    }

    @Test
    fun `str_replace on a missing file errors`() {
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "str_replace")
                    put("path", "/memories/none.txt")
                    put("old_str", "a")
                    put("new_str", "b")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "Error: The path /memories/none.txt does not exist. Please provide a valid path.",
            r.content,
        )
    }

    // --- insert -------------------------------------------------------------

    @Test
    fun `insert adds a line and reports success`() {
        create("/memories/t.txt", "a\nb")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "insert")
                    put("path", "/memories/t.txt")
                    put("insert_line", 1)
                    put("insert_text", "MID")
                },
            )
        assertFalse(r.isError)
        assertEquals("The file /memories/t.txt has been edited.", r.content)
        assertEquals("a\nMID\nb", store.readRaw("/memories/t.txt"))
    }

    @Test
    fun `insert with an out-of-range line errors`() {
        create("/memories/t.txt", "a\nb")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "insert")
                    put("path", "/memories/t.txt")
                    put("insert_line", 99)
                    put("insert_text", "x")
                },
            )
        assertTrue(r.isError)
        assertEquals(
            "Error: Invalid `insert_line` parameter: 99. It should be within the range of lines of the file: [0, 2]",
            r.content,
        )
    }

    // --- delete / rename ----------------------------------------------------

    @Test
    fun `delete removes the file and reports success`() {
        create("/memories/d.txt", "x")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "delete")
                    put("path", "/memories/d.txt")
                },
            )
        assertFalse(r.isError)
        assertEquals("Successfully deleted /memories/d.txt", r.content)
        assertFalse(File(root, "d.txt").exists())
    }

    @Test
    fun `delete of the root is rejected`() {
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "delete")
                    put("path", "/memories")
                },
            )
        assertTrue(r.isError)
        assertEquals("Error: cannot delete the /memories directory", r.content)
    }

    @Test
    fun `rename moves the file`() {
        create("/memories/a.txt", "x")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "rename")
                    put("old_path", "/memories/a.txt")
                    put("new_path", "/memories/b.txt")
                },
            )
        assertFalse(r.isError)
        assertEquals("Successfully renamed /memories/a.txt to /memories/b.txt", r.content)
        assertEquals("x", store.readRaw("/memories/b.txt"))
    }

    @Test
    fun `rename onto an existing destination errors`() {
        create("/memories/a.txt", "x")
        create("/memories/b.txt", "y")
        val r =
            store.execute(
                buildJsonObject {
                    put("command", "rename")
                    put("old_path", "/memories/a.txt")
                    put("new_path", "/memories/b.txt")
                },
            )
        assertTrue(r.isError)
        assertEquals("Error: The destination /memories/b.txt already exists", r.content)
    }

    // --- path traversal -----------------------------------------------------

    @Test
    fun `dot-dot traversal is rejected and writes nothing outside the root`() {
        val r = create("/memories/../secret.txt", "leak")
        assertTrue(r.isError)
        assertTrue(r.content.contains("Invalid path"))
        assertFalse(File(baseDir, "secret.txt").exists())
    }

    @Test
    fun `encoded traversal is rejected`() {
        val r = create("/memories/%2e%2e/secret.txt", "leak")
        assertTrue(r.isError)
        assertTrue(r.content.contains("Invalid path"))
        assertFalse(File(baseDir, "secret.txt").exists())
    }

    @Test
    fun `paths outside memories are rejected`() {
        val r = create("/etc/passwd", "leak")
        assertTrue(r.isError)
        assertTrue(r.content.contains("Invalid path"))
    }

    @Test
    fun `backslash paths are rejected`() {
        val r = create("/memories/..\\secret.txt", "leak")
        assertTrue(r.isError)
        assertTrue(r.content.contains("Invalid path"))
    }
}
