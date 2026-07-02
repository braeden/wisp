package com.assist.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the v1→v2 [AssistDatabase.MIGRATION_1_2] (phase-12): it must add the
 * `task_recipes` table **without** touching existing data — the user has real
 * session history that a destructive migration would wipe.
 *
 * A minimal v1 `sessions` table with one row is created via the framework SQLite
 * helper, the migration is applied directly, and we assert the row survives and
 * the new table exists and is usable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 1 to 2 preserves sessions and creates task_recipes`() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sessions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, `modelDefault` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, `systemPromptVersion` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO sessions (title, createdAt, updatedAt, modelDefault, status, systemPromptVersion) " +
                            "VALUES ('keep me', 1, 1, 'claude-opus-4-8', 'active', 1)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = factory.create(config)
        val db = helper.writableDatabase

        AssistDatabase.MIGRATION_1_2.migrate(db)

        // The pre-existing session row survives the migration.
        db.query("SELECT COUNT(*) FROM sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT title FROM sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("keep me", c.getString(0))
        }

        // The new table exists...
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='task_recipes'").use { c ->
            assertTrue(c.moveToFirst())
        }
        // ...and is usable with the expected columns.
        db.execSQL(
            "INSERT INTO task_recipes (title, intentKeywords, memoryPath, appPackage, useCount, lastUsedAt, createdAt) " +
                "VALUES ('t', 'k', '/memories/tasks/x.md', NULL, 0, 1, 1)",
        )
        db.query("SELECT COUNT(*) FROM task_recipes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        helper.close()
    }
}
