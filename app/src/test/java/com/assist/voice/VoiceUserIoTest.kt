package com.assist.voice

import app.cash.turbine.test
import com.assist.agent.AgentEvent
import com.assist.agent.AgentEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceUserIoTest {
    private class FakeTts(
        private val fail: Boolean = false,
    ) : TtsEngine {
        val spoken = mutableListOf<String>()

        override suspend fun isAvailable() = true

        override fun voices() = emptyList<VoiceInfo>()

        override suspend fun say(
            text: String,
            opts: SpeakOptions,
        ) {
            if (fail) throw RuntimeException("tts down")
            spoken += text
        }

        override fun stop() {}

        override val isSpeaking = false

        override fun events(): Flow<TtsEvent> = emptyFlow()
    }

    private class FakeStt(
        private val result: SttResult? = null,
        private val error: SttError? = null,
    ) : SttEngine {
        var calls = 0

        override suspend fun isAvailable() = true

        override suspend fun transcribeOnce(config: SttConfig): SttResult {
            calls++
            error?.let { throw SttException(it) }
            return result ?: SttResult("")
        }

        override fun stream(config: SttConfig): Flow<SttEvent> = emptyFlow()

        override fun cancel() {}
    }

    private fun io(
        tts: TtsEngine,
        stt: SttEngine,
        bus: AgentEventBus,
    ) = VoiceUserIo(tts, stt, DefaultAudioSessionArbiter(), bus)

    @Test
    fun `say speaks and emits Speaking`() =
        runTest {
            val tts = FakeTts()
            val bus = AgentEventBus()
            val io = io(tts, FakeStt(), bus)

            val events = mutableListOf<AgentEvent>()
            val collector = launch { bus.events.collect { events += it } }
            runCurrent()

            io.say("hello there")
            runCurrent()

            assertEquals(listOf("hello there"), tts.spoken)
            assertTrue(events.any { it is AgentEvent.Speaking && it.text == "hello there" })
            collector.cancel()
        }

    @Test
    fun `ask speaks the question then returns the transcript`() =
        runTest {
            val tts = FakeTts()
            val stt = FakeStt(SttResult("open the clock app"))
            val bus = AgentEventBus()
            val io = io(tts, stt, bus)

            val events = mutableListOf<AgentEvent>()
            val collector = launch { bus.events.collect { events += it } }
            runCurrent()

            val answer = io.ask("what should I do?")
            runCurrent()

            assertEquals("open the clock app", answer)
            assertEquals(listOf("what should I do?"), tts.spoken)
            assertEquals(1, stt.calls)
            assertTrue(events.any { it is AgentEvent.Speaking })
            assertTrue(events.any { it is AgentEvent.Listening })
            collector.cancel()
        }

    @Test
    fun `ask degrades to empty answer when recognition fails`() =
        runTest {
            val io = io(FakeTts(), FakeStt(error = SttError.NO_SPEECH), AgentEventBus())
            assertEquals("", io.ask("still there?"))
        }

    @Test
    fun `say swallows TTS failure without throwing`() =
        runTest {
            val io = io(FakeTts(fail = true), FakeStt(), AgentEventBus())
            io.say("this should not crash")
        }

    @Test
    fun `ask prefers a typed reply when voice does not answer`() =
        runTest {
            // STT never returns → the typed reply must win the race deterministically.
            val stt =
                object : SttEngine {
                    override suspend fun isAvailable() = true

                    override suspend fun transcribeOnce(config: SttConfig): SttResult =
                        kotlinx.coroutines.CompletableDeferred<SttResult>().await()

                    override fun stream(config: SttConfig): Flow<SttEvent> = emptyFlow()

                    override fun cancel() {}
                }
            val typed = TypedReplySource { "typed answer" }
            val io =
                VoiceUserIo(FakeTts(), stt, DefaultAudioSessionArbiter(), AgentEventBus(), typed)

            assertEquals("typed answer", io.ask("which one?"))
        }

    @Test
    fun `emits Speaking then Listening in order on ask`() =
        runTest {
            val bus = AgentEventBus()
            val io = io(FakeTts(), FakeStt(SttResult("ok")), bus)
            bus.events.test {
                io.ask("go?")
                assertTrue(awaitItem() is AgentEvent.Speaking)
                assertEquals(AgentEvent.Listening, awaitItem())
                cancelAndConsumeRemainingEvents()
            }
        }
}
