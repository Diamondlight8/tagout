package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.blocker.BlockManager
import com.example.data.db.TagOutDatabase
import com.example.data.model.BlockingProfile
import com.example.data.model.NfcTag
import com.example.util.AccessibilityHelper
import com.example.util.BackgroundNfcManager
import com.example.util.BatteryOptimizationHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        BlockManager.clear()
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("TagOut", appName)
    }

    @Test
    fun `BlockManager updates active profiles and detects blocked package`() {
        val profile = BlockingProfile(
            id = 1L,
            name = "Deep Focus",
            blockedPackages = listOf("com.instagram.android", "com.twitter.android"),
            lockTagIds = listOf("tag1"),
            unlockTagIds = listOf("tag2"),
            isActive = true
        )

        BlockManager.updateActiveProfiles(listOf(profile))

        assertTrue(BlockManager.isPackageBlocked("com.instagram.android"))
        assertTrue(BlockManager.isPackageBlocked("com.twitter.android"))
        assertFalse(BlockManager.isPackageBlocked("com.example.other"))
        assertEquals("Deep Focus", BlockManager.getBlockingProfileName("com.instagram.android"))
        assertEquals(2, BlockManager.activeBlockedPackagesCount.value)

        // Deactivate profile
        BlockManager.updateActiveProfiles(listOf(profile.copy(isActive = false)))
        assertFalse(BlockManager.isPackageBlocked("com.instagram.android"))
        assertNull(BlockManager.getBlockingProfileName("com.instagram.android"))
        assertEquals(0, BlockManager.activeBlockedPackagesCount.value)
    }

    @Test
    fun `AccessibilityHelper checks service state safely without throwing`() {
        // Initial state in Robolectric environment
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(context)
        // Verify call executes safely and returns boolean
        assertFalse(isEnabled)
    }

    @Test
    fun `BatteryOptimizationHelper checks optimization state safely without throwing`() {
        val isIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        // In clean test environment, app starts not ignoring battery optimizations
        assertFalse(isIgnored)
    }

    @Test
    fun `BatteryOptimizationHelper promptDisableBatteryOptimizations launches intent`() {
        BatteryOptimizationHelper.promptDisableBatteryOptimizations(context)
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val nextIntent = shadowApp.nextStartedActivity
        assertNotNull("Battery optimization intent should have been launched", nextIntent)
    }

    @Test
    fun `BackgroundNfcManager activates and deactivates profile without opening UI`() = runBlocking {
        val db = TagOutDatabase.getInstance(context)
        val tagDao = db.nfcTagDao()
        val profileDao = db.blockingProfileDao()

        // Insert tag and profile
        tagDao.insertTag(NfcTag(id = "desk_tag_test", name = "Desk Tag"))
        val profileId = profileDao.insertProfile(
            BlockingProfile(
                name = "Study Mode",
                blockedPackages = listOf("com.instagram.android"),
                lockTagIds = listOf("desk_tag_test"),
                unlockTagIds = listOf("desk_tag_test"),
                isActive = false
            )
        )

        // Simulate background tap on lock/unlock tag
        val resultLock = BackgroundNfcManager.processScannedTag(context, "desk_tag_test")
        assertNotNull(resultLock)
        assertTrue(resultLock!!.contains("locked", ignoreCase = true))

        // BlockManager should immediately have the blocked package
        assertTrue(BlockManager.isPackageBlocked("com.instagram.android"))

        // Simulate second background tap (dual tag toggles to inactive)
        val resultUnlock = BackgroundNfcManager.processScannedTag(context, "desk_tag_test")
        assertNotNull(resultUnlock)
        assertTrue(resultUnlock!!.contains("unlocked", ignoreCase = true))

        // BlockManager should no longer have the blocked package
        assertFalse(BlockManager.isPackageBlocked("com.instagram.android"))

        // Unrecognised tag should finish silently with null
        val resultUnrecognised = BackgroundNfcManager.processScannedTag(context, "unknown_tag_id")
        assertNull(resultUnrecognised)
    }

    @Test
    fun `NfcHandlerActivity finishes immediately and processes tag silently`() {
        val intent = android.content.Intent(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED).apply {
            putExtra(android.nfc.NfcAdapter.EXTRA_ID, byteArrayOf(0x04, 0x12, 0x34, 0x56))
        }
        val controller = org.robolectric.Robolectric.buildActivity(
            com.example.service.NfcHandlerActivity::class.java,
            intent
        )
        val activity = controller.create().get()
        assertTrue("NfcHandlerActivity must finish immediately", activity.isFinishing)
    }

    @Test
    fun `BootReceiver primes active profiles into BlockManager on boot completed`() = runBlocking {
        val db = TagOutDatabase.getInstance(context)
        val profileDao = db.blockingProfileDao()
        profileDao.insertProfile(
            BlockingProfile(
                name = "Boot Test Profile",
                blockedPackages = listOf("com.distracting.app"),
                lockTagIds = listOf("tag1"),
                unlockTagIds = listOf("tag2"),
                isActive = true
            )
        )

        val bootReceiver = com.example.receiver.BootReceiver()
        val bootIntent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)
        bootReceiver.onReceive(context, bootIntent)

        // Give coroutine brief moment
        kotlinx.coroutines.delay(100)

        assertTrue(BlockManager.isPackageBlocked("com.distracting.app"))
    }
}
