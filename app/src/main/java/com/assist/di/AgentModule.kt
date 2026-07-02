package com.assist.di

import com.assist.agent.ActionGate
import com.assist.agent.LoggingUserIo
import com.assist.agent.PlaceholderSystemPromptProvider
import com.assist.agent.SystemPromptProvider
import com.assist.agent.UserIo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `com.assist.agent` (phase-06). Separate from [AppModule] so
 * parallel phases don't collide on one module file.
 *
 * The agent loop, tool router, action gate, and event bus are constructor-
 * injected. This module only supplies the two swappable seams:
 * - [UserIo] → [LoggingUserIo] stub (phase-08 swaps in the real voice impl);
 * - [SystemPromptProvider] → [PlaceholderSystemPromptProvider] (phase-10 swaps
 *   in the real prompt).
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideUserIo(): UserIo = LoggingUserIo()

    @Provides
    @Singleton
    fun provideSystemPromptProvider(): SystemPromptProvider = PlaceholderSystemPromptProvider()

    @Provides
    @Singleton
    fun provideActionGate(): ActionGate = ActionGate()
}
