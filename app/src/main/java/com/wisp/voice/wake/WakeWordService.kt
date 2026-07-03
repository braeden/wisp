package com.wisp.voice.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wisp.R
import com.wisp.data.SettingsStore
import com.wisp.overlay.OverlayService
import com.wisp.voice.WakeConfig
import com.wisp.voice.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase-09 always-on wake-word foreground service (`microphone` FGS type, so
 * the OS shows the persistent mic indicator while armed). Collects
 * [WakeWordDetector.detections] for the configured keyword; a detection opens
 * the overlay already listening ([OverlayService.startListening] — the same
 * voice-first path as "Start a task"). The keyword setting is collected
 * `latest`, so changing it in Settings re-arms without a service restart.
 *
 * Toggled from Settings (start requires the app in the foreground — an
 * Android 14+ rule for microphone FGS). Mirrors [OverlayService]'s
 * [running] pattern so the toggle reflects true service state.
 */
@AndroidEntryPoint
class WakeWordService : Service() {
    @Inject lateinit var detector: WakeWordDetector

    @Inject lateinit var settings: SettingsStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundInternal()
        _running.value = true
        listenJob =
            serviceScope.launch {
                settings.wakeKeyword.collectLatest { keyword ->
                    runCatching {
                        detector.detections(WakeConfig(keyword = keyword)).collect {
                            Log.i(TAG, "wake word detected: ${it.keyword}")
                            OverlayService.startListening(this@WakeWordService, newSession = false)
                        }
                    }.onFailure { t ->
                        Log.e(TAG, "wake word unavailable, stopping", t)
                        stopSelf()
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listenJob?.cancel()
        serviceScope.cancel()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundInternal() {
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.wake_notification_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wisp wake word",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wisp_wake"
        private const val NOTIFICATION_ID = 44

        const val ACTION_STOP = "com.wisp.action.WAKE_STOP"

        private val _running = MutableStateFlow(false)

        /** True while the service is armed (drives the Settings toggle). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
