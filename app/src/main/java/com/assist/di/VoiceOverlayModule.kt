package com.assist.di

import com.assist.overlay.OverlayController
import com.assist.voice.TypedReplySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Integration seam between phase-07 (overlay) and phase-08 (voice): supplies the
 * voice layer's [TypedReplySource] from the overlay's typed-reply channel, so
 * `VoiceUserIo.ask()` races a typed overlay reply against the spoken answer and
 * takes whichever arrives first.
 *
 * Kept in its own module so `VoiceModule` and `OverlayController` stay unaware of
 * each other — only this wiring knows about both. If the overlay isn't showing,
 * [OverlayController.awaitTypedReply] simply never completes and the spoken answer
 * wins, so this binding is safe to always install.
 *
 * [OverlayController] is injected as a [Provider] to break the Dagger dependency
 * cycle (`OverlayController → AgentLoop → UserIo → TypedReplySource → …`): the
 * controller is only resolved lazily at reply-time, not at graph construction.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceOverlayModule {
    @Provides
    @Singleton
    fun provideTypedReplySource(overlay: Provider<OverlayController>): TypedReplySource =
        TypedReplySource { overlay.get().awaitTypedReply() }
}
