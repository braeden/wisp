package com.assist.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for sessions, messages, tool calls, usage, media, notes, and
 * (v2) learned task recipes.
 *
 * **Migration strategy (phase-12):** real numbered migrations — the user has
 * live session history that must survive schema changes, so the destructive
 * fallback is gone. v1→v2 only adds the `task_recipes` index table; every
 * existing table and row is left untouched. `exportSchema` is on so the
 * generated schema JSON documents the create statement the migration mirrors.
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        UsageEntity::class,
        MediaEntity::class,
        NoteEntity::class,
        TaskRecipeEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AssistDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun toolCallDao(): ToolCallDao

    abstract fun usageDao(): UsageDao

    abstract fun mediaDao(): MediaDao

    abstract fun noteDao(): NoteDao

    abstract fun taskRecipeDao(): TaskRecipeDao

    companion object {
        const val NAME = "assist.db"

        /**
         * v1 → v2 (phase-12): add the `task_recipes` index table. Additive only —
         * no existing table is altered, so all sessions/messages/usage survive.
         * The CREATE statements mirror Room's generated schema exactly (see
         * `schemas/com.assist.data.AssistDatabase/2.json`).
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `task_recipes` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`intentKeywords` TEXT NOT NULL, " +
                            "`memoryPath` TEXT NOT NULL, " +
                            "`appPackage` TEXT, " +
                            "`useCount` INTEGER NOT NULL, " +
                            "`lastUsedAt` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_task_recipes_memoryPath` ON `task_recipes` (`memoryPath`)",
                    )
                }
            }
    }
}
