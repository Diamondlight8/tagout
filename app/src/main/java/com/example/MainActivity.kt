package com.example

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.home.HomeScreen
import com.example.ui.onboarding.AccessibilityOnboardingScreen
import com.example.ui.profile.EditProfileScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TagOutTheme
import com.example.ui.viewmodel.TagOutViewModel
import com.example.util.NfcHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var isForegroundDispatchActive = false
    private val viewModel: TagOutViewModel by viewModels {
        TagOutViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Observe isAddTagDialogOpen to enable foreground dispatch ONLY during tag writing/setup flow
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.isAddTagDialogOpen.collect { isOpen ->
                    if (isOpen) {
                        enableNfcForegroundDispatch()
                    } else {
                        disableNfcForegroundDispatch()
                    }
                }
            }
        }

        setContent {
            val themePreference by viewModel.themePreference.collectAsStateWithLifecycle()

            TagOutTheme(themePreference = themePreference) {
                TagOutApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityState()
        viewModel.checkBatteryOptimizationState()
        if (viewModel.isAddTagDialogOpen.value) {
            enableNfcForegroundDispatch()
        }
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Foreground dispatch is only active during the tag writing/setup flow
        if (viewModel.isAddTagDialogOpen.value) {
            viewModel.onTagDetectedDuringSetup(intent)
        }
    }

    private fun enableNfcForegroundDispatch() {
        if (isForegroundDispatchActive) return
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
            isForegroundDispatchActive = true
        } catch (_: Exception) {
            // NFC not available or disabled
        }
    }

    private fun disableNfcForegroundDispatch() {
        if (!isForegroundDispatchActive) return
        try {
            nfcAdapter?.disableForegroundDispatch(this)
            isForegroundDispatchActive = false
        } catch (_: Exception) {
            // NFC not available
        }
    }
}

@Composable
fun TagOutApp(viewModel: TagOutViewModel) {
    val navController = rememberNavController()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()

    // Determine initial start destination based on whether accessibility service is enabled at launch
    val initialStartDestination = remember {
        if (viewModel.isAccessibilityEnabled.value) "home" else "accessibility_onboarding"
    }

    // Automatically transition to "home" once the accessibility service is enabled
    LaunchedEffect(isAccessibilityEnabled) {
        if (isAccessibilityEnabled) {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == "accessibility_onboarding") {
                navController.navigate("home") {
                    popUpTo("accessibility_onboarding") { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialStartDestination,
        modifier = Modifier.fillMaxSize()
    ) {
        composable("accessibility_onboarding") {
            AccessibilityOnboardingScreen(
                onOpenSettings = {
                    // Will re-check onResume
                }
            )
        }

        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToNewProfile = { navController.navigate("profile/0") },
                onNavigateToEditProfile = { profileId -> navController.navigate("profile/$profileId") }
            )
        }

        composable(
            route = "profile/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.LongType })
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: 0L
            EditProfileScreen(
                profileId = profileId,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
