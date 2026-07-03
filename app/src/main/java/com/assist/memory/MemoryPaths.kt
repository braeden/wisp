package com.assist.memory

/**
 * Framework-free path math + traversal protection for the memory tool
 * (phase-12). All memory paths are logical, rooted at [ROOT] (`/memories`),
 * which [MemoryStore] maps onto app-private storage. Split out from the file-IO
 * so the (security-critical) validation is trivially unit-testable on the JVM.
 *
 * Rejection rules mirror the Anthropic memory-tool "Security considerations":
 * every path must start with `/memories`; any `..` segment, backslash, or
 * URL-encoded traversal (`%2e` / `%2f` / `%5c`) is rejected outright rather than
 * resolved.
 */
object MemoryPaths {
    const val ROOT = "/memories"

    /** Directory under which task recipes live, relative to the root. */
    const val TASKS_DIR = "tasks"

    /**
     * Canonicalize [path] to a normalized logical path under [ROOT], or return
     * null if it is malformed or attempts to escape the memory root.
     */
    fun normalize(path: String): String? {
        if (path.isBlank()) return null
        val lower = path.lowercase()
        // Reject URL-encoded traversal / separators before any decoding.
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return null
        // Reject Windows separators; we only speak POSIX-style logical paths.
        if (path.contains('\\')) return null
        if (!path.startsWith("/")) return null
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        // Any parent-traversal segment is rejected, not resolved.
        if (segments.any { it == ".." }) return null
        if (segments.isEmpty()) return null
        if (segments.first() != "memories") return null
        return "/" + segments.joinToString("/")
    }

    /** True if [path] normalizes to the memory root itself. */
    fun isRoot(path: String): Boolean = normalize(path) == ROOT

    /**
     * The portion of [path] relative to [ROOT] (e.g. `tasks/foo.md`), or null if
     * invalid. The root itself maps to the empty string.
     */
    fun relative(path: String): String? {
        val norm = normalize(path) ?: return null
        return norm.removePrefix(ROOT).removePrefix("/")
    }

    /** True if [path] is a recipe file at `/memories/tasks/<slug>.md`. */
    fun isTaskRecipe(path: String): Boolean {
        val rel = relative(path) ?: return false
        if (!rel.startsWith("$TASKS_DIR/") || !rel.endsWith(".md")) return false
        // Only direct children of tasks/ count as recipes (no nested dirs).
        return !rel.removePrefix("$TASKS_DIR/").contains('/')
    }

    /** The `<slug>` of a `/memories/tasks/<slug>.md` recipe path, or null. */
    fun taskSlug(path: String): String? {
        if (!isTaskRecipe(path)) return null
        return relative(path)!!.removePrefix("$TASKS_DIR/").removeSuffix(".md")
    }
}
