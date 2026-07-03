package com.assist.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

/**
 * Runtime/special-permission checks and deep-links for onboarding. Owned by
 * phase-02; consumed by the overlay/voice phases.
 *
 * The AccessibilityService class itself is created in phase-03; we reference its
 * component id by string so this helper compiles independently.
 */
object Permissions {
    /** Fully-qualified component id of the (phase-03) accessibility service. */
    const val ACCESSIBILITY_SERVICE_ID = "com.assist/com.assist.service.AssistAccessibilityService"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** True if our accessibility service is listed in the enabled-services setting. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(ACCESSIBILITY_SERVICE_ID, ignoreCase = true)) return true
        }
        return false
    }

    // --- Deep links ---------------------------------------------------------

    fun openAccessibilitySettings(context: Context) =
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addNewTaskFlag(),
        )

    fun openOverlaySettings(context: Context) =
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addNewTaskFlag(),
        )

    fun openAppDetailsSettings(context: Context) =
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addNewTaskFlag(),
        )

    private fun Intent.addNewTaskFlag(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
