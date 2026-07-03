package com.assist.di

import android.content.Context
import com.assist.agent.AgentEventBus
import com.assist.agent.AgentLoop
import com.assist.agent.UserIo
import com.assist.voice.AudioSessionArbiter
import com.assist.voice.DefaultAudioSessionArbiter
import com.assist.voice.SttEngine
import com.assist.voice.TtsEngine
import com.assist.voice.TypedReplySource
import com.assist.voice.VoiceProvider
import com.assist.voice.VoiceUserIo
import com.assist.voice.android.AndroidSttEngine
import com.assist.voice.android.AndroidTtsEngine
import com.assist.voice.android.AndroidVoiceProvider
import com.assist.voice.android.BargeInDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for `com.assist.voice` (phase-08). Binds the `android`
 * [VoiceProvider] (SpeechRecognizer STT + TextToSpeech TTS) and the real
 * [UserIo] ([VoiceUserIo]) — which **replaces** phase-06's `LoggingUserIo`
 * binding (removed from `AgentModule` to avoid a duplicate `UserIo`). Backend
 * types stay in `voice/android`; only the seam interfaces are exposed here.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides
    @Singleton
    fun provideTtsEngine(
        @ApplicationContext context: Context,
    ): TtsEngine = AndroidTtsEngine(context)

    @Provides
    @Singleton
    fun provideSttEngine(
        @ApplicationContext context: Context,
    ): SttEngine = AndroidSttEngine(context)

    @Provides
    @Singleton
    fun provideAudioSessionArbiter(): AudioSessionArbiter = DefaultAudioSessionArbiter()

    @Provides
    @Singleton
    fun provideVoiceProvider(
        stt: SttEngine,
        tts: TtsEngine,
    ): VoiceProvider = AndroidVoiceProvider(stt, tts)

    @Provides
    @Singleton
    fun provideUserIo(
        tts: TtsEngine,
        stt: SttEngine,
        arbiter: AudioSessionArbiter,
        bus: AgentEventBus,
        typedReplies: TypedReplySource,
    ): UserIo =
        VoiceUserIo(
            tts = tts,
            stt = stt,
            arbiter = arbiter,
            bus = bus,
            typedReplies = typedReplies,
        )

    @Provides
    @Singleton
    fun provideBargeInDetector(
        @ApplicationContext context: Context,
        tts: TtsEngine,
        stt: SttEngine,
        arbiter: AudioSessionArbiter,
        agentLoop: AgentLoop,
    ): BargeInDetector = BargeInDetector(context, tts, stt, arbiter, agentLoop)
}
