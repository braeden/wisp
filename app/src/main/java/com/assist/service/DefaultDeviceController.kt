package com.assist.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * [DeviceController] backed by the live [AssistAccessibilityService]. Owns the single
 * current [ScreenFrame] (id→node map) and recycles the previous one on each new
 * perception to avoid `AccessibilityNodeInfo` leaks.
 */
class DefaultDeviceController(
    private val appContext: Context,
    private val serializer: ScreenSerializer,
    private val gestureFactory: GestureFactory,
    private val appResolver: AppResolver,
) : DeviceController {
    private val frameLock = Mutex()
    private var currentFrame: ScreenFrame = ScreenFrame.EMPTY

    private fun service(): AssistAccessibilityService? = AssistAccessibilityService.instance

    override suspend fun getScreenState(): ScreenState {
        val svc = service() ?: return ScreenState.EMPTY
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
        val frame = serializer.serialize(root?.let { AccessibilityNodeView(it) })
        frameLock.withLock {
            currentFrame.recycle()
            currentFrame = frame
        }
        return frame.state
    }

    override suspend fun takeScreenshot(): Bitmap? {
        val svc = service() ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    appContext.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bmp =
                                try {
                                    Bitmap
                                        .wrapHardwareBuffer(buffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } catch (t: Throwable) {
                                    Log.w(TAG, "screenshot decode failed", t)
                                    null
                                } finally {
                                    buffer.close()
                                }
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed: $errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    // --- Taps ---------------------------------------------------------------

    override suspend fun tap(elementId: Int): ToolOutcome {
        val node =
            frameLock.withLock { currentFrame.node(elementId) }
                ?: return ToolOutcome.fail("No element #$elementId in the current screen")
        if (node.isClickable && node.performClick()) {
            return ToolOutcome.ok("Tapped #$elementId (${node.className.orRole()})")
        }
        val c = node.bounds
        if (c.isEmpty) return ToolOutcome.fail("Element #$elementId has no tappable bounds")
        return if (dispatch(gestureFactory.tap(c.centerX, c.centerY))) {
            ToolOutcome.ok("Tapped #$elementId at (${c.centerX},${c.centerY})")
        } else {
            ToolOutcome.fail("Tap gesture on #$elementId was not dispatched")
        }
    }

    override suspend fun tapXy(
        x: Int,
        y: Int,
    ): ToolOutcome =
        if (dispatch(gestureFactory.tap(x, y))) {
            ToolOutcome.ok("Tapped ($x,$y)")
        } else {
            ToolOutcome.fail("Tap gesture at ($x,$y) failed")
        }

    override suspend fun longPress(elementId: Int): ToolOutcome {
        val node =
            frameLock.withLock { currentFrame.node(elementId) }
                ?: return ToolOutcome.fail("No element #$elementId in the current screen")
        if (node.isLongClickable && node.performLongClick()) {
            return ToolOutcome.ok("Long-pressed #$elementId")
        }
        val c = node.bounds
        if (c.isEmpty) return ToolOutcome.fail("Element #$elementId has no bounds")
        return if (dispatch(gestureFactory.longPress(c.centerX, c.centerY))) {
            ToolOutcome.ok("Long-pressed #$elementId at (${c.centerX},${c.centerY})")
        } else {
            ToolOutcome.fail("Long-press gesture on #$elementId failed")
        }
    }

    override suspend fun longPressXy(
        x: Int,
        y: Int,
    ): ToolOutcome =
        if (dispatch(gestureFactory.longPress(x, y))) {
            ToolOutcome.ok("Long-pressed ($x,$y)")
        } else {
            ToolOutcome.fail("Long-press gesture at ($x,$y) failed")
        }

    // --- Swipe / scroll -----------------------------------------------------

    override suspend fun swipe(
        direction: SwipeDirection,
        fraction: Double,
    ): ToolOutcome {
        val segment = Gestures.swipe(direction, screenBounds(), fraction)
        return if (dispatch(gestureFactory.swipe(segment))) {
            ToolOutcome.ok("Swiped $direction")
        } else {
            ToolOutcome.fail("Swipe $direction failed")
        }
    }

    override suspend fun swipeXy(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): ToolOutcome {
        val segment = Gestures.Segment(Gestures.Point(x1, y1), Gestures.Point(x2, y2))
        return if (dispatch(gestureFactory.swipe(segment, durationMs))) {
            ToolOutcome.ok("Swiped ($x1,$y1)->($x2,$y2)")
        } else {
            ToolOutcome.fail("Swipe ($x1,$y1)->($x2,$y2) failed")
        }
    }

    override suspend fun scroll(
        elementId: Int,
        forward: Boolean,
    ): ToolOutcome {
        val node =
            frameLock.withLock { currentFrame.node(elementId) }
                ?: return ToolOutcome.fail("No element #$elementId in the current screen")
        val ok = if (forward) node.performScrollForward() else node.performScrollBackward()
        return if (ok) {
            ToolOutcome.ok("Scrolled #$elementId ${if (forward) "forward" else "back"}")
        } else {
            ToolOutcome.fail(
                "Element #$elementId did not scroll (${if (forward) "forward" else "back"})",
            )
        }
    }

    override suspend fun scroll(direction: SwipeDirection): ToolOutcome {
        // Content scrolls opposite to the finger: to scroll content down, swipe up.
        val fingerDir =
            when (direction) {
                SwipeDirection.DOWN -> SwipeDirection.UP
                SwipeDirection.UP -> SwipeDirection.DOWN
                SwipeDirection.LEFT -> SwipeDirection.RIGHT
                SwipeDirection.RIGHT -> SwipeDirection.LEFT
            }
        val segment = Gestures.swipe(fingerDir, screenBounds(), fraction = 0.6)
        return if (dispatch(gestureFactory.swipe(segment))) {
            ToolOutcome.ok("Scrolled $direction")
        } else {
            ToolOutcome.fail("Scroll $direction failed")
        }
    }

    // --- Text / keys --------------------------------------------------------

    override suspend fun setText(
        elementId: Int,
        text: String,
    ): ToolOutcome {
        val node =
            frameLock.withLock { currentFrame.node(elementId) }
                ?: return ToolOutcome.fail("No element #$elementId in the current screen")
        if (!node.isEditable) {
            return ToolOutcome.fail(
                "Element #$elementId is not an editable field",
            )
        }
        val ok = node.performSetText(text)
        val passwordNote = if (node.isPassword) " (password field — gate before use)" else ""
        return if (ok) {
            ToolOutcome.ok("Set text on #$elementId$passwordNote")
        } else {
            ToolOutcome.fail("Failed to set text on #$elementId")
        }
    }

    override suspend fun pressKey(key: DeviceKey): ToolOutcome {
        if (key == DeviceKey.ENTER) {
            val node =
                frameLock.withLock {
                    val focusedId =
                        currentFrame.state.elements
                            .firstOrNull { it.focused && it.editable }
                            ?.id
                    focusedId?.let { currentFrame.node(it) }
                }
            return if (node != null && node.performImeEnter()) {
                ToolOutcome.ok("Pressed ENTER")
            } else {
                ToolOutcome.fail("No focused editable field to send ENTER to")
            }
        }
        val svc = service() ?: return ToolOutcome.fail("Accessibility service not connected")
        val action =
            when (key) {
                DeviceKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                DeviceKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                DeviceKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                DeviceKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                DeviceKey.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                DeviceKey.ENTER -> return ToolOutcome.fail("unreachable")
            }
        return if (svc.performGlobalAction(action)) {
            ToolOutcome.ok("Pressed $key")
        } else {
            ToolOutcome.fail("Global action $key failed")
        }
    }

    // --- App launch ---------------------------------------------------------

    override suspend fun openApp(packageOrLabel: String): ToolOutcome =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            @Suppress("DEPRECATION")
            val apps =
                pm
                    .queryIntentActivities(query, 0)
                    .map {
                        InstalledApp(
                            packageName = it.activityInfo.packageName,
                            label = it.loadLabel(pm).toString(),
                        )
                    }.distinctBy { it.packageName }
            val pkg =
                appResolver.resolve(packageOrLabel, apps)
                    ?: return@withContext ToolOutcome.fail("No app matched \"$packageOrLabel\"")
            val launch =
                pm.getLaunchIntentForPackage(pkg)
                    ?: return@withContext ToolOutcome.fail("No launch intent for $pkg")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return@withContext try {
                appContext.startActivity(launch)
                ToolOutcome.ok("Opened $pkg")
            } catch (t: Throwable) {
                ToolOutcome.fail("Failed to launch $pkg: ${t.message}")
            }
        }

    override suspend fun wait(ms: Long): ToolOutcome {
        val clamped = ms.coerceIn(0, MAX_WAIT_MS)
        delay(clamped)
        return ToolOutcome.ok("Waited ${clamped}ms")
    }

    // --- Internals ----------------------------------------------------------

    private suspend fun dispatch(
        gesture: android.accessibilityservice.GestureDescription,
    ): Boolean {
        val svc = service() ?: return false
        return suspendCancellableCoroutine { cont ->
            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            val dispatched =
                runCatching {
                    svc.dispatchGesture(
                        gesture,
                        callback,
                        null,
                    )
                }.getOrDefault(false)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    private fun screenBounds(): Bounds {
        val dm = appContext.resources.displayMetrics
        return Bounds(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun String?.orRole(): String = this?.substringAfterLast('.') ?: "view"

    companion object {
        private const val TAG = "DeviceController"
        private const val MAX_WAIT_MS = 30_000L
    }
}
