package com.evsuite.profile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparaison de versions — logique pure, aucun accès réseau ni Android. */
class UpdateCheckerTest {

    @Test
    fun `remote patch superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4"))
    }

    @Test
    fun `remote minor superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("2.7.0", "2.6.9"))
    }

    @Test
    fun `remote major superieur est plus recent`() {
        assertTrue(UpdateChecker.isNewer("3.0.0", "2.99.99"))
    }

    @Test
    fun `versions egales ne sont pas plus recentes`() {
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4"))
    }

    @Test
    fun `remote inferieur n est pas plus recent`() {
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4"))
    }

    @Test
    fun `prefixe v est ignore des deux cotes`() {
        assertTrue(UpdateChecker.isNewer("v2.6.5", "v2.6.4"))
        assertFalse(UpdateChecker.isNewer("v2.6.4", "2.6.4"))
    }

    @Test
    fun `segments manquants comptent comme zero`() {
        // "2.7" == "2.7.0" : pas une mise à jour.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.0"))
        // "2.7.1" > "2.7".
        assertTrue(UpdateChecker.isNewer("2.7.1", "2.7"))
        // "2.7" < "2.7.1" : le segment absent vaut 0.
        assertFalse(UpdateChecker.isNewer("2.7", "2.7.1"))
    }

    // ── Suffixes de build (T-907) ────────────────────────────────────────────

    @Test
    fun `le suffixe de flavor est ignore`() {
        // Le canal unstable s'appelle "2.6.4.42-unstable". Les suffixes restent ignorés.
        // [2, 6] et trouvait donc TOUTE release distante plus récente.
        assertFalse(UpdateChecker.isNewer("2.6.4", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.4-offline", "2.6.4"))
        assertTrue(UpdateChecker.isNewer("2.6.5", "2.6.4-offline"))
        assertFalse(UpdateChecker.isNewer("2.6.3", "2.6.4-offline"))
    }

    @Test
    fun `les suffixes de pre-release et de build sont ignores`() {
        assertFalse(UpdateChecker.isNewer("2.7.0-rc1", "2.7.0"))
        assertFalse(UpdateChecker.isNewer("2.7.0+build42", "2.7.0"))
        assertTrue(UpdateChecker.isNewer("2.7.1-rc1", "2.7.0"))
    }

    @Test
    fun `un segment non numerique vaut zero sans decaler les suivants`() {
        // "2.x.5" doit valoir [2, 0, 5] — surtout pas [2, 5], qui ferait passer
        // le patch pour un minor.
        assertEquals(listOf(2, 0, 5), UpdateChecker.segments("2.x.5"))
        assertFalse(UpdateChecker.isNewer("2.x.5", "2.1.0"))
    }

    @Test
    fun `segments extrait le coeur numerique`() {
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("v2.6.4"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4-offline"))
        assertEquals(listOf(2, 6, 4), UpdateChecker.segments("2.6.4+build9"))
    }

    @Test
    fun `la version unstable vient du nom de l asset`() {
        assertEquals("2.7.0.42", UpdateChecker.versionFromAssetName(
            "EVProfile-unstable-2.7.0.42.apk"))
        assertEquals(null, UpdateChecker.versionFromAssetName("EVProfile-stable-2.7.0.apk"))
    }
}
