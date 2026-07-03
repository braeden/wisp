package com.assist.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Debug entry point (phase-06 acceptance): a manifest-registered receiver so the
 * run can be triggered even when the app process isn't up.
 *
 * ```
 * adb shell am broadcast -a com.assist.DEBUG_RUN --es intent "open the Clock app and start a 1 minute timer"
 * adb shell am broadcast -a com.assist.DEBUG_INTERRUPT
 * ```
 *
 * Starts [AgentService] (a foreground service) which creates a session and runs
 * the [AgentLoop]. Debug-only convenience; not part of the shipped surface.
 */
class DebugRunReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_DEBUG_RUN -> {
                val userIntent = intent.getStringExtra(AgentService.EXTRA_INTENT)?.trim().orEmpty()
                if (userIntent.isEmpty()) {
                    Log.w(TAG, "DEBUG_RUN missing --es intent \"...\"")
                    return
                }
                Log.i(TAG, "DEBUG_RUN -> \"$userIntent\"")
                ContextCompat.startForegroundService(
                    context,
                    AgentService.runIntent(context, userIntent),
                )
            }
            ACTION_DEBUG_INTERRUPT -> {
                Log.i(TAG, "DEBUG_INTERRUPT")
                ContextCompat.startForegroundService(
                    context,
                    Intent(
                        context,
                        AgentService::class.java,
                    ).setAction(AgentService.ACTION_INTERRUPT),
                )
            }
        }
    }

    companion object {
        private const val TAG = "DebugRunReceiver"
        const val ACTION_DEBUG_RUN = "com.assist.DEBUG_RUN"
        const val ACTION_DEBUG_INTERRUPT = "com.assist.DEBUG_INTERRUPT"
    }
}
