package com.example.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BlockingProfile
import com.example.data.model.NfcTag
import com.example.ui.components.AddTagDialog
import com.example.ui.components.BatteryOptimizationPromptDialog
import com.example.ui.components.NfcRippleSymbol
import com.example.ui.components.ProfileCard
import com.example.ui.components.RenameTagDialog
import com.example.ui.components.TagCard
import com.example.ui.theme.SoftIndigo
import com.example.ui.viewmodel.TagOutViewModel
import com.example.util.AccessibilityHelper
import com.example.util.BatteryOptimizationHelper
import com.example.util.NfcHelper
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: TagOutViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToNewProfile: () -> Unit,
    onNavigateToEditProfile: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val isAnyProfileActive by viewModel.isAnyProfileActive.collectAsStateWithLifecycle()
    val manualControlEnabled by viewModel.manualControlEnabled.collectAsStateWithLifecycle()
    val isAddTagDialogOpen by viewModel.isAddTagDialogOpen.collectAsStateWithLifecycle()
    val pendingTagId by viewModel.pendingTagId.collectAsStateWithLifecycle()
    val pendingTagName by viewModel.pendingTagName.collectAsStateWithLifecycle()
    val tagWriteState by viewModel.tagWriteState.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsStateWithLifecycle()
    val showBatteryPromptDialog by viewModel.showBatteryOptimizationPrompt.collectAsStateWithLifecycle()
    val nfcRippleTrigger by viewModel.nfcScanRippleEvent.collectAsStateWithLifecycle(initialValue = 0L)

    val snackbarHostState = remember { SnackbarHostState() }
    var tagToRename by remember { mutableStateOf<NfcTag?>(null) }
    var profileToDelete by remember { mutableStateOf<BlockingProfile?>(null) }
    var tagToDelete by remember { mutableStateOf<NfcTag?>(null) }

    val isNfcSupported = remember { NfcHelper.isNfcSupported(context) }
    var isNfcEnabled by remember { mutableStateOf(NfcHelper.isNfcEnabled(context)) }
    val isDark = isSystemInDarkTheme()

    // Listen for scan messages
    LaunchedEffect(Unit) {
        viewModel.scanFeedbackMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "TagOut",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Requirement 1: Persistent Non-Dismissable Banner if Accessibility Service is revoked
            if (!isAccessibilityEnabled) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("accessibility_revoked_banner"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Accessibility Service Disabled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "TagOut requires the accessibility service to detect and close blocked apps.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { AccessibilityHelper.openAccessibilitySettings(context) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.testTag("re_enable_accessibility_button")
                            ) {
                                Text(
                                    text = "Enable",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // NFC Warning Banner if NFC is turned off
            if (isNfcSupported && !isNfcEnabled) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = SoftIndigo,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "NFC is turned off",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tap to open Android Settings and enable NFC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Battery Optimization Banner to keep Accessibility Service and blocker active
            if (!isBatteryOptimizationIgnored) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("battery_optimization_banner"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, SoftIndigo.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryAlert,
                                contentDescription = "Battery Alert",
                                tint = SoftIndigo,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Disable Battery Optimization",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Ensures the Accessibility Service and app blocker stay active in the background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.openBatteryOptimizationPrompt()
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoftIndigo,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("disable_battery_optimization_button")
                            ) {
                                Text(
                                    text = "Fix",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // Requirement 4: Concentric Ripple Visualizer Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nfc_hero_scanner_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Static NFC symbol with 800ms expanding concentric rings animation on scan
                        NfcRippleSymbol(
                            triggerKey = nfcRippleTrigger,
                            containerSize = 72.dp,
                            iconSize = 32.dp,
                            iconTint = if (isAnyProfileActive) SoftIndigo else MaterialTheme.colorScheme.primary,
                            isDarkTheme = isDark
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isAnyProfileActive) "Focus Active • Ready for Tag" else "Tap NFC Tag to Toggle",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAnyProfileActive) {
                                    "Editing is locked while a profile is active. Tap unlock tag to release."
                                } else {
                                    "Tap an assigned tag to lock focus, or tap to pair a new tag."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // SECTION 1: NFC Tags
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "NFC Tags",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Physical keys to toggle your focus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            if (isAnyProfileActive) {
                                viewModel.showLockedSnackbar()
                            } else {
                                viewModel.openAddTagDialog()
                            }
                        },
                        modifier = Modifier.testTag("add_tag_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isAnyProfileActive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add Tag",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isAnyProfileActive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (tags.isEmpty()) {
                item {
                    // Subtle empty state
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empty_tags_card"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No NFC tags registered yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Pair a tag to act as a lock or unlock key for your focus profiles.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = {
                                    if (isAnyProfileActive) viewModel.showLockedSnackbar()
                                    else viewModel.openAddTagDialog()
                                },
                                modifier = Modifier.testTag("get_started_add_tag_button")
                            ) {
                                Text(
                                    text = "Add Your First Tag",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SoftIndigo
                                )
                            }
                        }
                    }
                }
            } else {
                items(tags, key = { it.id }) { tag ->
                    val count = profiles.count {
                        it.lockTagIds.contains(tag.id) || it.unlockTagIds.contains(tag.id)
                    }
                    TagCard(
                        tag = tag,
                        assignedProfilesCount = count,
                        isLocked = isAnyProfileActive,
                        onRename = {
                            if (isAnyProfileActive) viewModel.showLockedSnackbar()
                            else tagToRename = tag
                        },
                        onDelete = {
                            if (isAnyProfileActive) viewModel.showLockedSnackbar()
                            else tagToDelete = tag
                        }
                    )
                }
            }

            // SECTION 2: Blocking Profiles
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Profiles",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Apps to block when locked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            if (isAnyProfileActive) viewModel.showLockedSnackbar()
                            else onNavigateToNewProfile()
                        },
                        modifier = Modifier.testTag("new_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isAnyProfileActive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "New Profile",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isAnyProfileActive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (profiles.isEmpty()) {
                item {
                    // Subtle empty state
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empty_profiles_card"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (tags.isEmpty()) "Add a tag first" else "No profiles yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (tags.isEmpty()) {
                                    "Once you register an NFC tag above, you can build blocking profiles."
                                } else {
                                    "Create a profile, choose apps to block, and assign your tags as lock and unlock keys."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            if (tags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(
                                    onClick = {
                                        if (isAnyProfileActive) viewModel.showLockedSnackbar()
                                        else onNavigateToNewProfile()
                                    },
                                    modifier = Modifier.testTag("create_first_profile_button")
                                ) {
                                    Text(
                                        text = "Create Profile",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SoftIndigo
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        allTags = tags,
                        manualControlEnabled = manualControlEnabled,
                        onToggleActive = { viewModel.toggleProfileManual(profile) },
                        onClick = { onNavigateToEditProfile(profile.id) },
                        onDelete = {
                            if (isAnyProfileActive) viewModel.showLockedSnackbar()
                            else profileToDelete = profile
                        }
                    )
                }
            }

            // Bottom spacer for comfortable scrolling
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Add Tag Dialog (NDEF setup & immediate write flow with auto-retry)
    if (isAddTagDialogOpen) {
        AddTagDialog(
            tagWriteState = tagWriteState,
            pendingTagId = pendingTagId,
            tagName = pendingTagName,
            onTagNameChange = { viewModel.setPendingTagName(it) },
            rippleTrigger = nfcRippleTrigger,
            onWriteAndSave = { name ->
                viewModel.writeAndSaveTag(name)
            },
            onResetState = {
                viewModel.resetTagWriteState()
            },
            onSaveManual = { id, name ->
                viewModel.saveNewTag(id, name)
            },
            onDismiss = { viewModel.closeAddTagDialog() }
        )
    }

    // Rename Tag Dialog
    tagToRename?.let { tag ->
        RenameTagDialog(
            tag = tag,
            onConfirm = { newName ->
                viewModel.renameTag(tag, newName)
                tagToRename = null
            },
            onDismiss = { tagToRename = null }
        )
    }

    // Delete Tag Confirmation Dialog
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "Delete Tag?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${tag.name}\"? It will be unassigned from all profiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTag(tag.id)
                        tagToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_tag_button")
                ) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    // Delete Profile Confirmation Dialog
    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "Delete Profile?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Delete \"${profile.name}\"? Any active app blocks by this profile will be deactivated.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        profileToDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_profile_button")
                ) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showBatteryPromptDialog) {
        BatteryOptimizationPromptDialog(
            onConfirm = {
                BatteryOptimizationHelper.promptDisableBatteryOptimizations(context)
                viewModel.dismissBatteryOptimizationPrompt()
            },
            onDismiss = {
                viewModel.dismissBatteryOptimizationPrompt()
            }
        )
    }
}
