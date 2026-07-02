package com.assist.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The outcome of a memory command: the text Claude reads back, and error flag. */
data class MemoryResult(val content: String, val isError: Boolean)

/**
 * Executes the six Anthropic memory-tool commands
 * (`view/create/str_replace/insert/delete/rename`) over app-private files under
 * [rootDir] (`filesDir/memories`). Claude emits `memory` `tool_use` blocks; the
 * agent routes them here (via `ToolRouter`) and returns the result as a
 * `tool_result`.
 *
 * Security is this class's job: every path is validated by [MemoryPaths] (which
 * rejects traversal), then re-checked canonically against [rootDir]; files are
 * size-capped at [maxFileChars]. Return/error strings mirror the memory-tool
 * doc so Claude interprets them correctly.
 */
class MemoryStore(
    private val rootDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxFileChars: Int = MAX_FILE_CHARS,
) {

    /** The on-disk directory backing the logical `/memories` root. */
    fun memoriesDir(): File = rootDir

    /** Raw contents of a memory file (used by the recipe indexer), or null. */
    fun readRaw(path: String): String? {
        val f = resolve(path) ?: return null
        return if (f.isFile) f.readText() else null
    }

    /** Delete a memory file/dir (used when the UI deletes a recipe). */
    fun deletePath(path: String): Boolean {
        if (MemoryPaths.isRoot(path)) return false
        val f = resolve(path) ?: return false
        if (!f.exists()) return false
        return f.deleteRecursively()
    }

    /** Parse [rawInputJson] into a command and run it. */
    fun execute(rawInputJson: String): MemoryResult {
        val obj = runCatching { json.parseToJsonElement(rawInputJson) as? JsonObject }.getOrNull()
            ?: return MemoryResult("Error: invalid memory command", true)
        return execute(obj)
    }

    fun execute(input: JsonObject): MemoryResult {
        return when (val command = input.str("command")) {
            "view" -> view(input.str("path"), input.viewRange())
            "create" -> create(input.str("path"), input.str("file_text") ?: "")
            "str_replace" -> strReplace(input.str("path"), input.str("old_str"), input.str("new_str") ?: "")
            "insert" -> insert(input.str("path"), input.intOr("insert_line", -1), input.str("insert_text") ?: "")
            "delete" -> delete(input.str("path"))
            "rename" -> rename(input.str("old_path"), input.str("new_path"))
            null -> MemoryResult("Error: missing command", true)
            else -> MemoryResult("Error: unknown command $command", true)
        }
    }

    // --- Commands -----------------------------------------------------------

    private fun view(path: String?, range: Pair<Int, Int>?): MemoryResult {
        path ?: return MemoryResult("Error: missing path", true)
        val f = resolve(path) ?: return invalidPath(path)
        ensureRoot()
        if (!f.exists()) return MemoryResult(NOT_EXIST_VALID.format(path), true)
        if (f.isDirectory) return MemoryResult(listing(path, f), false)
        val content = f.readText()
        if (MemoryText.lineCount(content) > MemoryText.LINE_LIMIT) {
            return MemoryResult("File $path exceeds maximum line limit of ${MemoryText.LINE_LIMIT} lines.", true)
        }
        val (slice, startLine) = if (range != null) {
            MemoryText.slice(content, range.first, range.second)
                ?: return MemoryResult(
                    "Error: Invalid `view_range` parameter: [${range.first}, ${range.second}]. " +
                        "It should be within the range of lines of the file: [1, ${MemoryText.lineCount(content)}]",
                    true,
                )
        } else {
            content to 1
        }
        val body = MemoryText.withLineNumbers(slice, startLine)
        return MemoryResult("Here's the content of $path with line numbers:\n$body", false)
    }

    private fun create(path: String?, fileText: String): MemoryResult {
        path ?: return MemoryResult("Error: missing path", true)
        val f = resolve(path) ?: return invalidPath(path)
        if (MemoryPaths.isRoot(path)) return MemoryResult("Error: cannot create the /memories directory", true)
        if (f.exists()) return MemoryResult("Error: File $path already exists", true)
        if (fileText.length > maxFileChars) return MemoryResult(tooLarge(path), true)
        f.parentFile?.mkdirs()
        f.writeText(fileText)
        return MemoryResult("File created successfully at: $path", false)
    }

    private fun strReplace(path: String?, oldStr: String?, newStr: String): MemoryResult {
        path ?: return MemoryResult("Error: missing path", true)
        oldStr ?: return MemoryResult("Error: missing old_str", true)
        val f = resolve(path) ?: return invalidPath(path)
        if (!f.isFile) return MemoryResult("Error: The path $path does not exist. Please provide a valid path.", true)
        val content = f.readText()
        return when (val r = MemoryText.strReplace(content, oldStr, newStr)) {
            is MemoryText.ReplaceResult.NotFound ->
                MemoryResult("No replacement was performed, old_str `$oldStr` did not appear verbatim in $path.", true)
            is MemoryText.ReplaceResult.Duplicate ->
                MemoryResult(
                    "No replacement was performed. Multiple occurrences of old_str `$oldStr` " +
                        "in lines: ${r.lineNumbers.joinToString(", ")}. Please ensure it is unique",
                    true,
                )
            is MemoryText.ReplaceResult.Success -> {
                if (r.content.length > maxFileChars) return MemoryResult(tooLarge(path), true)
                f.writeText(r.content)
                MemoryResult("The memory file has been edited.", false)
            }
        }
    }

    private fun insert(path: String?, insertLine: Int, insertText: String): MemoryResult {
        path ?: return MemoryResult("Error: missing path", true)
        val f = resolve(path) ?: return invalidPath(path)
        if (!f.isFile) return MemoryResult("Error: The path $path does not exist", true)
        val content = f.readText()
        return when (val r = MemoryText.insert(content, insertLine, insertText)) {
            is MemoryText.InsertResult.InvalidLine ->
                MemoryResult(
                    "Error: Invalid `insert_line` parameter: $insertLine. " +
                        "It should be within the range of lines of the file: [0, ${r.nLines}]",
                    true,
                )
            is MemoryText.InsertResult.Success -> {
                if (r.content.length > maxFileChars) return MemoryResult(tooLarge(path), true)
                f.writeText(r.content)
                MemoryResult("The file $path has been edited.", false)
            }
        }
    }

    private fun delete(path: String?): MemoryResult {
        path ?: return MemoryResult("Error: missing path", true)
        val f = resolve(path) ?: return invalidPath(path)
        if (MemoryPaths.isRoot(path)) return MemoryResult("Error: cannot delete the /memories directory", true)
        if (!f.exists()) return MemoryResult("Error: The path $path does not exist", true)
        f.deleteRecursively()
        return MemoryResult("Successfully deleted $path", false)
    }

    private fun rename(oldPath: String?, newPath: String?): MemoryResult {
        oldPath ?: return MemoryResult("Error: missing old_path", true)
        newPath ?: return MemoryResult("Error: missing new_path", true)
        val from = resolve(oldPath) ?: return invalidPath(oldPath)
        val to = resolve(newPath) ?: return invalidPath(newPath)
        if (MemoryPaths.isRoot(oldPath)) return MemoryResult("Error: cannot rename the /memories directory", true)
        if (!from.exists()) return MemoryResult("Error: The path $oldPath does not exist", true)
        if (to.exists()) return MemoryResult("Error: The destination $newPath already exists", true)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            // Fall back to copy+delete across boundaries within our own dir.
            from.copyRecursively(to, overwrite = false)
            from.deleteRecursively()
        }
        return MemoryResult("Successfully renamed $oldPath to $newPath", false)
    }

    // --- Path resolution ----------------------------------------------------

    /** Map a logical path to a real [File] under [rootDir], or null if it escapes. */
    private fun resolve(path: String): File? {
        val rel = MemoryPaths.relative(path) ?: return null
        val f = if (rel.isEmpty()) rootDir else File(rootDir, rel)
        val root = rootDir.canonicalFile
        val cf = f.canonicalFile
        if (cf != root && !cf.path.startsWith(root.path + File.separator)) return null
        return cf
    }

    private fun ensureRoot() {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    private fun invalidPath(path: String) =
        MemoryResult("Error: Invalid path $path. Access is restricted to /memories.", true)

    private fun tooLarge(path: String) =
        "Error: File $path exceeds the maximum size of $maxFileChars characters."

    private fun listing(path: String, dir: File): String {
        val header = "Here're the files and directories up to 2 levels deep in $path, " +
            "excluding hidden items and node_modules:"
        val entries = mutableListOf("${humanSize(sizeOf(dir))}\t$path")
        collect(dir, path, depth = 0, into = entries)
        return header + "\n" + entries.joinToString("\n")
    }

    private fun collect(dir: File, logicalDir: String, depth: Int, into: MutableList<String>) {
        if (depth >= 2) return
        val children = dir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name != "node_modules" }
            ?.sortedBy { it.name }
            ?: return
        for (child in children) {
            val logical = "$logicalDir/${child.name}"
            into += "${humanSize(sizeOf(child))}\t$logical"
            if (child.isDirectory) collect(child, logical, depth + 1, into)
        }
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fK".format(bytes / 1024.0)
        else -> "%.1fM".format(bytes / (1024.0 * 1024.0))
    }

    // --- JSON accessors -----------------------------------------------------

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.intOr(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default

    private fun JsonObject.viewRange(): Pair<Int, Int>? {
        val arr = this["view_range"]?.jsonArray ?: return null
        if (arr.size < 2) return null
        return arr[0].jsonPrimitive.int to arr[1].jsonPrimitive.int
    }

    companion object {
        /** Per-file size cap; recipes are small, so this is deliberately generous. */
        const val MAX_FILE_CHARS = 100_000
        private const val NOT_EXIST_VALID = "The path %s does not exist. Please provide a valid path."
    }
}
