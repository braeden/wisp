package com.assist.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for sessions, messages, tool calls, usage, media, and notes.
 *
 * **Migration strategy:** destructive for now (`fallbackToDestructiveMigration`
 * in [com.assist.di.DataModule]). This is a sideload-only personal build with no
 * production data to preserve; when the schema stabilizes and real history
 * matters, replace the destructive fallback with numbered [androidx.room.migration.Migration]s
 * and bump [version]. `exportSchema` is off to avoid a schema-output directory.
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        UsageEntity::class,
        MediaEntity::class,
        NoteEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AssistDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun usageDao(): UsageDao
    abstract fun mediaDao(): MediaDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "assist.db"
    }
}
