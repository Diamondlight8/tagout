package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * BatteryOptimizationHelper
 *
 * Provides utilities to check and request exemption from Android battery optimizations (Doze mode,
 * app standby, and OEM background app killers).
 *
 * Exempting TagOut ensures:
 * 1. TagOutAccessibilityService is not terminated in the background by aggressive battery savers.
 * 2. Background app launches are immediately intercepted and blocked.
 * 3. NFC hardware dispatch and intent handling remain snappy and responsive.
 */
object BatteryOptimizationHelper {

    /**
     * Checks if TagOut is currently exempt from battery optimizations.
     * Returns true if battery optimization is disabled for this app, false if optimized.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Launches the system intent prompting the user to disable battery optimization for TagOut.
     *
     * Flow:
     * 1. Attempts direct system dialog prompt (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).
     * 2. Falls back to system Battery Optimization Settings list (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).
     * 3. Falls back to Application Details Settings where battery settings can be configured manually.
     */
    fun promptDisableBatteryOptimizations(context: Context) {
        val packageName = context.packageName

        // 1. Primary: Direct system prompt dialog for this package
        try {
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(requestIntent)
            return
        } catch (_: Exception) {}

        // 2. Secondary fallback: General battery optimization list
        try {
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            return
        } catch (_: Exception) {}

        // 3. Tertiary fallback: App info settings page
        try {
            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(appDetailsIntent)
        } catch (_: Exception) {}
    }
}
