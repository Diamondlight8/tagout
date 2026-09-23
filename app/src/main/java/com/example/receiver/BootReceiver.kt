package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.blocker.BlockManager
import com.example.data.db.TagOutDatabase
import com.example.service.TagOutAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BootReceiver
 *
 * Ensures TagOut blocking state, in-memory BlockManager cache, and NFC handling
 * are primed immediately after system reboot (BOOT_COMPLETED) without requiring the user
 * to open the TagOut app first.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = TagOutDatabase.getInstance(appContext)
                    val activeProfiles = db.blockingProfileDao().getActiveProfiles().first()
                    BlockManager.updateActiveProfiles(activeProfiles)
                    if (activeProfiles.isNotEmpty()) {
                        TagOutAccessibilityService.checkAndCloseIfBlocked()
                    }
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
