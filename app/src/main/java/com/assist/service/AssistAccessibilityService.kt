package com.assist.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.assist.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single [AccessibilityService] that perceives the screen and performs gestures.
 * Holds a process-wide [instance] so [DeviceController] can reach it. Emits UI-change
 * signals on [ScreenChangeSignals] and (for phase-03 verification) registers a debug
 * broadcast receiver that dumps/acts on the screen.
 */
@AndroidEntryPoint
class AssistAccessibilityService : AccessibilityService() {
    @Inject lateinit var screenChangeSignals: ScreenChangeSignals

    @Inject lateinit var deviceController: DeviceController

    @Inject @AppScope
    lateinit var scope: CoroutineScope

    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerDebugReceiver()
        Log.i(TAG, "AssistAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            screenChangeSignals.signal()
        }
    }

    override fun onInterrupt() {
        // No long-running feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
    }

    // --- Debug verification hook (phase-03) ---------------------------------

    private fun registerDebugReceiver() {
        if (debugReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action != ACTION_DEBUG_DUMP) return
                    scope.launch { handleDebug(intent) }
                }
            }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_DEBUG_DUMP),
            ContextCompat.RECEIVER_EXPORTED,
        )
        debugReceiver = receiver
    }

    private suspend fun handleDebug(intent: Intent) {
        intent
            .getStringExtra(
                "open",
            )?.let { Log.i(TAG, "debug open: ${deviceController.openApp(it)}") }
        if (intent.hasExtra("tap")) {
            Log.i(TAG, "debug tap: ${deviceController.tap(intent.getIntExtra("tap", -1))}")
        }
        intent.getStringExtra("swipe")?.let { dir ->
            SwipeDirection
                .fromString(
                    dir,
                )?.let { Log.i(TAG, "debug swipe: ${deviceController.swipe(it)}") }
        }
        intent.getStringExtra("key")?.let { k ->
            DeviceKey
                .fromString(
                    k,
                )?.let { Log.i(TAG, "debug key: ${deviceController.pressKey(it)}") }
        }
        if (intent.getBooleanExtra("screenshot", false)) {
            val bmp = deviceController.takeScreenshot()
            Log.i(
                TAG,
                "debug screenshot: non-null=${bmp != null} size=${bmp?.width}x${bmp?.height}",
            )
        }
        val state = deviceController.getScreenState()
        Log.i(TAG, "==== SCREEN DUMP ====\n${state.toOutline()}")
    }

    companion object {
        private const val TAG = "AssistA11yService"
        const val ACTION_DEBUG_DUMP = "com.assist.DEBUG_DUMP_SCREEN"

        /** Process-wide handle to the connected service, or null when disabled. */
        @Volatile
        var instance: AssistAccessibilityService? = null
            private set
    }
}
