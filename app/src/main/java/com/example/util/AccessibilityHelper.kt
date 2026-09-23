package com.example.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.example.service.TagOutAccessibilityService

object AccessibilityHelper {

    /**
     * Checks if TagOutAccessibilityService is enabled in Android settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (TagOutAccessibilityService.isRunning) {
            return true
        }

        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledServices != null) {
                for (service in enabledServices) {
                    val info = service.resolveInfo?.serviceInfo
                    if (info != null && info.packageName == context.packageName &&
                        (info.name == TagOutAccessibilityService::class.java.name ||
                         info.name.endsWith("TagOutAccessibilityService"))
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            val expectedCanonical = "${context.packageName}/${TagOutAccessibilityService::class.java.canonicalName}"
            val expectedSimple = "${context.packageName}/${TagOutAccessibilityService::class.java.name}"

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                if (componentNameString.equals(expectedCanonical, ignoreCase = true) ||
                    componentNameString.equals(expectedSimple, ignoreCase = true) ||
                    (componentNameString.startsWith(context.packageName) &&
                     componentNameString.contains("TagOutAccessibilityService"))
                ) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Deep links directly to Android Accessibility Settings.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
