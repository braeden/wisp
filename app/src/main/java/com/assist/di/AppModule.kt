package com.assist.di

import android.content.Context
import com.assist.data.EncryptedSecretStore
import com.assist.data.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the app-scoped [CoroutineScope] (lives for the process). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSecretStore(
        @ApplicationContext context: Context,
    ): SecretStore = EncryptedSecretStore(context)

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
