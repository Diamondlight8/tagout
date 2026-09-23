package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_tags")
data class NfcTag(
    @PrimaryKey
    val id: String, // Hex UID of NFC tag (e.g. "04:5A:21:8F")
    val name: String, // User-defined name (e.g. "Desk Tag", "Lock Tag")
    val createdAt: Long = System.currentTimeMillis()
)
