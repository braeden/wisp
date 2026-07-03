package com.assist.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bus that fires on relevant accessibility events (window state /
 * content changed). The agent loop awaits [events] after an action to know when the
 * UI has settled and it should re-perceive. Emissions are conflated-ish: the buffer
 * drops the oldest under backpressure so a slow collector never blocks the service.
 */
@Singleton
class ScreenChangeSignals
    @Inject
    constructor() {
        private val _events =
            MutableSharedFlow<Unit>(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** Fires (Unit) each time the foreground UI meaningfully changes. */
        val events: SharedFlow<Unit> = _events.asSharedFlow()

        /** Called by the service from `onAccessibilityEvent`. Never blocks. */
        fun signal() {
            _events.tryEmit(Unit)
        }
    }
