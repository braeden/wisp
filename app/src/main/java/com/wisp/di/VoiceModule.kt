package com.wisp.di

import android.content.Context
import com.wisp.agent.AgentEventBus
import com.wisp.agent.AgentLoop
import com.wisp.agent.UserIo
import com.wisp.data.SecretStore
import com.wisp.data.SettingsStore
import com.wisp.voice.AudioSessionArbiter
import com.wisp.voice.DefaultAudioSessionArbiter
import com.wisp.voice.SttEngine
import com.wisp.voice.TtsEngine
import com.wisp.voice.TypedReplySource
import com.wisp.voice.VoiceProvider
import com.wisp.voice.VoiceUserIo
import com.wisp.voice.android.AndroidSttEngine
import com.wisp.voice.android.AndroidTtsEngine
import com.wisp.voice.WakeWordDetector
import com.wisp.voice.android.AndroidVoiceProvider
import com.wisp.voice.android.BargeInDetector
import com.wisp.voice.wake.PorcupineWakeWordDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for `com.wisp.voice` (phase-08). Binds the `android`
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
        settings: SettingsStore,
    ): TtsEngine = AndroidTtsEngine(context, enginePackage = { settings.ttsEngine.value })

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
    fun provideWakeWordDetector(
        @ApplicationContext context: Context,
        secrets: SecretStore,
        arbiter: AudioSessionArbiter,
    ): WakeWordDetector = PorcupineWakeWordDetector(context, secrets, arbiter)

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
