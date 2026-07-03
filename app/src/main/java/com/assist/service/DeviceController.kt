package com.assist.service

import android.graphics.Bitmap

/**
 * The concrete action surface the agent's ToolRouter (phase-06) drives. Every method
 * is `suspend` and either returns a [ToolOutcome] or the requested perception
 * payload. Backed by the live [AssistAccessibilityService]; when the service is not
 * connected, actions fail gracefully with a descriptive [ToolOutcome].
 */
interface DeviceController {
    /** Serialize the foreground window and refresh the live id→node frame. */
    suspend fun getScreenState(): ScreenState

    /** PNG-capable bitmap of the current display, or null on failure/rate-limit. */
    suspend fun takeScreenshot(): Bitmap?

    suspend fun tap(elementId: Int): ToolOutcome

    suspend fun tapXy(
        x: Int,
        y: Int,
    ): ToolOutcome

    suspend fun longPress(elementId: Int): ToolOutcome

    suspend fun longPressXy(
        x: Int,
        y: Int,
    ): ToolOutcome

    suspend fun swipe(
        direction: SwipeDirection,
        fraction: Double = 0.6,
    ): ToolOutcome

    suspend fun swipeXy(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = GestureFactory.SWIPE_MS,
    ): ToolOutcome

    suspend fun scroll(
        elementId: Int,
        forward: Boolean = true,
    ): ToolOutcome

    suspend fun scroll(direction: SwipeDirection): ToolOutcome

    /** Focus the node and set its text. Password fields are flagged in the outcome. */
    suspend fun setText(
        elementId: Int,
        text: String,
    ): ToolOutcome

    suspend fun pressKey(key: DeviceKey): ToolOutcome

    /** Resolve a package id or human label and launch it. */
    suspend fun openApp(packageOrLabel: String): ToolOutcome

    suspend fun wait(ms: Long): ToolOutcome
}
