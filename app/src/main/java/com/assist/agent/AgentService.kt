package com.assist.agent

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
import com.assist.BuildConfig
import com.assist.R
import com.assist.data.SecretStore
import com.assist.data.SessionRepository
import com.assist.data.SettingsStore
import com.assist.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service hosting the [AgentLoop] so a task survives backgrounding
 * while driving other apps. Phase-06 keeps it minimal: it starts a run from an
 * [ACTION_RUN] intent (typically via [DebugRunReceiver]) and can [ACTION_INTERRUPT]
 * one. In phase-07 this coordinates the single FGS with the overlay.
 */
@AndroidEntryPoint
class AgentService : Service() {
    @Inject lateinit var agentLoop: AgentLoop

    @Inject lateinit var repository: SessionRepository

    @Inject lateinit var secretStore: SecretStore

    @Inject lateinit var settings: SettingsStore

    @Inject @AppScope
    lateinit var scope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundInternal("Assist agent ready")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_INTERRUPT -> {
                agentLoop.interrupt()
                stopSelfSoon()
            }
            else -> {
                val userIntent = intent?.getStringExtra(EXTRA_INTENT)?.trim().orEmpty()
                if (userIntent.isEmpty()) {
                    Log.w(TAG, "no '$EXTRA_INTENT' extra; ignoring start")
                    stopSelfSoon()
                } else {
                    val sessionId =
                        intent
                            ?.getLongExtra(EXTRA_SESSION_ID, NO_SESSION)
                            ?.takeIf { it > 0 }
                    runIntent(userIntent, sessionId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runIntent(
        userIntent: String,
        resumeSessionId: Long? = null,
    ) {
        seedApiKeyFromBuildConfig()
        startForegroundInternal("Running: ${userIntent.take(60)}")
        scope.launch {
            // Continue an existing session when asked (overlay session switch /
            // follow-up messages); otherwise every run starts a fresh session.
            val session =
                resumeSessionId?.let { repository.resumeSession(it) }
                    ?: repository.createSession(
                        title = userIntent.take(80),
                        model = settings.getAgentModel().modelId,
                    )
            Log.i(
                TAG,
                "run session=${session.id} resumed=${resumeSessionId != null} intent=\"$userIntent\"",
            )
            runCatching { agentLoop.start(session.id, userIntent).join() }
                .onFailure { Log.e(TAG, "run failed", it) }
            Log.i(TAG, "run complete for session=${session.id}")
            stopSelfSoon()
        }
    }

    /**
     * For headless debug runs the API key is baked into [BuildConfig] at build
     * time; seed it into the [SecretStore] if the user hasn't stored one via the
     * onboarding UI. No-op in normal use.
     */
    private fun seedApiKeyFromBuildConfig() {
        if (secretStore.getApiKey().isNullOrBlank() && BuildConfig.ANTHROPIC_API_KEY.isNotBlank()) {
            secretStore.setApiKey(BuildConfig.ANTHROPIC_API_KEY)
            Log.i(TAG, "seeded API key from BuildConfig for debug run")
        }
    }

    private fun stopSelfSoon() {
        if (!agentLoop.isRunning) stopSelf()
    }

    private fun startForegroundInternal(text: String) {
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Assist agent", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "assist_agent"
        private const val NOTIFICATION_ID = 42

        const val ACTION_RUN = "com.assist.action.AGENT_RUN"
        const val ACTION_INTERRUPT = "com.assist.action.AGENT_INTERRUPT"
        const val EXTRA_INTENT = "intent"
        const val EXTRA_SESSION_ID = "session_id"
        private const val NO_SESSION = -1L

        /**
         * Build a start intent carrying [userIntent]. Pass [sessionId] to continue
         * an existing session instead of creating a new one.
         */
        fun runIntent(
            context: Context,
            userIntent: String,
            sessionId: Long? = null,
        ): Intent =
            Intent(context, AgentService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_INTENT, userIntent)
                .apply { sessionId?.let { putExtra(EXTRA_SESSION_ID, it) } }
    }
}
