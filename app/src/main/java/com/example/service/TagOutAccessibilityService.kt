package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.blocker.BlockManager
import com.example.data.db.TagOutDatabase
import com.example.ui.blocked.BlockedAppActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TagOutAccessibilityService
 *
 * Implements lightweight, real-time foreground window monitoring and auto-closing.
 * Whenever a blocked app is running or opened, this service intercepts the event,
 * verifies confirmed blocking against the live current state first, and only draws
 * the block screen if confirmed blocked at that exact moment.
 */
class TagOutAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastBlockedPackage: String? = null
    private var lastBlockedTimestamp: Long = 0L

    @Volatile
    private var currentBlockedApps: Map<String, String> = emptyMap()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        currentInstance = this

        // Observe the blocked apps list StateFlow in real time
        serviceScope.launch {
            BlockManager.blockedAppsFlow.collect { blockedMap ->
                currentBlockedApps = blockedMap
            }
        }

        // Check immediately in case blocked apps are already in the foreground
        checkAndCloseIfBlocked()
    }

    override fun onDestroy() {
        isRunning = false
        if (currentInstance === this) {
            currentInstance = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Final fix — TagOut's own package name must be checked at the very top of the accessibility event handler,
        // before any database read or state check, and immediately returned if it matches.
        // This is a hardcoded guard, not a database lookup.
        if (isTagOutOwnPackage(packageName)) {
            return
        }

        // System UI and Android core package guards
        if (packageName == "com.android.systemui" || packageName == "android") return

        currentForegroundPackage = packageName

        closeIfPackageBlocked(packageName)
    }

    private fun isTagOutOwnPackage(pkg: String): Boolean {
        return pkg == applicationContext.packageName ||
                pkg == "com.aistudio.tagout.kfpwnv" ||
                pkg.startsWith("com.aistudio.tagout") ||
                pkg == "com.example"
    }

    private fun closeIfPackageBlocked(packageName: String) {
        // Fast real-time filter: if not in the observed StateFlow, definitely not blocked
        if (!currentBlockedApps.containsKey(packageName)) {
            return
        }

        // First fix: Check current active profile state first from the live current state,
        // and only draw the block screen if the app is confirmed blocked at that exact moment.
        // Not from a cached value — from the live current state.
        serviceScope.launch(Dispatchers.IO) {
            val db = TagOutDatabase.getInstance(applicationContext)
            val liveActiveProfiles = db.blockingProfileDao().getActiveProfilesSync()

            // Keep BlockManager StateFlow in sync with live state immediately
            BlockManager.updateActiveProfilesSync(liveActiveProfiles)

            // Confirm whether this package is actively blocked at this exact moment
            val confirmedProfile = liveActiveProfiles.firstOrNull { profile ->
                profile.isActive && profile.blockedPackages.contains(packageName)
            } ?: return@launch // NOT confirmed blocked in live state -> do NOT draw block screen!

            val profileName = confirmedProfile.name

            // Confirmed blocked from live current state! Only now draw the block screen
            withContext(Dispatchers.Main) {
                val now = System.currentTimeMillis()
                if (packageName == lastBlockedPackage && (now - lastBlockedTimestamp) < 800L) {
                    // Debounce rapid repeated window events for the same blocked app
                    return@withContext
                }
                lastBlockedPackage = packageName
                lastBlockedTimestamp = now

                // Immediately send user home to remove distraction
                performGlobalAction(GLOBAL_ACTION_HOME)

                // Show minimal TagOut blocked focus screen
                val intent = Intent(this@TagOutAccessibilityService, BlockedAppActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(BlockedAppActivity.EXTRA_BLOCKED_PACKAGE, packageName)
                    putExtra(BlockedAppActivity.EXTRA_PROFILE_NAME, profileName)
                }
                startActivity(intent)
            }
        }
    }

    fun closeIfForegroundAppBlocked() {
        val activePkg = currentForegroundPackage
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return

        if (isTagOutOwnPackage(activePkg) ||
            activePkg == "com.android.systemui" ||
            activePkg == "android"
        ) {
            return
        }

        closeIfPackageBlocked(activePkg)
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentInstance: TagOutAccessibilityService? = null
            private set

        @Volatile
        var currentForegroundPackage: String? = null
            private set

        /**
         * Triggers an immediate foreground check to auto-close any blocked app
         * currently running on profile activation.
         */
        fun checkAndCloseIfBlocked() {
            currentInstance?.closeIfForegroundAppBlocked()
        }
    }
}
