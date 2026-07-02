package com.assist.data

import com.assist.memory.MemoryPaths
import com.assist.memory.MemoryStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** UI/domain view of a learned task recipe (index over a memory file). */
data class TaskRecipe(
    val id: Long,
    val title: String,
    val appPackage: String?,
    val useCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val memoryPath: String,
    val intentKeywords: String,
)

/**
 * Keeps the [TaskRecipeEntity] index in sync with the `/memories/tasks/<slug>.md`
 * recipe files the agent writes and uses (phase-12). The memory file is the
 * source of truth; this index just makes recipes listable, searchable, and
 * prunable. Also provides a cheap `recallHint` so the agent can be pointed at a
 * matching recipe for a new intent.
 */
class TaskMemoryRepository(
    private val recipes: TaskRecipeDao,
    private val memoryStore: MemoryStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun listRecipes(): Flow<List<TaskRecipe>> =
        recipes.observeAll().map { list -> list.map { it.toDomain() } }

    /** The recipe file's raw markdown, for the "view content" UI action. */
    suspend fun recipeContent(id: Long): String? = withContext(ioDispatcher) {
        val row = recipes.getById(id) ?: return@withContext null
        memoryStore.readRaw(row.memoryPath)
    }

    /** Delete both the index row and the backing memory file. */
    suspend fun deleteRecipe(id: Long) = withContext(ioDispatcher) {
        val row = recipes.getById(id) ?: return@withContext
        memoryStore.deletePath(row.memoryPath)
        recipes.deleteById(id)
    }

    /**
     * Recipes whose keywords/title overlap [intent], best match first (ties
     * broken by [TaskRecipeEntity.useCount]). Optional pre-seeding for the agent.
     */
    suspend fun recallHint(intent: String, limit: Int = 3): List<TaskRecipe> = withContext(ioDispatcher) {
        val wanted = tokenize(intent)
        if (wanted.isEmpty()) return@withContext emptyList()
        recipes.getAll()
            .map { row -> row to score(wanted, tokenize("${row.intentKeywords} ${row.title}")) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<TaskRecipeEntity, Int>> { it.second }.thenByDescending { it.first.useCount })
            .take(limit)
            .map { it.first.toDomain() }
    }

    /**
     * Called after a successful memory `tool_use`. Maintains the recipe index for
     * writes/uses/renames/deletes of `/memories/tasks/<slug>.md` files.
     */
    suspend fun onMemoryMutation(input: JsonObject) = withContext(ioDispatcher) {
        when (input.str("command")) {
            "view" -> input.str("path")?.let { if (MemoryPaths.isTaskRecipe(it)) recipes.recordUse(it, now()) }
            "create", "str_replace", "insert" ->
                input.str("path")?.let { if (MemoryPaths.isTaskRecipe(it)) indexRecipe(it) }
            "delete" -> input.str("path")?.let { if (MemoryPaths.isTaskRecipe(it)) recipes.deleteByPath(it) }
            "rename" -> {
                val old = input.str("old_path")
                val new = input.str("new_path")
                if (old != null && MemoryPaths.isTaskRecipe(old)) recipes.deleteByPath(old)
                if (new != null && MemoryPaths.isTaskRecipe(new)) indexRecipe(new)
            }
            else -> Unit
        }
    }

    /** Read the recipe file and upsert its index row (title/keywords/app). */
    private suspend fun indexRecipe(path: String) {
        val content = memoryStore.readRaw(path)
        val slug = MemoryPaths.taskSlug(path) ?: return
        val title = deriveTitle(content, slug)
        val appPackage = content?.let { deriveAppPackage(it) }
        val keywords = (tokenize(title) + tokenize(slug.replace('-', ' '))).distinct().joinToString(" ")
        val existing = recipes.getByPath(path)
        val ts = now()
        if (existing == null) {
            recipes.insert(
                TaskRecipeEntity(
                    title = title,
                    intentKeywords = keywords,
                    memoryPath = path,
                    appPackage = appPackage,
                    useCount = 0,
                    lastUsedAt = ts,
                    createdAt = ts,
                ),
            )
        } else {
            recipes.update(
                existing.copy(
                    title = title,
                    intentKeywords = keywords,
                    appPackage = appPackage ?: existing.appPackage,
                    lastUsedAt = ts,
                ),
            )
        }
    }

    private fun deriveTitle(content: String?, slug: String): String {
        val firstLine = content?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        val fromContent = firstLine?.trimStart('#', ' ', '\t')?.take(120)?.takeIf { it.isNotBlank() }
        return fromContent ?: slug.replace('-', ' ').replace('_', ' ').trim()
    }

    private fun deriveAppPackage(content: String): String? {
        // Match a dotted package on an "App:"/"Package:" line, else any bare package token.
        val pkg = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")
        val appLine = content.lineSequence().firstOrNull {
            val l = it.lowercase().trimStart('-', '*', ' ', '\t')
            l.startsWith("app") || l.startsWith("package")
        }
        return (appLine?.let { pkg.find(it)?.value }) ?: pkg.find(content)?.value
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    private fun score(wanted: Set<String>, have: Set<String>): Int = wanted.count { it in have }

    private fun TaskRecipeEntity.toDomain() = TaskRecipe(
        id = id,
        title = title,
        appPackage = appPackage,
        useCount = useCount,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt,
        memoryPath = memoryPath,
        intentKeywords = intentKeywords,
    )

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private companion object {
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "set", "app", "run", "get", "use", "you", "your",
            "this", "that", "how", "can", "please", "make", "want",
        )
    }
}
