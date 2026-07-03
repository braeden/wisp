package com.assist.di

import com.assist.prompt.DefaultSystemPromptProvider
import com.assist.prompt.SystemPromptProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the phase-10 system-prompt provider. Separate from [AppModule] so phase-10
 * owns its own DI wiring (per the boundary rule).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PromptModule {
    @Binds
    @Singleton
    abstract fun bindSystemPromptProvider(impl: DefaultSystemPromptProvider): SystemPromptProvider
}
