package com.evsuite.profile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version comparison — pure logic, no network access or Android. */
class UpdateCheckerTest {

    @Test
    fun `higher remote patch is newer`() {
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4"))
    }

    @Test
    fun `higher remote minor is newer`() {
        assertTrue(UpdateChecker.isNewer("2.7.0", "2.6.9"))
    }

    @Test
    fun `higher remote major is newer`() {
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.99.99"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4"))
    }

    @Test
    fun `lower remote version is not newer`() {
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4"))
    }

    @Test
    fun `v prefix is ignored on both sides`() {
        assertTrue(UpdateChecker.isNewer("v2.6.5", "v2.6.4"))
        assertFalse(UpdateChecker.isNewer("v2.6.4", "2.6.4"))
    }

    @Test
    fun `segments manquants comptent comme zero`() {
        // "2.7" == "2.7.0": not an update.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.0"))
        // "2.7.1" > "2.7".
        assertTrue(UpdateChecker.isNewer("2.7.1", "2.7"))
        // "2.7" < "2.7.1": the missing segment has value zero.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.1"))
    }

    // ── Suffixes de build (T-907) ────────────────────────────────────────────

    @Test
    fun `flavor suffix is ignored`() {
        // The unstable channel is called "2.6.4.42-unstable". Suffixes remain ignored.
        // [2, 6] and therefore found ANY remote release more recent.
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.4-offline", "2.6.4"))
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4-offline"))
    }

    @Test
    fun `prerelease and build suffixes are ignored`() {
        assertFalse(UpdateChecker.isNewer("2.7.0-rc1", "2.7.0"))
        assertFalse(UpdateChecker.isNewer("2.7.0+build42", "2.7.0"))
        assertTrue(UpdateChecker.isNewer("2.7.1-rc1", "2.7.0"))
    }

    @Test
    fun `non-numeric segment is zero without shifting later segments`() {
        // "2.x.5" must equal [2, 0, 5] — especially not [2, 5], which would make
        // the patch for a minor.
        assertEquals(listOf(2, 0, 5), UpdateChecker.segments("2.x.5"))
        assertFalse(UpdateChecker.isNewer("2.x.5", "2.1.0"))
    }

    @Test
    fun `segments extracts the numeric core`() {
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("v2.6.4"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4-offline"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4+build9"))
    }

    @Test
    fun `unstable version comes from the asset name`() {
        assertEquals("2.7.0.42", UpdateChecker.versionFromAssetName(
            "EVProfile-unstable-2.7.0.42.apk"))
        assertEquals(null, UpdateChecker.versionFromAssetName("EVProfile-stable-2.7.0.apk"))
    }
}
