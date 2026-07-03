package com.assist.overlay

import android.util.Log
import com.assist.agent.AgentEvent
import com.assist.agent.AgentEventBus
import com.assist.agent.AgentLoop
import com.assist.data.ContextTracker
import com.assist.data.SessionEntity
import com.assist.data.SessionRepository
import com.assist.data.SettingsStore
import com.assist.di.AppScope
import com.assist.voice.AudioSessionArbiter
import com.assist.voice.MicOwner
import com.assist.voice.SttEngine
import com.assist.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the [AgentEventBus] into an [OverlayUiState] the overlay renders, and
 * exposes the overlay's controls (interrupt, session switch, compact / drop, and
 * the typed-reply seam) as plain suspend/`tryEmit` calls.
 *
 * **Typed-reply contract (for `voice/VoiceUserIo.ask()` merge wiring).** The
 * overlay never edits `UserIo`; instead it surfaces the user's typed answer here:
 * - [submitReply] — the overlay UI calls this when the user submits a typed reply
 *   or taps a confirmation Yes/No. Non-blocking, buffered.
 * - [awaitTypedReply] — suspends until the next reply. `VoiceUserIo.ask()` should
 *   race this against speech recognition (`select { voiceAnswer(); awaitTypedReply() }`)
 *   and take whichever returns first. [typedReplies] is the raw stream if the
 *   merge prefers to wire it directly.
 *
 * Framework-free apart from the injected coroutine scope: the reducing/throttling
 * logic is unit-tested (see `OverlayReducerTest`, `ThrottleTest`, `OverlayControllerTest`).
 */
