package com.assist.service

import kotlinx.serialization.Serializable

/**
 * Screen-space rectangle (pixels), left/top inclusive, right/bottom exclusive —
 * mirrors [android.graphics.Rect] but is framework-free so serialization and
 * geometry can be unit-tested on the JVM without Robolectric.
 */
@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Horizontal midpoint. */
    val centerX: Int get() = left + width / 2

    /** Vertical midpoint. */
    val centerY: Int get() = top + height / 2

    /** True when the rectangle has no area (zero/negative width or height). */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in left until right && y in top until bottom

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)
    }
}
