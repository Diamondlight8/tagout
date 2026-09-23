package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocking_profiles")
data class BlockingProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val blockedPackages: List<String>,
    val lockTagIds: List<String>,
    val unlockTagIds: List<String>,
    val isActive: Boolean = false,
    val activatedAt: Long? = null
)