@Singleton
class OverlayController @Inject constructor(
    private val bus: AgentEventBus,
    private val agentLoop: AgentLoop,
    private val repository: SessionRepository,
    private val contextTracker: ContextTracker,
    private val stt: SttEngine,
    private val tts: TtsEngine,
    private val arbiter: AudioSessionArbiter,
    private val settings: SettingsStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val reducer = OverlayReducer()

    private val expanded = MutableStateFlow(false)
    private val hud = MutableSharedFlow<OverlayInput>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val typed = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The most recent run's session id, for HUD queries and context ops. */
    @Volatile
    private var currentSessionId: Long? = null

    /** The overlay's single source of truth. */
    val uiState: StateFlow<OverlayUiState> = buildState()

    /** Sessions for the switch-session list. */
    val sessions: StateFlow<List<SessionEntity>> =
        repository.listSessions()
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Raw typed-reply stream (see class doc). */
    val typedReplies: Flow<String> get() = typed.asSharedFlow()

    init {
        // HUD is DB-backed (suspend), so it can't live in the pure fold: refresh it
        // whenever a run starts or its token accounting changes.
        scope.launch {
            bus.events.collect { event ->
                when (event) {
                    is AgentEvent.Started -> {
                        currentSessionId = event.sessionId
                        refreshHud()
                    }
                    is AgentEvent.UsageUpdated,
                    is AgentEvent.ToolCallFinished,
                    is AgentEvent.Finished -> refreshHud()
                    else -> Unit
                }
            }
        }
    }

    // --- Panel expand / collapse -------------------------------------------

    fun setExpanded(value: Boolean) {
        expanded.value = value
    }

    fun toggleExpanded() {
        expanded.value = !expanded.value
    }

    // --- Controls -----------------------------------------------------------

    /** Barge-in: cancel the in-flight task. */
    fun interrupt() {
        Log.i(TAG, "overlay interrupt")
        agentLoop.interrupt()
    }

    /** Start a fresh session (no run yet); becomes the current session. */
    fun newSession() {
        scope.launch {
            val session = repository.createSession(
                title = "New session",
                model = settings.getAgentModel().modelId,
            )
            currentSessionId = session.id
            hud.tryEmit(OverlayInput.SessionChanged(session.id))
            refreshHud()
            Log.i(TAG, "overlay new session=${session.id}")
        }
    }

    /** Switch the current/HUD session to [sessionId]. */
    fun switchSession(sessionId: Long) {
        scope.launch {
            repository.resumeSession(sessionId)
            currentSessionId = sessionId
            hud.tryEmit(OverlayInput.SessionChanged(sessionId))
            refreshHud()
            Log.i(TAG, "overlay switch session=$sessionId")
        }
    }

    /** Same op the `drop_old_screenshots` tool uses: drop all but the last screenshot. */
    fun dropScreenshotsNow() {
        val sid = currentSessionId ?: return
        scope.launch {
            repository.markScreenshotsDropped(sid, keepLast = 1)
            refreshHud()
            Log.i(TAG, "overlay dropped screenshots for session=$sid")
        }
    }

    /**
     * Local compaction: fold the older transcript into a summary note, keeping the
     * recent tail. Mirrors `compact_conversation`'s local half; the model-side
     * server compaction runs on the next request.
     */
    fun compactNow() {
        val sid = currentSessionId ?: return
        scope.launch {
            repository.summarizeAndCompact(
                sessionId = sid,
                summary = "Conversation compacted by the user from the overlay.",
                keepLast = COMPACT_KEEP_LAST,
            )
            refreshHud()
            Log.i(TAG, "overlay compacted session=$sid")
        }
    }

    // --- Typed-reply seam ---------------------------------------------------

    /** The overlay UI submits a typed reply / confirmation answer. Non-blocking. */
    fun submitReply(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        typed.tryEmit(trimmed)
    }

    /** Suspends until the next typed reply. Intended to be raced against voice. */
    suspend fun awaitTypedReply(): String = typed.first()

    private val _dictating = MutableStateFlow(false)

    /** True while a dictation capture is in flight — drives the mics' active state. */
    val dictating: StateFlow<Boolean> = _dictating.asStateFlow()

    /**
     * Dictate a reply: capture one spoken utterance and return the transcript for
     * the reply field to fill in. Goes through the shared [AudioSessionArbiter] at
     * [MicOwner.LISTEN_ONCE] so it never contends with the agent's own voice I/O.
     * Returns null on any recognition failure (no permission, no speech, etc.) so
     * the UI can simply fall back to typing.
     */
    suspend fun dictate(): String? {
        if (!_dictating.compareAndSet(expect = false, update = true)) return null
        return try {
            // Barge-in: silence any in-flight TTS immediately so the user isn't
            // talking over the agent (its say() just completes early).
            runCatching { tts.stop() }
            runCatching {
                arbiter.withMic(MicOwner.LISTEN_ONCE) { stt.transcribeOnce().text }
            }.onFailure { Log.w(TAG, "dictation failed: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        } finally {
            _dictating.value = false
        }
    }

    /**
     * Toggle-off for the mic: abort an in-flight dictation capture. The aborted
     * [SttEngine.transcribeOnce] throws inside [dictate], which returns null.
     */
    fun cancelDictation() {
        if (_dictating.value) {
            Log.i(TAG, "dictation cancelled by user")
            runCatching { stt.cancel() }
        }
    }

    // --- Internals ----------------------------------------------------------

    private suspend fun refreshHud() {
        val sid = currentSessionId ?: return
        runCatching { contextTracker.contextStatus(sid) }
            .onSuccess { hud.tryEmit(OverlayInput.Hud(it)) }
            .onFailure { Log.w(TAG, "HUD refresh failed", it) }
    }

    private fun buildState(): StateFlow<OverlayUiState> {
        val agentInputs = bus.events.map<AgentEvent, OverlayInput> { OverlayInput.Event(it) }
        val folded = merge(agentInputs, hud)
            .runningFold(OverlayUiState.INITIAL) { state, input -> reducer.reduce(state, input) }
            .throttleLatest(TEXT_THROTTLE_MS)
        // Eagerly, not WhileSubscribed: the controller is a singleton and the fold
        // must keep accumulating while the overlay window is closed, so reopening
        // the overlay shows the run's text/tool history instead of a blank panel.
        return combine(folded, expanded) { core, isExpanded -> core.copy(expanded = isExpanded) }
            .stateIn(scope, SharingStarted.Eagerly, OverlayUiState.INITIAL)
    }

    companion object {
        private const val TAG = "OverlayController"
        private const val TEXT_THROTTLE_MS = 80L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val COMPACT_KEEP_LAST = 4
    }
}

/**
 * Leading + trailing throttle: emits the first value in a window immediately, then
 * at most one (the latest) per [periodMs]. Because each upstream value is a full
 * [OverlayUiState] snapshot, coalescing a burst of text deltas to leading+trailing
 * never drops information — the trailing snapshot already contains all of it.
 * Structured-concurrency-safe: the trailing timer is a child of [channelFlow].
 */
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> {
    if (periodMs <= 0L) return this
    return channelFlow {
        val mutex = Mutex()
        var pending: T? = null
        var hasPending = false
        var windowOpen = false

        collect { value ->
            mutex.withLock {
                if (!windowOpen) {
                    windowOpen = true
                    send(value)
                    launch {
                        delay(periodMs)
                        mutex.withLock {
                            if (hasPending) {
                                @Suppress("UNCHECKED_CAST")
                                send(pending as T)
                                pending = null
                                hasPending = false
                            }
                            windowOpen = false
                        }
                    }
                } else {
                    pending = value
                    hasPending = true
                }
            }
        }
    }
}
