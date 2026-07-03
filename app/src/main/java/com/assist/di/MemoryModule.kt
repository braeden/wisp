package com.assist.di

import android.content.Context
import com.assist.memory.MemoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt bindings for the learned-task memory subsystem (phase-12). The
 * [MemoryStore] backs the Anthropic memory tool with app-private files under
 * `filesDir/memories`. Kept in its own module so parallel phases don't collide.
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {
    @Provides
    @Singleton
    fun provideMemoryStore(
        @ApplicationContext context: Context,
    ): MemoryStore = MemoryStore(rootDir = File(context.filesDir, "memories"))
}
