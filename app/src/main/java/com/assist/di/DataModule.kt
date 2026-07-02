package com.assist.di

import android.content.Context
import androidx.room.Room
import com.assist.data.AssistDatabase
import com.assist.data.ContextTracker
import com.assist.data.CostCalculator
import com.assist.data.PrefsSettingsStore
import com.assist.data.ScreenshotStore
import com.assist.data.SessionRepository
import com.assist.data.SettingsStore
import com.assist.data.TaskMemoryRepository
import com.assist.data.TaskRecipeDao
import com.assist.memory.MemoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for phase-05 (session DB + context management) plus phase-12
 * additions (settings, task-recipe index). Kept separate from `AppModule` so
 * parallel phases don't collide on one file.
 *
 * Migration (phase-12): a real numbered `MIGRATION_1_2` — no destructive
 * fallback, so the user's session history survives the schema bump.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AssistDatabase =
        Room.databaseBuilder(context, AssistDatabase::class.java, AssistDatabase.NAME)
            .addMigrations(AssistDatabase.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun provideScreenshotStore(@ApplicationContext context: Context): ScreenshotStore =
        ScreenshotStore(context.filesDir)

    @Provides
    @Singleton
    fun provideCostCalculator(): CostCalculator = CostCalculator()

    @Provides
    @Singleton
    fun provideSessionRepository(
        db: AssistDatabase,
        screenshotStore: ScreenshotStore,
        costCalculator: CostCalculator,
    ): SessionRepository = SessionRepository(db, screenshotStore, costCalculator)

    @Provides
    @Singleton
    fun provideContextTracker(
        db: AssistDatabase,
        costCalculator: CostCalculator,
    ): ContextTracker = ContextTracker(db, costCalculator)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        PrefsSettingsStore(context)

    @Provides
    fun provideTaskRecipeDao(db: AssistDatabase): TaskRecipeDao = db.taskRecipeDao()

    @Provides
    @Singleton
    fun provideTaskMemoryRepository(
        taskRecipeDao: TaskRecipeDao,
        memoryStore: MemoryStore,
    ): TaskMemoryRepository = TaskMemoryRepository(taskRecipeDao, memoryStore)
}
