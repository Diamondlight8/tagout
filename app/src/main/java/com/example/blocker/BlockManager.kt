package com.example.blocker

import com.example.data.model.BlockingProfile
import com.example.service.TagOutAccessibilityService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ARCHITECTURAL TRADEOFF ANALYSIS:
 * Why TagOut uses AccessibilityService instead of DevicePolicyManager for app blocking:
 *
 * 1. DevicePolicyManager (DPM):
 *    - Mechanism: Uses `setPackagesSuspended()` or `setApplicationHidden()` to lock apps at the OS layer.
 *    - Tradeoff / Major Drawback: Requires the application to be provisioned as Device Owner
 *      (DPO). Setting Device Owner on unrooted Android devices requires wiping the phone (factory reset)
 *      or running specialized ADB commands (`adb shell dpm set-device-owner ...`) over USB from a computer.
 *      For a personal, intentional focus app used by general consumers, this is impractical and introduces
 *      extreme setup friction.
 *
 * 2. AccessibilityService (Selected Implementation):
 *    - Mechanism: Receives real-time window state transitions (`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`).
 *    - Tradeoff / Advantages:
 *      * Accessible to everyday users: Can be enabled directly through standard Android Settings > Accessibility.
 *      * Immediate reactive blocking: Intercepts the foreground package immediately when a distracting app
 *        is launched and redirects the user away to a calm TagOut focus screen.
 *      * Preserves system stability and does not lock user out of essential OS functions.
 *      * Fully respects user consent and can be toggled without developer tools.
 */
object BlockManager {

    // Observable StateFlow of blocked apps mapping: packageName -> profileName
    // Observed in real time by TagOutAccessibilityService
    private val _blockedAppsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val blockedAppsFlow: StateFlow<Map<String, String>> = _blockedAppsFlow.asStateFlow()

    // Observable active block count
    private val _activeBlockedPackagesCount = MutableStateFlow(0)
    val activeBlockedPackagesCount: StateFlow<Int> = _activeBlockedPackagesCount.asStateFlow()

    // Profile ID to profile name mapping for fast synchronous profile matching
    private val profileIdToNameMap = ConcurrentHashMap<Long, String>()

    /**
     * Updates the in-memory blocked packages cache and StateFlow from the list of currently active profiles.
     * Also immediately triggers an auto-close check for currently foregrounded apps.
     */
    fun updateActiveProfiles(activeProfiles: List<BlockingProfile>) {
        updateActiveProfilesSync(activeProfiles)

        val hasActive = activeProfiles.any { it.isActive }
        if (hasActive) {
            TagOutAccessibilityService.checkAndCloseIfBlocked()
        }
    }

    /**
     * Synchronously sets the blocked apps StateFlow from active profiles.
     * Guaranteed to update _blockedAppsFlow immediately on the caller thread before returning.
     */
    fun updateActiveProfilesSync(activeProfiles: List<BlockingProfile>) {
        val newMap = mutableMapOf<String, String>()
        profileIdToNameMap.clear()
        for (profile in activeProfiles) {
            if (profile.isActive) {
                profileIdToNameMap[profile.id] = profile.name
                for (pkg in profile.blockedPackages) {
                    newMap[pkg] = profile.name
                }
            }
        }
        _blockedAppsFlow.value = newMap
        _activeBlockedPackagesCount.value = newMap.size
    }

    /**
     * Synchronously clears / filters out a deactivated profile from the blocked apps StateFlow.
     * The moment unlock is triggered the list must be empty before the unlock function returns —
     * not after, not async, synchronously cleared first.
     */
    fun onProfileDeactivatedSync(profileId: Long) {
        val profileName = profileIdToNameMap.remove(profileId)
        val current = _blockedAppsFlow.value
        val updated = if (profileName != null) {
            current.filterValues { it != profileName }
        } else {
            emptyMap()
        }
        _blockedAppsFlow.value = updated
        _activeBlockedPackagesCount.value = updated.size
    }

    /**
     * Checks if a package is currently blocked in the observed StateFlow.
     * Returns the name of the profile blocking it, or null if not blocked.
     */
    fun getBlockingProfileName(packageName: String): String? {
        return _blockedAppsFlow.value[packageName]
    }

    /**
     * Checks if a package is currently blocked.
     */
    fun isPackageBlocked(packageName: String): Boolean {
        return _blockedAppsFlow.value.containsKey(packageName)
    }

    /**
     * Clears all in-memory blocks (e.g. when all profiles are deactivated).
     */
    fun clear() {
        clearSynchronously()
    }

    /**
     * Synchronously empties all in-memory blocks immediately.
     */
    fun clearSynchronously() {
        profileIdToNameMap.clear()
        _blockedAppsFlow.value = emptyMap()
        _activeBlockedPackagesCount.value = 0
    }
}
