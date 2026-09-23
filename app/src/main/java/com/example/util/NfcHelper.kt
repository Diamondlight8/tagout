package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

sealed class TagInspectionResult {
    data class Writable(
        val hasExistingData: Boolean,
        val isTagOutTag: Boolean,
        val existingTagId: String?,
        val summary: String
    ) : TagInspectionResult()

    data class ReadOnlyOrProtected(
        val message: String = "This NFC tag is read-only or write-protected and cannot be used with TagOut."
    ) : TagInspectionResult()

    data class Error(val message: String) : TagInspectionResult()
}

sealed class NdefWriteResult {
    data class Success(val tagId: String) : NdefWriteResult()
    data class ReadOnlyOrProtected(
        val message: String = "This NFC tag is read-only or write-protected and cannot be used with TagOut."
    ) : NdefWriteResult()
    data class ConnectionLost(
        val message: String = "Tag connection lost or out of date. Tap your tag against your phone again to retry."
    ) : NdefWriteResult()
    data class Error(val message: String) : NdefWriteResult()
}

object NfcHelper {

    fun isNfcSupported(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null && adapter.isEnabled
    }

    /**
     * Extracts an android.nfc.Tag parcelable from an Intent.
     */
    fun extractTag(intent: Intent): Tag? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag
        }
    }

    /**
     * Extracts the TagOut tag ID from either:
     * 1. intent.data URI query parameter (?id=UNIQUE_TAG_ID)
     * 2. NDEF record URI (tagout://tag?id=UNIQUE_TAG_ID)
     * 3. Fallback raw hardware ID
     */
    fun extractTagIdFromUriOrNdef(intent: Intent): String? {
        // 1. Direct intent data URI (NDEF URI scheme dispatch)
        val dataUri = intent.data
        if (dataUri != null) {
            val queryId = dataUri.getQueryParameter("id")
            if (!queryId.isNullOrBlank()) return queryId
        }

        // 2. Data string check
        val dataString = intent.dataString
        if (dataString != null && dataString.contains("tagout://tag")) {
            val uri = try { Uri.parse(dataString) } catch (_: Exception) { null }
            val id = uri?.getQueryParameter("id")
            if (!id.isNullOrBlank()) return id
        }

        // 3. Inspect NDEF messages array if present
        val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (rawMessages != null) {
            for (raw in rawMessages) {
                val msg = raw as? NdefMessage ?: continue
                for (record in msg.records) {
                    try {
                        val recordUri = record.toUri()
                        if (recordUri != null && recordUri.scheme == "tagout" && recordUri.host == "tag") {
                            val id = recordUri.getQueryParameter("id")
                            if (!id.isNullOrBlank()) return id
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 4. Fallback hardware ID
        return extractTagId(intent)
    }

    /**
     * Extracts NFC Tag ID as a formatted uppercase hexadecimal string (e.g. "04:5A:21:8F").
     */
    fun extractTagId(intent: Intent): String? {
        val idBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
            ?: run {
                val tag = extractTag(intent)
                tag?.id
            } ?: return null

        if (idBytes.isEmpty()) return null
        return idBytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Inspects a physical NFC tag to determine whether it is writable, unformatted,
     * contains existing NDEF data, or is read-only / write-protected.
     */
    fun inspectTag(tag: Tag): TagInspectionResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val writable = ndef.isWritable
                if (!writable) {
                    try { ndef.close() } catch (_: Exception) {}
                    return TagInspectionResult.ReadOnlyOrProtected()
                }

                val existingMsg = try { ndef.ndefMessage } catch (_: Exception) { null }
                try { ndef.close() } catch (_: Exception) {}

                if (existingMsg != null && existingMsg.records.isNotEmpty()) {
                    val record = existingMsg.records[0]
                    val uri = try { record.toUri() } catch (_: Exception) { null }
                    val isTagOut = uri?.scheme == "tagout" && uri?.host == "tag"
                    val tagId = if (isTagOut) uri?.getQueryParameter("id") else null
                    val summary = if (isTagOut) {
                        "TagOut tag (ID: $tagId)"
                    } else {
                        uri?.toString() ?: "Data from another app"
                    }
                    return TagInspectionResult.Writable(
                        hasExistingData = true,
                        isTagOutTag = isTagOut,
                        existingTagId = tagId,
                        summary = summary
                    )
                } else {
                    return TagInspectionResult.Writable(
                        hasExistingData = false,
                        isTagOutTag = false,
                        existingTagId = null,
                        summary = "Blank tag"
                    )
                }
            } catch (e: Exception) {
                try { ndef.close() } catch (_: Exception) {}
                return TagInspectionResult.Error("Could not inspect tag: ${e.localizedMessage ?: "Connection error"}")
            }
        }

        // Tag may be an unformatted tag capable of NDEF formatting
        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            return TagInspectionResult.Writable(
                hasExistingData = false,
                isTagOutTag = false,
                existingTagId = null,
                summary = "Plain unformatted tag"
            )
        }

        return TagInspectionResult.ReadOnlyOrProtected(
            "This NFC tag is read-only or write-protected and cannot be used with TagOut."
        )
    }

    /**
     * Writes a custom URI NDEF record (tagout://tag?id=UNIQUE_TAG_ID) to the physical tag.
     * Formats unformatted tags or overwrites existing records if the tag is writable.
     */
    fun writeTagOutNdef(tag: Tag, uniqueTagId: String): NdefWriteResult {
        val uriString = "tagout://tag?id=$uniqueTagId"
        val uriRecord = NdefRecord.createUri(Uri.parse(uriString))
        val ndefMessage = NdefMessage(arrayOf(uriRecord))
        val messageBytes = ndefMessage.toByteArray()

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) {
                    try { ndef.close() } catch (_: Exception) {}
                    return NdefWriteResult.ReadOnlyOrProtected()
                }
                if (ndef.maxSize < messageBytes.size) {
                    try { ndef.close() } catch (_: Exception) {}
                    return NdefWriteResult.Error("Tag storage too small (${ndef.maxSize} bytes available, ${messageBytes.size} required)")
                }
                ndef.writeNdefMessage(ndefMessage)
                try { ndef.close() } catch (_: Exception) {}
                return NdefWriteResult.Success(uniqueTagId)
            } catch (e: java.io.IOException) {
                try { ndef.close() } catch (_: Exception) {}
                return NdefWriteResult.ConnectionLost(
                    "Tag connection lost or out of date. Tap your tag against your phone again to retry."
                )
            } catch (e: Exception) {
                try { ndef.close() } catch (_: Exception) {}
                val msg = e.localizedMessage ?: ""
                if (msg.contains("out of date", ignoreCase = true) ||
                    msg.contains("lost", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("closed", ignoreCase = true)
                ) {
                    return NdefWriteResult.ConnectionLost(
                        "Tag connection lost or out of date. Tap your tag against your phone again to retry."
                    )
                }
                return NdefWriteResult.Error(e.localizedMessage ?: "Failed to write NDEF message to tag.")
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            try {
                formatable.connect()
                formatable.format(ndefMessage)
                try { formatable.close() } catch (_: Exception) {}
                return NdefWriteResult.Success(uniqueTagId)
            } catch (e: java.io.IOException) {
                try { formatable.close() } catch (_: Exception) {}
                return NdefWriteResult.ConnectionLost(
                    "Tag connection lost during formatting. Tap your tag against your phone again to retry."
                )
            } catch (e: Exception) {
                try { formatable.close() } catch (_: Exception) {}
                val msg = e.localizedMessage ?: ""
                if (msg.contains("out of date", ignoreCase = true) ||
                    msg.contains("lost", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("closed", ignoreCase = true)
                ) {
                    return NdefWriteResult.ConnectionLost(
                        "Tag connection lost or out of date. Tap your tag against your phone again to retry."
                    )
                }
                return NdefWriteResult.Error(e.localizedMessage ?: "Failed to format tag with NDEF.")
            }
        }

        return NdefWriteResult.ReadOnlyOrProtected(
            "This NFC tag is read-only or write-protected and cannot be used with TagOut."
        )
    }

    /**
     * Triggers a subtle, calm haptic pulse when an NFC tag is tapped.
     */
    fun triggerHapticFeedback(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(40)
                }
            }
        } catch (_: Exception) {
            // Non-critical if vibration is unavailable
        }
    }
}
