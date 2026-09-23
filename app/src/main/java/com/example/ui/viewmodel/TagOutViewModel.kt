package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.TagOutDatabase
import com.example.data.model.BlockingProfile
import com.example.data.model.InstalledApp
import com.example.data.model.NfcTag
import com.example.data.model.TagScanResult
import com.example.data.repository.TagOutRepository
import com.example.util.AccessibilityHelper
import com.example.util.AppInfoProvider
import com.example.util.BatteryOptimizationHelper
import com.example.util.NdefWriteResult
import com.example.util.NfcHelper
import com.example.util.TagInspectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed class TagWriteState {
    object ReadyToScan : TagWriteState()
    object Writing : TagWriteState()
    data class WaitingForReconnection(
        val message: String,
        val tagName: String,
        val uniqueId: String
    ) : TagWriteState()
    data class WriteSuccess(val tagId: String, val tagName: String) : TagWriteState()
    data class WriteError(val message: String, val isReadOnly: Boolean = false) : TagWriteState()
}

class TagOutViewModel(
    application: Application,
    private val repository: TagOutRepository,
    private val appInfoProvider: AppInfoProvider
) : AndroidViewModel(application) {

    val tags: StateFlow<List<NfcTag>> = repository.allTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles: StateFlow<List<BlockingProfile>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfiles: StateFlow<List<BlockingProfile>> = repository.activeProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isAnyProfileActive: StateFlow<Boolean> = repository.activeProfiles
        .map { list -> list.any { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val manualControlEnabled: StateFlow<Boolean> = repository.manualControlEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val themePreference: StateFlow<String> = repository.themePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")

    private val _isAccessibilityEnabled = MutableStateFlow(
        AccessibilityHelper.isAccessibilityServiceEnabled(application)
    )
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isBatteryOptimizationIgnored = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application)
    )
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    private val _showBatteryOptimizationPrompt = MutableStateFlow(false)
    val showBatteryOptimizationPrompt: StateFlow<Boolean> = _showBatteryOptimizationPrompt.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _scanFeedbackMessage = MutableSharedFlow<String>()
    val scanFeedbackMessage: SharedFlow<String> = _scanFeedbackMessage.asSharedFlow()

    // Event emitted when NFC tag is scanned to trigger concentric 800ms ripple animation
    private val _nfcScanRippleEvent = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val nfcScanRippleEvent: SharedFlow<Long> = _nfcScanRippleEvent.asSharedFlow()

    // Add Tag Dialog & NDEF Writing state
    private val _isAddTagDialogOpen = MutableStateFlow(false)
    val isAddTagDialogOpen: StateFlow<Boolean> = _isAddTagDialogOpen.asStateFlow()

    private val _pendingTagId = MutableStateFlow<String?>(null)
    val pendingTagId: StateFlow<String?> = _pendingTagId.asStateFlow()

    private val _pendingTagName = MutableStateFlow("")
    val pendingTagName: StateFlow<String> = _pendingTagName.asStateFlow()

    private val _tagWriteState = MutableStateFlow<TagWriteState>(TagWriteState.ReadyToScan)
    val tagWriteState: StateFlow<TagWriteState> = _tagWriteState.asStateFlow()

    private val _currentPhysicalTag = MutableStateFlow<Tag?>(null)
    val currentPhysicalTag: StateFlow<Tag?> = _currentPhysicalTag.asStateFlow()

    init {
        loadInstalledApps()
        checkAccessibilityState()
        checkBatteryOptimizationState()
    }

    fun checkAccessibilityState() {
        _isAccessibilityEnabled.value = AccessibilityHelper.isAccessibilityServiceEnabled(getApplication())
    }

    fun checkBatteryOptimizationState() {
        _isBatteryOptimizationIgnored.value = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(getApplication())
    }

    fun openBatteryOptimizationPrompt() {
        _showBatteryOptimizationPrompt.value = true
    }

    fun dismissBatteryOptimizationPrompt() {
        _showBatteryOptimizationPrompt.value = false
    }

    fun showLockedSnackbar() {
        viewModelScope.launch {
            _scanFeedbackMessage.emit("Deactivate the active profile to edit settings.")
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            if (_installedApps.value.isEmpty()) {
                _isLoadingApps.value = true
                val apps = appInfoProvider.getInstalledLauncherApps()
                _installedApps.value = apps
                _isLoadingApps.value = false
            }
        }
    }

    fun setPendingTagName(name: String) {
        _pendingTagName.value = name
    }

    // NFC Tag Management
    fun openAddTagDialog(tagId: String? = null) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        _pendingTagName.value = ""
        _pendingTagId.value = tagId
        _currentPhysicalTag.value = null
        _tagWriteState.value = TagWriteState.ReadyToScan
        _isAddTagDialogOpen.value = true
    }

    fun closeAddTagDialog() {
        _pendingTagName.value = ""
        _pendingTagId.value = null
        _currentPhysicalTag.value = null
        _tagWriteState.value = TagWriteState.ReadyToScan
        _isAddTagDialogOpen.value = false
    }

    fun resetTagWriteState() {
        _currentPhysicalTag.value = null
        _tagWriteState.value = TagWriteState.ReadyToScan
    }

    fun setPendingTagId(id: String) {
        _pendingTagId.value = id
    }

    /**
     * Intercepts an NFC intent while the Add Tag setup flow is visible.
     * Writes to the tag immediately without an intermediate confirmation dialog.
     * If write fails with a connection or "out of date" error, prompts to tap again
     * and automatically retries writing without restarting the flow.
     */
    fun onTagDetectedDuringSetup(intent: Intent) {
        if (!_isAddTagDialogOpen.value) return
        NfcHelper.triggerHapticFeedback(getApplication())
        _nfcScanRippleEvent.tryEmit(System.currentTimeMillis())

        val rawTag = NfcHelper.extractTag(intent)
        val hwId = NfcHelper.extractTagId(intent) ?: "UNKNOWN"

        val currentState = _tagWriteState.value
        val (uniqueId, tagName) = when (currentState) {
            is TagWriteState.WaitingForReconnection -> {
                // Reconnection attempt: reuse existing unique ID and tag name!
                Pair(currentState.uniqueId, currentState.tagName)
            }
            else -> {
                val inputName = _pendingTagName.value.trim()
                val name = if (inputName.isNotEmpty()) inputName else "Tag ${tags.value.size + 1}"
                val newId = "tag_${UUID.randomUUID().toString().take(8)}"
                Pair(newId, name)
            }
        }

        if (rawTag != null) {
            _currentPhysicalTag.value = rawTag
            _tagWriteState.value = TagWriteState.Writing

            viewModelScope.launch(Dispatchers.IO) {
                when (val writeResult = NfcHelper.writeTagOutNdef(rawTag, uniqueId)) {
                    is NdefWriteResult.Success -> {
                        repository.insertTag(NfcTag(id = uniqueId, name = tagName))
                        NfcHelper.triggerHapticFeedback(getApplication())
                        _tagWriteState.value = TagWriteState.WriteSuccess(uniqueId, tagName)
                        _scanFeedbackMessage.emit("Tag \"$tagName\" paired successfully")
                        delay(1200)
                        closeAddTagDialog()
                    }
                    is NdefWriteResult.ConnectionLost -> {
                        // Tag connection lost or out of date: wait for user to tap tag again and retry automatically
                        _tagWriteState.value = TagWriteState.WaitingForReconnection(
                            message = writeResult.message,
                            tagName = tagName,
                            uniqueId = uniqueId
                        )
                    }
                    is NdefWriteResult.ReadOnlyOrProtected -> {
                        _tagWriteState.value = TagWriteState.WriteError(
                            message = writeResult.message,
                            isReadOnly = true
                        )
                    }
                    is NdefWriteResult.Error -> {
                        _tagWriteState.value = TagWriteState.WriteError(
                            message = writeResult.message,
                            isReadOnly = false
                        )
                    }
                }
            }
        } else {
            // Emulation or tests without raw Tag parcelable
            val fallbackName = _pendingTagName.value.trim().ifEmpty { "Tag ${tags.value.size + 1}" }
            saveNewTag(hwId, fallbackName)
        }
    }

    /**
     * Writes the NDEF URI record (tagout://tag?id=UNIQUE_TAG_ID) to the physical tag,
     * formats if unformatted, and saves the unique tag to the local Room database.
     */
    fun writeAndSaveTag(name: String) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        val trimmedName = name.trim().ifEmpty { "Tag ${tags.value.size + 1}" }
        val physicalTag = _currentPhysicalTag.value

        if (physicalTag != null) {
            _tagWriteState.value = TagWriteState.Writing
            viewModelScope.launch(Dispatchers.IO) {
                val uniqueId = "tag_${UUID.randomUUID().toString().take(8)}"
                when (val writeResult = NfcHelper.writeTagOutNdef(physicalTag, uniqueId)) {
                    is NdefWriteResult.Success -> {
                        repository.insertTag(NfcTag(id = uniqueId, name = trimmedName))
                        NfcHelper.triggerHapticFeedback(getApplication())
                        _tagWriteState.value = TagWriteState.WriteSuccess(uniqueId, trimmedName)
                        _scanFeedbackMessage.emit("Tag \"$trimmedName\" paired successfully")
                        delay(1200)
                        closeAddTagDialog()
                    }
                    is NdefWriteResult.ConnectionLost -> {
                        _tagWriteState.value = TagWriteState.WaitingForReconnection(
                            message = writeResult.message,
                            tagName = trimmedName,
                            uniqueId = uniqueId
                        )
                    }
                    is NdefWriteResult.ReadOnlyOrProtected -> {
                        _tagWriteState.value = TagWriteState.WriteError(
                            message = writeResult.message,
                            isReadOnly = true
                        )
                    }
                    is NdefWriteResult.Error -> {
                        _tagWriteState.value = TagWriteState.WriteError(
                            message = writeResult.message,
                            isReadOnly = false
                        )
                    }
                }
            }
        } else {
            // Fallback for tests or manual simulation
            val fallbackId = _pendingTagId.value ?: "tag_${UUID.randomUUID().toString().take(8)}"
            saveNewTag(fallbackId, trimmedName)
        }
    }

    fun saveNewTag(id: String, name: String) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        viewModelScope.launch {
            val trimmedName = name.trim().ifEmpty { "Tag ${tags.value.size + 1}" }
            repository.insertTag(NfcTag(id = id, name = trimmedName))
            closeAddTagDialog()
            _scanFeedbackMessage.emit("Tag \"$trimmedName\" saved")
        }
    }

    fun renameTag(tag: NfcTag, newName: String) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (trimmed.isNotEmpty()) {
                repository.updateTag(tag.copy(name = trimmed))
                _scanFeedbackMessage.emit("Tag renamed to \"$trimmed\"")
            }
        }
    }

    fun deleteTag(tagId: String) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        viewModelScope.launch {
            repository.deleteTag(tagId)
            _scanFeedbackMessage.emit("Tag removed")
        }
    }

    // Profile Management
    fun saveProfile(
        id: Long,
        name: String,
        blockedPackages: List<String>,
        lockTagIds: List<String>,
        unlockTagIds: List<String>
    ) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        viewModelScope.launch {
            val trimmedName = name.trim().ifEmpty { "Focus Profile" }
            if (id == 0L) {
                // New profile
                repository.insertProfile(
                    BlockingProfile(
                        name = trimmedName,
                        blockedPackages = blockedPackages,
                        lockTagIds = lockTagIds,
                        unlockTagIds = unlockTagIds,
                        isActive = false
                    )
                )
                _scanFeedbackMessage.emit("Profile \"$trimmedName\" created")
            } else {
                // Existing profile
                val existing = repository.getProfileByIdSync(id)
                val updated = (existing ?: BlockingProfile(
                    name = trimmedName,
                    blockedPackages = blockedPackages,
                    lockTagIds = lockTagIds,
                    unlockTagIds = unlockTagIds
                )).copy(
                    name = trimmedName,
                    blockedPackages = blockedPackages,
                    lockTagIds = lockTagIds,
                    unlockTagIds = unlockTagIds
                )
                repository.updateProfile(updated)
                _scanFeedbackMessage.emit("Profile \"$trimmedName\" updated")
            }
        }
    }

    fun deleteProfile(id: Long) {
        if (isAnyProfileActive.value) {
            showLockedSnackbar()
            return
        }
        viewModelScope.launch {
            repository.deleteProfile(id)
            _scanFeedbackMessage.emit("Profile deleted")
        }
    }

    fun toggleProfileManual(profile: BlockingProfile) {
        viewModelScope.launch {
            if (!manualControlEnabled.value) {
                _scanFeedbackMessage.emit("Manual control is disabled in Settings")
                return@launch
            }
            val newActive = !profile.isActive
            repository.setProfileActive(profile.id, newActive)
            val msg = if (newActive) "Locked with \"${profile.name}\"" else "Unlocked \"${profile.name}\""
            _scanFeedbackMessage.emit(msg)
        }
    }

    // Process Scanned NFC Tag
    fun onNfcTagScanned(tagId: String) {
        NfcHelper.triggerHapticFeedback(getApplication())
        _nfcScanRippleEvent.tryEmit(System.currentTimeMillis())

        // If the Add Tag dialog is currently visible, populate the pending tag ID!
        if (_isAddTagDialogOpen.value) {
            _pendingTagId.value = tagId
            return
        }

        viewModelScope.launch {
            val results = repository.handleScannedTag(tagId)
            for (res in results) {
                when (res) {
                    is TagScanResult.ProfileLocked -> {
                        showToast("${res.profile.name} locked")
                        _scanFeedbackMessage.emit("Locked: \"${res.profile.name}\" via ${res.tag.name}")
                    }
                    is TagScanResult.ProfileUnlocked -> {
                        showToast("${res.profile.name} unlocked")
                        _scanFeedbackMessage.emit("Unlocked: \"${res.profile.name}\" via ${res.tag.name}")
                    }
                    is TagScanResult.ProfileToggled -> {
                        val state = if (res.nowActive) "locked" else "unlocked"
                        showToast("${res.profile.name} $state")
                        val displayState = if (res.nowActive) "Locked" else "Unlocked"
                        _scanFeedbackMessage.emit("$displayState: \"${res.profile.name}\" via ${res.tag.name}")
                    }
                    is TagScanResult.ProfileAlreadyActive -> {
                        _scanFeedbackMessage.emit("\"${res.profile.name}\" is already locked")
                    }
                    is TagScanResult.ProfileAlreadyInactive -> {
                        _scanFeedbackMessage.emit("\"${res.profile.name}\" is already unlocked")
                    }
                    is TagScanResult.TagUnassigned -> {
                        _scanFeedbackMessage.emit("\"${res.tag.name}\" is not assigned to any profile")
                    }
                    is TagScanResult.TagUnregistered -> {
                        if (isAnyProfileActive.value) {
                            _scanFeedbackMessage.emit("Deactivate the active profile to edit settings.")
                        } else {
                            openAddTagDialog(tagId)
                        }
                    }
                }
            }
        }
    }

    private fun showToast(text: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // Graceful fallback
        }
    }

    // Settings
    fun setManualControl(enabled: Boolean) {
        viewModelScope.launch {
            repository.setManualControlEnabled(enabled)
        }
    }

    fun setThemePreference(theme: String) {
        viewModelScope.launch {
            repository.setThemePreference(theme)
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = TagOutDatabase.getInstance(application)
            val repo = TagOutRepository(db.nfcTagDao(), db.blockingProfileDao(), db.appSettingDao())
            val appInfo = AppInfoProvider(application)
            return TagOutViewModel(application, repo, appInfo) as T
        }
    }
}
