package com.assist.di

import com.assist.agent.ActionGate
import com.assist.agent.Phase10SystemPromptProvider
import com.assist.agent.SystemPromptProvider
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
 * injected. This module supplies the swappable seams it still owns:
 * - [SystemPromptProvider] → [Phase10SystemPromptProvider], which adapts the real
 *   phase-10 `com.assist.prompt.SystemPromptProvider` (bound in [PromptModule]).
 *
 * The `UserIo` binding moved to `VoiceModule` (phase-08): the real
 * `VoiceUserIo` replaces the `LoggingUserIo` stub. `LoggingUserIo` is retained in
 * `com.assist.agent` as a headless/test fallback.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideSystemPromptProvider(
        delegate: com.assist.prompt.SystemPromptProvider,
    ): SystemPromptProvider = Phase10SystemPromptProvider(delegate)

    @Provides
    @Singleton
    fun provideActionGate(): ActionGate = ActionGate()
}
