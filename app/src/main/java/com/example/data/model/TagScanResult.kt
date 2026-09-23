package com.example.data.model

sealed class TagScanResult {
    data class ProfileLocked(val profile: BlockingProfile, val tag: NfcTag) : TagScanResult()
    data class ProfileUnlocked(val profile: BlockingProfile, val tag: NfcTag) : TagScanResult()
    data class ProfileToggled(val profile: BlockingProfile, val tag: NfcTag, val nowActive: Boolean) : TagScanResult()
    data class ProfileAlreadyActive(val profile: BlockingProfile, val tag: NfcTag) : TagScanResult()
    data class ProfileAlreadyInactive(val profile: BlockingProfile, val tag: NfcTag) : TagScanResult()
    data class TagUnassigned(val tag: NfcTag) : TagScanResult()
    data class TagUnregistered(val tagId: String) : TagScanResult()
}
