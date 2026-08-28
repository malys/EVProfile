package com.evsuite.profile.profile

import androidx.test.core.app.ApplicationProvider
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.hardware.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileManagerTest {

    private lateinit var manager: ProfileManager

    private fun profile(name: String, aebMode: Int = 2) = DrivingProfile(
        name = name,
        driveMode = DriveMode.NORMAL,
        regenLevel = RegenLevel.MEDIUM,
        aebMode = aebMode
    )

    @Before
    fun setUp() {
        manager = ProfileManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `list is empty at startup`() {
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `save then getById returns the profile`() {
        val p = profile("Ville")
        assertTrue(manager.save(p))
        assertEquals("Ville", manager.getById(p.id)?.name)
    }

    @Test
    fun `save updates an existing id instead of adding one`() {
        val p = profile("Ville")
        manager.save(p)
        manager.save(p.copy(name = "Autoroute"))
        assertEquals(1, manager.getAll().size)
        assertEquals("Autoroute", manager.getById(p.id)?.name)
    }

    @Test
    fun `save refuses beyond MAX_PROFILES`() {
        repeat(ProfileManager.MAX_PROFILES) { assertTrue(manager.save(profile("P$it"))) }
        assertFalse(manager.save(profile("overflow")))
        assertEquals(ProfileManager.MAX_PROFILES, manager.getAll().size)
    }

    @Test
    fun `delete removes the profile`() {
        val p = profile("Ville")
        manager.save(p)
        manager.delete(p.id)
        assertTrue(manager.getAll().isEmpty())
    }

    @Test
    fun `deleting the default profile clears the default`() {
        val p = profile("Ville")
        manager.save(p)
        manager.setDefault(p.id)
        manager.delete(p.id)
        assertNull(manager.getDefaultId())
        assertNull(manager.getDefaultProfile())
    }

    @Test
    fun `migration of zero aebMode enables AEB with alert and braking`() {
        // Profile created before adding AEB: aebMode=0 (JVM default), aebEnabled=false.
        manager.save(profile("Legacy", aebMode = 0).copy(aebEnabled = false))
        val migrated = manager.getAll().single()
        assertTrue(migrated.aebEnabled)
        assertEquals(2, migrated.aebMode)
    }

    @Test
    fun `migration preserves profiles already configured`() {
        manager.save(profile("Moderne", aebMode = 1).copy(aebEnabled = false))
        val kept = manager.getAll().single()
        assertFalse(kept.aebEnabled)
        assertEquals(1, kept.aebMode)
    }

    @Test
    fun `getProfileForBtDevice ignores MAC case`() {
        manager.save(profile("BT").copy(btDeviceMac = "AA:BB:CC:DD:EE:FF"))
        assertEquals("BT", manager.getProfileForBtDevice("aa:bb:cc:dd:ee:ff")?.name)
        assertNull(manager.getProfileForBtDevice("11:22:33:44:55:66"))
    }
}
