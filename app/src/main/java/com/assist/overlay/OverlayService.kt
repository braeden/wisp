package com.assist.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.assist.R
import com.assist.agent.AgentService
import com.assist.ui.theme.AssistTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts the Compose overlay in a `WindowManager` window
 * above whatever app the agent drives.
 *
 * The window is `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` +
 * `FLAG_NOT_TOUCH_MODAL`, so it draws on top but the driven app still receives all
 * touch/key input outside the overlay's own bounds. Focusability is toggled on
 * only while capturing a typed reply (soft keyboard), then off again.
 *
 * **FGS coordination (integration point):** this ships as its own `specialUse`
 * FGS for phase-07, mirroring phase-06's `AgentService`. They are intended to be
 * unified into a single coordinated FGS + notification later — see the report.
 * Nothing here touches `AgentService`.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var controller: OverlayController

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundInternal()
        addOverlay()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        removeOverlay()
        super.onDestroy()
    }

    // --- Window management --------------------------------------------------

    private fun addOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        params = layoutParams

        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onResume()
        }
        lifecycleOwner = owner

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                AssistTheme {
                    val uiScope = rememberCoroutineScope()
                    val state by controller.uiState.collectAsState()
                    val sessions by controller.sessions.collectAsState()
                    val dictating by controller.dictating.collectAsState()
                    OverlayRoot(
                        state = state,
                        sessions = sessions,
                        dictating = dictating,
                        onToggleExpanded = controller::toggleExpanded,
                        onDrag = ::moveBy,
                        onInterrupt = controller::interrupt,
                        onRecordNewMessage = {
                            // Collapsed "record": dictate a fresh instruction and
                            // run it as a new task (supersedes any in-flight run).
                            uiScope.launch {
                                val text = controller.dictate()
                                if (!text.isNullOrBlank()) {
                                    ContextCompat.startForegroundService(
                                        this@OverlayService,
                                        AgentService.runIntent(this@OverlayService, text),
                                    )
                                }
                            }
                        },
                        onNewSession = controller::newSession,
                        onSwitchSession = controller::switchSession,
                        onSubmitReply = { text ->
                            controller.submitReply(text)
                            setFocusable(false)
                        },
                        onDictate = controller::dictate,
                        onSetFocusable = ::setFocusable,
                        onStop = { stopSelf() },
                    )
                }
            }
        }
        composeView = view

        // Re-clamp whenever the content resizes (e.g. expand/collapse) so a panel
        // that grows near an edge is pulled back on-screen instead of stranding its
        // controls off the display.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampToScreen() }

        runCatching { wm.addView(view, layoutParams) }
            .onFailure { Log.e(TAG, "addView failed (overlay permission missing?)", it) }
    }

    private fun removeOverlay() {
        composeView?.let { view -> runCatching { windowManager?.removeView(view) } }
        lifecycleOwner?.onDestroy()
        composeView = null
        lifecycleOwner = null
        windowManager = null
        params = null
    }

    /** Drag handler: shift the window by [delta] px, then clamp it on-screen. */
    private fun moveBy(delta: Offset) {
        val p = params ?: return
        val view = composeView ?: return
        p.x += delta.x.toInt()
        p.y += delta.y.toInt()
        runCatching { windowManager?.updateViewLayout(view, p) }
        clampToScreen()
    }

    /**
     * Keep the window's top-left within the display so it can never be dragged —
     * or grown, on expand — fully off-screen (which would strand its controls).
     * Re-invoked on every layout change (see [addOverlay]).
     */
    private fun clampToScreen() {
        val p = params ?: return
        val view = composeView ?: return
        val wm = windowManager ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val maxX = (bounds.width() - view.width).coerceAtLeast(0)
        val maxY = (bounds.height() - view.height).coerceAtLeast(0)
        val nx = p.x.coerceIn(0, maxX)
        val ny = p.y.coerceIn(0, maxY)
        if (nx != p.x || ny != p.y) {
            p.x = nx
            p.y = ny
            runCatching { wm.updateViewLayout(view, p) }
        }
    }

    /**
     * Toggle window focusability. Non-focusable is the default (so the driven app
     * keeps input); focusable + a visible soft keyboard is set only to capture a
     * typed reply, then reverted.
     */
    private fun setFocusable(focusable: Boolean) {
        val p = params ?: return
        val view = composeView ?: return
        if (focusable) {
            p.flags = BASE_FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            p.flags = BASE_FLAGS
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        runCatching { windowManager?.updateViewLayout(view, p) }
    }

    // --- Foreground notification -------------------------------------------

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Assist overlay", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "assist_overlay"
        private const val NOTIFICATION_ID = 43

        const val ACTION_STOP = "com.assist.action.OVERLAY_STOP"

        private val _running = MutableStateFlow(false)

        /** Whether the overlay window is currently shown. Drives the Settings toggle. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** Start the overlay as a foreground service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        /** Stop the overlay. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
