package com.assist.di

import com.assist.data.SecretStore
import com.assist.llm.LlmClient
import com.assist.llm.anthropic.AnthropicLlmClient
import com.assist.llm.anthropic.ModelRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the LLM layer: a Claude-backed [LlmClient] and the [ModelRouter].
 * Separate from [AppModule] so phase-04 owns its own DI wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides
    @Singleton
    fun provideLlmJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Provides
    @Singleton
    fun provideLlmOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Streaming turns can be long-lived; disable read/call timeouts and rely
            // on coroutine cancellation to abort (interruptibility, phase-08).
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    @Provides
    @Singleton
    fun provideModelRouter(): ModelRouter = ModelRouter()

    @Provides
    @Singleton
    fun provideLlmClient(
        secretStore: SecretStore,
        okHttpClient: OkHttpClient,
        json: Json,
    ): LlmClient =
        AnthropicLlmClient(
            secretStore = secretStore,
            okHttp = okHttpClient,
            json = json,
        )
}
