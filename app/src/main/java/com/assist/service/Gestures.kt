package com.assist.service

/**
 * Framework-free gesture geometry. Turns high-level intents (a direction + an area)
 * into concrete start/end points so the math is unit-testable; [GestureFactory]
 * consumes these to build `GestureDescription`s.
 */
object Gestures {
    data class Point(
        val x: Int,
        val y: Int,
    )

    data class Segment(
        val start: Point,
        val end: Point,
    )

    /**
     * A swipe within [area], covering [fraction] of the relevant dimension, centred
     * on the area. [direction] is the finger's travel direction.
     */
    fun swipe(
        direction: SwipeDirection,
        area: Bounds,
        fraction: Double = 0.6,
    ): Segment {
        val f = fraction.coerceIn(0.1, 0.95)
        val cx = area.centerX
        val cy = area.centerY
        val halfH = (area.height * f / 2).toInt()
        val halfW = (area.width * f / 2).toInt()
        return when (direction) {
            // Finger moves up => start low, end high.
            SwipeDirection.UP -> Segment(Point(cx, cy + halfH), Point(cx, cy - halfH))
            SwipeDirection.DOWN -> Segment(Point(cx, cy - halfH), Point(cx, cy + halfH))
            SwipeDirection.LEFT -> Segment(Point(cx + halfW, cy), Point(cx - halfW, cy))
            SwipeDirection.RIGHT -> Segment(Point(cx - halfW, cy), Point(cx + halfW, cy))
        }
    }

    /** Tap point = centre of an element's bounds. */
    fun center(bounds: Bounds): Point = Point(bounds.centerX, bounds.centerY)
}
