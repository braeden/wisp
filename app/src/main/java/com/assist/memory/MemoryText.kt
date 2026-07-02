package com.assist.memory

/**
 * Framework-free string editing for the memory tool (phase-12): line numbering,
 * `str_replace` occurrence logic, and `insert` indexing. Pure functions over
 * strings so they can be unit-tested without any filesystem. [MemoryStore]
 * composes the user-facing result strings (which embed the path) around these.
 */
object MemoryText {

    /** Files longer than this many lines are refused by `view` (per the doc). */
    const val LINE_LIMIT = 999_999

    /** Render [content] with 6-wide, right-aligned, 1-indexed line numbers + tab. */
    fun withLineNumbers(content: String, startLine: Int = 1): String {
        val lines = content.split("\n")
        return lines.mapIndexed { i, line ->
            "%6d\t%s".format(startLine + i, line)
        }.joinToString("\n")
    }

    /** Number of lines in [content] (an empty string counts as one line). */
    fun lineCount(content: String): Int = content.split("\n").size

    sealed interface ReplaceResult {
        data class Success(val content: String) : ReplaceResult
        data object NotFound : ReplaceResult
        data class Duplicate(val lineNumbers: List<Int>) : ReplaceResult
    }

    /**
     * Replace the single occurrence of [oldStr] with [newStr] in [content].
     * Returns [ReplaceResult.NotFound] if absent, [ReplaceResult.Duplicate] with
     * the 1-indexed lines of each match if it appears more than once.
     */
    fun strReplace(content: String, oldStr: String, newStr: String): ReplaceResult {
        if (oldStr.isEmpty()) return ReplaceResult.NotFound
        val starts = occurrences(content, oldStr)
        return when {
            starts.isEmpty() -> ReplaceResult.NotFound
            starts.size > 1 -> ReplaceResult.Duplicate(starts.map { lineOf(content, it) })
            else -> {
                val at = starts.first()
                ReplaceResult.Success(content.substring(0, at) + newStr + content.substring(at + oldStr.length))
            }
        }
    }

    sealed interface InsertResult {
        data class Success(val content: String) : InsertResult
        data class InvalidLine(val nLines: Int) : InsertResult
    }

    /**
     * Insert [insertText] after line [insertLine] (0 = beginning). A trailing
     * newline on [insertText] is trimmed to match the reference behavior.
     */
    fun insert(content: String, insertLine: Int, insertText: String): InsertResult {
        val lines = content.split("\n").toMutableList()
        if (insertLine < 0 || insertLine > lines.size) return InsertResult.InvalidLine(lines.size)
        lines.add(insertLine, insertText.removeSuffix("\n"))
        return InsertResult.Success(lines.joinToString("\n"))
    }

    /**
     * Slice [content] to the inclusive 1-indexed [start]..[end] line range
     * (`end == -1` means to the end of the file). Returns the sliced content and
     * the first line's number (for numbering), or null if the range is invalid.
     */
    fun slice(content: String, start: Int, end: Int): Pair<String, Int>? {
        val lines = content.split("\n")
        if (start < 1 || start > lines.size) return null
        val last = if (end == -1) lines.size else end
        if (last < start || last > lines.size) return null
        return lines.subList(start - 1, last).joinToString("\n") to start
    }

    private fun occurrences(s: String, sub: String): List<Int> {
        val res = mutableListOf<Int>()
        var idx = s.indexOf(sub)
        while (idx >= 0) {
            res += idx
            idx = s.indexOf(sub, idx + sub.length)
        }
        return res
    }

    private fun lineOf(s: String, charOffset: Int): Int =
        s.substring(0, charOffset).count { it == '\n' } + 1
}
