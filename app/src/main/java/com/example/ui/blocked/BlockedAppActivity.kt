package com.example.ui.blocked

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.blocker.BlockManager
import com.example.data.db.TagOutDatabase
import com.example.data.model.TagScanResult
import com.example.data.repository.TagOutRepository
import com.example.ui.components.NfcRippleSymbol
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.SoftIndigo
import com.example.ui.theme.TagOutTheme
import com.example.ui.theme.TrueBlack
import com.example.util.NfcHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockedAppActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
    }

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var repository: TagOutRepository

    private var blockedPackage: String = ""
    private var profileName: String = ""
    private var appLabel: String = ""
    private var rippleTriggerState = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val db = TagOutDatabase.getInstance(applicationContext)
        repository = TagOutRepository(db.nfcTagDao(), db.blockingProfileDao(), db.appSettingDao())

        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: ""
        profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Focus Profile"

        // Resolve friendly application label
        appLabel = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(blockedPackage, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            blockedPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        setContent {
            TagOutTheme(themePreference = "dark") {
                var manualControlEnabled by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    manualControlEnabled = repository.manualControlEnabled.first()
                }

                BlockedScreenContent(
                    appLabel = appLabel,
                    profileName = profileName,
                    manualControlEnabled = manualControlEnabled,
                    rippleTrigger = rippleTriggerState.value,
                    onReturnHome = {
                        lifecycleScope.launch {
                            // Immediate recheck: Trigger a fresh state read from the database
                            // and update the accessibility service's blocked list one more time.
                            // Ensures any stale state is cleared on that tap.
                            val db = TagOutDatabase.getInstance(applicationContext)
                            val freshActiveProfiles = db.blockingProfileDao().getActiveProfilesSync()
                            BlockManager.updateActiveProfilesSync(freshActiveProfiles)

                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                            finish()
                        }
                    },
                    onManualUnlock = {
                        lifecycleScope.launch {
                            val activeProfiles = repository.activeProfiles.first()
                            val matching = activeProfiles.firstOrNull { it.name == profileName }
                            if (matching != null) {
                                BlockManager.onProfileDeactivatedSync(matching.id)
                                repository.setProfileActive(matching.id, false)
                            } else {
                                BlockManager.clearSynchronously()
                            }
                            finish()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tagId = NfcHelper.extractTagId(intent) ?: return
        NfcHelper.triggerHapticFeedback(this)
        rippleTriggerState.value = System.currentTimeMillis()

        lifecycleScope.launch {
            val results = repository.handleScannedTag(tagId)
            for (res in results) {
                if (res is TagScanResult.ProfileUnlocked ||
                    (res is TagScanResult.ProfileToggled && !res.nowActive)) {
                    // Successfully unlocked profile!
                    finish()
                    return@launch
                }
            }
        }
    }

    private fun enableNfcForegroundDispatch() {
        try {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val filters = arrayOf(
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    try { addDataType("*/*") } catch (_: Exception) {}
                },
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            )
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, null)
        } catch (_: Exception) {}
    }

    private fun disableNfcForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {}
    }
}

@Composable
fun BlockedScreenContent(
    appLabel: String,
    profileName: String,
    manualControlEnabled: Boolean,
    rippleTrigger: Long = 0L,
    onReturnHome: () -> Unit,
    onManualUnlock: () -> Unit
) {
    Scaffold(
        containerColor = TrueBlack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Minimal NFC Icon with concentric ripple animation
            NfcRippleSymbol(
                triggerKey = rippleTrigger,
                containerSize = 100.dp,
                iconSize = 40.dp,
                iconTint = Color.White,
                isDarkTheme = true
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Focus Mode Active",
                style = MaterialTheme.typography.headlineLarge,
                color = DarkTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "\"$appLabel\" is blocked by profile \"$profileName\".",
                style = MaterialTheme.typography.bodyLarge,
                color = DarkTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tap your assigned NFC tag to unlock, or step away.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftIndigo,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Primary Return Home Button
            Button(
                onClick = onReturnHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = TrueBlack
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("blocked_return_home_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Return Home",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (manualControlEnabled) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onManualUnlock,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DarkTextSecondary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blocked_manual_unlock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Unlock Manually",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
