package com.assist.di

import android.content.Context
import androidx.room.Room
import com.assist.data.AssistDatabase
import com.assist.data.ContextTracker
import com.assist.data.CostCalculator
import com.assist.data.ScreenshotStore
import com.assist.data.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for phase-05 (session DB + context management). Kept separate
 * from `AppModule` so parallel phases don't collide on one file.
 *
 * Migration: destructive for now — see [AssistDatabase] doc.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AssistDatabase =
        Room.databaseBuilder(context, AssistDatabase::class.java, AssistDatabase.NAME)
            .fallbackToDestructiveMigration()
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
}
