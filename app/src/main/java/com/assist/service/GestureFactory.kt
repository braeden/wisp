package com.assist.service

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Builds [GestureDescription]s from screen coordinates for dispatch via
 * `AccessibilityService.dispatchGesture`. Coordinate math is delegated to the pure
 * [Gestures] helper; this class only assembles framework `Path`/`StrokeDescription`.
 */
class GestureFactory {
    fun tap(
        x: Int,
        y: Int,
        durationMs: Long = TAP_MS,
    ): GestureDescription = stroke(x, y, x, y, 0, durationMs)

    fun longPress(
        x: Int,
        y: Int,
        durationMs: Long = LONG_PRESS_MS,
    ): GestureDescription = stroke(x, y, x, y, 0, durationMs)

    fun swipe(
        segment: Gestures.Segment,
        durationMs: Long = SWIPE_MS,
    ): GestureDescription =
        stroke(segment.start.x, segment.start.y, segment.end.x, segment.end.y, 0, durationMs)

    private fun stroke(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        startTime: Long,
        duration: Long,
    ): GestureDescription {
        val path =
            Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
            }
        return GestureDescription
            .Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, startTime, duration.coerceAtLeast(1)),
            ).build()
    }

    companion object {
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 600L
        const val SWIPE_MS = 300L
    }
}
