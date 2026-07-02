package com.assist.agent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide hot stream of [AgentEvent]s. The agent loop publishes; the overlay
 * (07) and voice (08) collect. [emit] is non-suspending ([tryEmit]) so the loop
 * can publish even while being cancelled (interrupt path) without blocking on a
 * slow collector — the buffer drops the oldest under backpressure.
 */
@Singleton
class AgentEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Publish [event]. Never blocks; returns false only if the buffer is saturated. */
    fun emit(event: AgentEvent): Boolean = _events.tryEmit(event)
}
