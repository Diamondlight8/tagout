package com.example.data.repository

import com.example.blocker.BlockManager
import com.example.data.db.AppSettingDao
import com.example.data.db.BlockingProfileDao
import com.example.data.db.NfcTagDao
import com.example.data.model.AppSetting
import com.example.data.model.BlockingProfile
import com.example.data.model.NfcTag
import com.example.data.model.TagScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagOutRepository(
    private val nfcTagDao: NfcTagDao,
    private val profileDao: BlockingProfileDao,
    private val settingDao: AppSettingDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    val allTags: Flow<List<NfcTag>> = nfcTagDao.getAllTags()
    val allProfiles: Flow<List<BlockingProfile>> = profileDao.getAllProfiles()
    val activeProfiles: Flow<List<BlockingProfile>> = profileDao.getActiveProfiles()

    init {
        // Keep in-memory BlockManager in continuous sync with database active profiles
        scope.launch {
            activeProfiles.collect { profiles ->
                BlockManager.updateActiveProfiles(profiles)
            }
        }
    }

    // Setting keys
    companion object {
        private const val KEY_MANUAL_CONTROL = "manual_control_enabled"
        private const val KEY_THEME = "app_theme_mode" // "dark", "light", "system"
    }

    val manualControlEnabled: Flow<Boolean> = settingDao.getSetting(KEY_MANUAL_CONTROL)
        .map { it == null || it == "true" } // Default: enabled
        .distinctUntilChanged()

    suspend fun setManualControlEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        settingDao.setSetting(AppSetting(KEY_MANUAL_CONTROL, enabled.toString()))
    }

    val themePreference: Flow<String> = settingDao.getSetting(KEY_THEME)
        .map { it ?: "dark" } // Default: dark AMOLED
        .distinctUntilChanged()

    suspend fun setThemePreference(theme: String) = withContext(Dispatchers.IO) {
        settingDao.setSetting(AppSetting(KEY_THEME, theme))
    }

    // Tag Operations
    suspend fun getTagById(id: String): NfcTag? = withContext(Dispatchers.IO) {
        nfcTagDao.getTagById(id)
    }

    suspend fun insertTag(tag: NfcTag) = withContext(Dispatchers.IO) {
        nfcTagDao.insertTag(tag)
    }

    suspend fun updateTag(tag: NfcTag) = withContext(Dispatchers.IO) {
        nfcTagDao.updateTag(tag)
    }

    suspend fun deleteTag(tagId: String) = withContext(Dispatchers.IO) {
        nfcTagDao.deleteTagById(tagId)
        // Clean up references to this tag in profiles
        val profiles = profileDao.getAllProfiles().first()
        for (profile in profiles) {
            val hasLock = profile.lockTagIds.contains(tagId)
            val hasUnlock = profile.unlockTagIds.contains(tagId)
            if (hasLock || hasUnlock) {
                val updated = profile.copy(
                    lockTagIds = profile.lockTagIds.filter { it != tagId },
                    unlockTagIds = profile.unlockTagIds.filter { it != tagId }
                )
                profileDao.updateProfile(updated)
            }
        }
    }

    // Profile Operations
    fun getProfileById(id: Long): Flow<BlockingProfile?> = profileDao.getProfileById(id)

    suspend fun getProfileByIdSync(id: Long): BlockingProfile? = withContext(Dispatchers.IO) {
        profileDao.getProfileByIdSync(id)
    }

    suspend fun insertProfile(profile: BlockingProfile): Long = withContext(Dispatchers.IO) {
        profileDao.insertProfile(profile)
    }

    suspend fun updateProfile(profile: BlockingProfile) = withContext(Dispatchers.IO) {
        profileDao.updateProfile(profile)
    }

    suspend fun deleteProfile(id: Long) = withContext(Dispatchers.IO) {
        profileDao.deleteProfileById(id)
    }

    suspend fun setProfileActive(id: Long, isActive: Boolean) = withContext(Dispatchers.IO) {
        if (!isActive) {
            // Synchronously clear first: the moment unlock is triggered the list must be empty
            // before the unlock function returns — not after, not async, synchronously cleared first.
            BlockManager.onProfileDeactivatedSync(id)
        }
        val timestamp = if (isActive) System.currentTimeMillis() else null
        profileDao.updateActiveStatus(id, isActive, timestamp)
        val freshActive = profileDao.getActiveProfilesSync()
        BlockManager.updateActiveProfilesSync(freshActive)
    }

    /**
     * Core NFC Tag handling logic:
     * Evaluates scanned tag against all profiles and applies lock/unlock/toggle rules.
     */
    suspend fun handleScannedTag(tagId: String): List<TagScanResult> = withContext(Dispatchers.IO) {
        val tag = nfcTagDao.getTagById(tagId)
        if (tag == null) {
            return@withContext listOf(TagScanResult.TagUnregistered(tagId))
        }

        val allProfilesList = profileDao.getAllProfiles().first()
        val matchingProfiles = allProfilesList.filter {
            it.lockTagIds.contains(tagId) || it.unlockTagIds.contains(tagId)
        }

        if (matchingProfiles.isEmpty()) {
            return@withContext listOf(TagScanResult.TagUnassigned(tag))
        }

        val results = mutableListOf<TagScanResult>()

        for (profile in matchingProfiles) {
            val isLock = profile.lockTagIds.contains(tagId)
            val isUnlock = profile.unlockTagIds.contains(tagId)

            if (isLock && isUnlock) {
                // Dual assigned -> acts as a toggle
                val newActiveState = !profile.isActive
                if (!newActiveState) {
                    BlockManager.onProfileDeactivatedSync(profile.id)
                }
                val timestamp = if (newActiveState) System.currentTimeMillis() else null
                profileDao.updateActiveStatus(profile.id, newActiveState, timestamp)
                val freshActive = profileDao.getActiveProfilesSync()
                BlockManager.updateActiveProfilesSync(freshActive)
                results.add(TagScanResult.ProfileToggled(profile, tag, newActiveState))
            } else if (isLock) {
                // Lock tag
                if (profile.isActive) {
                    // Edge case: profile already active when lock tag scanned again
                    results.add(TagScanResult.ProfileAlreadyActive(profile, tag))
                } else {
                    profileDao.updateActiveStatus(profile.id, true, System.currentTimeMillis())
                    val freshActive = profileDao.getActiveProfilesSync()
                    BlockManager.updateActiveProfilesSync(freshActive)
                    results.add(TagScanResult.ProfileLocked(profile, tag))
                }
            } else if (isUnlock) {
                // Unlock tag
                if (!profile.isActive) {
                    results.add(TagScanResult.ProfileAlreadyInactive(profile, tag))
                } else {
                    BlockManager.onProfileDeactivatedSync(profile.id)
                    profileDao.updateActiveStatus(profile.id, false, null)
                    val freshActive = profileDao.getActiveProfilesSync()
                    BlockManager.updateActiveProfilesSync(freshActive)
                    results.add(TagScanResult.ProfileUnlocked(profile, tag))
                }
            }
        }

        results
    }
}
