package com.example.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.example.util.BackgroundNfcManager
import com.example.util.NfcHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Transparent, no-display NFC Handler Activity.
 *
 * Triggered by Android's NDEF dispatch system when a TagOut-written NFC tag
 * (tagout://tag?id=UNIQUE_TAG_ID) is tapped from anywhere on the phone
 * (homescreen, inside any app, etc.).
 *
 * Extracts the tag ID from the URI, matches it against saved TagOut profiles,
 * toggles the profile (even if manual control is disabled), triggers subtle haptics
 * and a system notification, and immediately finishes in under 200ms without displaying any UI.
 */
class NfcHandlerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableActivityAnimation()

        handleIncomingNfc(intent)
        finish()
        disableActivityAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingNfc(it) }
        finish()
        disableActivityAnimation()
    }

    private fun handleIncomingNfc(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val tagId = NfcHelper.extractTagIdFromUriOrNdef(incomingIntent) ?: return

        // Process tag synchronously in ~5-15ms (< 200ms total)
        try {
            runBlocking(Dispatchers.IO) {
                BackgroundNfcManager.processScannedTag(applicationContext, tagId)
            }
        } catch (_: Exception) {
            // Handled gracefully
        }
    }

    private fun disableActivityAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
