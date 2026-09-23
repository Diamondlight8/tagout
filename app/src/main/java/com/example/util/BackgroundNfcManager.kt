package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.blocker.BlockManager
import com.example.data.db.TagOutDatabase
import com.example.service.TagOutAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Handles background NFC tag scanning when the app is closed or in the background.
 *
 * Silently evaluates scanned tags against saved profiles in Room database,
 * activates or deactivates profiles, auto-closes foreground blocked apps if needed,
 * and provides subtle haptic and notification feedback without opening the app UI.
 */
object BackgroundNfcManager {

    private const val NOTIFICATION_CHANNEL_ID = "tagout_nfc_status"
    private const val NOTIFICATION_ID = 1001

    suspend fun processNfcIntent(context: Context, intent: Intent): String? {
        val tagId = NfcHelper.extractTagId(intent) ?: return null
        return processScannedTag(context.applicationContext, tagId)
    }

    suspend fun processScannedTag(context: Context, tagId: String): String? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = TagOutDatabase.getInstance(appContext)
        val tagDao = db.nfcTagDao()
        val profileDao = db.blockingProfileDao()

        val tag = tagDao.getTagById(tagId)
        if (tag == null) {
            // If the tag is not recognised as a TagOut tag, finish silently with no feedback
            return@withContext null
        }

        val allProfiles = profileDao.getAllProfiles().first()
        val matchingProfiles = allProfiles.filter {
            it.lockTagIds.contains(tagId) || it.unlockTagIds.contains(tagId)
        }

        if (matchingProfiles.isEmpty()) {
            // Not assigned to any profile, finish silently with no feedback
            return@withContext null
        }

        var feedbackMessage: String? = null
        var anyLocked = false
        var anyUnlocked = false

        for (profile in matchingProfiles) {
            val isLock = profile.lockTagIds.contains(tagId)
            val isUnlock = profile.unlockTagIds.contains(tagId)

            if (isLock && isUnlock) {
                // Dual assigned -> toggle active state
                val newState = !profile.isActive
                if (!newState) {
                    BlockManager.onProfileDeactivatedSync(profile.id)
                }
                val timestamp = if (newState) System.currentTimeMillis() else null
                profileDao.updateActiveStatus(profile.id, newState, timestamp)
                val remainingActive = profileDao.getActiveProfilesSync()
                BlockManager.updateActiveProfilesSync(remainingActive)
                val toastText = if (newState) {
                    anyLocked = true
                    "${profile.name} locked"
                } else {
                    anyUnlocked = true
                    "${profile.name} unlocked"
                }
                feedbackMessage = toastText
                showToast(appContext, toastText)
            } else if (isLock) {
                if (!profile.isActive) {
                    profileDao.updateActiveStatus(profile.id, true, System.currentTimeMillis())
                    val freshActive = profileDao.getActiveProfilesSync()
                    BlockManager.updateActiveProfilesSync(freshActive)
                    anyLocked = true
                    val toastText = "${profile.name} locked"
                    feedbackMessage = toastText
                    showToast(appContext, toastText)
                } else {
                    feedbackMessage = "Profile \"${profile.name}\" is already locked"
                }
            } else if (isUnlock) {
                if (profile.isActive) {
                    // Synchronously clear first before unlock returns
                    BlockManager.onProfileDeactivatedSync(profile.id)
                    profileDao.updateActiveStatus(profile.id, false, null)
                    val remainingActive = profileDao.getActiveProfilesSync()
                    BlockManager.updateActiveProfilesSync(remainingActive)
                    anyUnlocked = true
                    val toastText = "${profile.name} unlocked"
                    feedbackMessage = toastText
                    showToast(appContext, toastText)
                } else {
                    feedbackMessage = "Profile \"${profile.name}\" is already unlocked"
                }
            }
        }

        // Haptic feedback: short single pulse for lock, double pulse for unlock
        triggerSubtleHaptic(appContext, isUnlock = anyUnlocked && !anyLocked)

        // Immediately update in-memory BlockManager and close any foreground blocked apps
        val updatedActiveProfiles = profileDao.getActiveProfiles().first()
        BlockManager.updateActiveProfiles(updatedActiveProfiles)

        if (anyLocked) {
            TagOutAccessibilityService.checkAndCloseIfBlocked()
        }

        // Small system notification saying which profile was toggled
        if (feedbackMessage != null) {
            postSubtleNotification(appContext, "TagOut", feedbackMessage)
        }

        feedbackMessage
    }

    /**
     * Subtle haptic feedback confirming the NFC tag scan and profile action.
     */
    fun triggerSubtleHaptic(context: Context, isUnlock: Boolean = false) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isUnlock) {
                    // Two gentle quick taps for unlock (subtle, calm)
                    VibrationEffect.createWaveform(longArrayOf(0, 35, 60, 35), -1)
                } else {
                    // Single crisp pulse for lock
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45L)
            }
        } catch (_: Exception) {
            // Vibrator unavailable
        }
    }

    /**
     * Posts a subtle, low-priority system notification indicating the tag action.
     * Stays briefly without intrusive sound or heads-up banner.
     */
    private fun postSubtleNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // Ensure notification channel exists on Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "TagOut Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Subtle status feedback for NFC tag actions"
                    enableVibration(false)
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Check notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(4000L)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Notification dispatch failed gracefully
        }
    }

    /**
     * Displays a short Android Toast on the main UI thread with exact message format.
     */
    fun showToast(context: Context, text: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // Graceful fallback
        }
    }
}
