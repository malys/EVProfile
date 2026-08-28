package com.evsuite.profile.profile

import androidx.test.core.app.ApplicationProvider
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.hardware.model.RegenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-903] The steering wheel button, Bluetooth connection and switching to READY can change the
 * profiles at the same time, from the UI and from the service — each with its own instance of
 * ProfileManager. These tests fail if serialization skips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileConcurrencyTest {

    private fun profile(name: String) = DrivingProfile(
        name = name,
        driveMode = DriveMode.NORMAL,
        regenLevel = RegenLevel.MEDIUM,
        aebMode = 2
    )

    /** Runs [count] threads in parallel and waits until they have all finished. */
    private fun runConcurrently(count: Int, block: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        repeat(count) { i ->
            Thread {
                start.await()
                try { block(i) } finally { done.countDown() }
            }.start()
        }
        start.countDown()   // simultaneous departure
        assertTrue("threads are blocked", done.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun `concurrent saves do not lose profiles`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // One instance per thread: this is the real situation (UI + service).
        val profiles = (0 until ProfileManager.MAX_PROFILES).map { profile("P$it") }

        runConcurrently(profiles.size) { i ->
            ProfileManager(context).save(profiles[i])
        }

        val saved = ProfileManager(context).getAll()
        assertEquals("a saved profile was lost", profiles.size, saved.size)
        profiles.forEach { p ->
            assertNotNull("profile ${p.name} missing", saved.firstOrNull { it.id == p.id })
        }
    }

    @Test
    fun `concurrent updates of one profile retain one entry`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = profile("Base")
        ProfileManager(context).save(base)

        runConcurrently(8) { i ->
            ProfileManager(context).save(base.copy(name = "Rename$i"))
        }

        val all = ProfileManager(context).getAll()
        assertEquals("the concurrent update duplicated the profile", 1, all.size)
        assertTrue("inconsistent name: ${all[0].name}", all[0].name.startsWith("Rename"))
    }

    @Test
    fun `concurrent file saves never corrupt the backup`() {
        val manager = ProfileBackupManager()
        val profiles = (0 until 5).map { profile("P$it") }

        runConcurrently(12) { i ->
            manager.writeBackup(profiles.take((i % 5) + 1), profiles[0].id)
        }

        // The final file must be readable and consistent — never a truncated JSON
        // or the interleaving of two writes.
        val backup = manager.readBackup()
        assertNotNull("backup unreadable after concurrent writes", backup)
        assertTrue(backup!!.profiles.isNotEmpty())
        assertEquals(profiles[0].id, backup.defaultId)
    }

    @Test
    fun `concurrent reads always see a complete backup`() {
        val manager = ProfileBackupManager()
        val profiles = (0 until 5).map { profile("P$it") }
        manager.writeBackup(profiles, profiles[0].id)

        // Interleaved writes and reads: replacement is done without windows
        // during which the file is missing or partial.
        runConcurrently(12) { i ->
            if (i % 2 == 0) {
                manager.writeBackup(profiles, profiles[0].id)
            } else {
                assertNotNull("backup missing during rewrite", manager.readBackup())
            }
        }
    }
}
