package com.evsuite.profile.update

import com.evsuite.hardware.AppLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Politique d'origine des APK et comparaison de signatures.
 *
 * Robolectric est nécessaire uniquement parce que [ApkUrlPolicy] journalise via
 * AppLogger (android.util.Log) ; la logique testée reste pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkSecurityTest {

    // ── Origines autorisées ──────────────────────────────────────────────────

    @Test
    fun `https vers un hote autorise est accepte`() {
        assertTrue(ApkUrlPolicy.isAllowed(
            "https://github.com/malys/EVProfile/releases/download/unstable/EVProfile-unstable-2.7.0.42.apk"))
        assertTrue(ApkUrlPolicy.isAllowed(
            "https://objects.githubusercontent.com/github-production-release-asset/x.apk"))
    }

    @Test
    fun `http est refuse meme sur un hote autorise`() {
        assertFalse(ApkUrlPolicy.isAllowed("http://github.com/malys/EVProfile/app.apk"))
    }

    @Test
    fun `hote etranger est refuse`() {
        assertFalse(ApkUrlPolicy.isAllowed("https://evil.example.com/app.apk"))
    }

    @Test
    fun `hote qui imite un hote autorise est refuse`() {
        // Suffixe / préfixe trompeurs : la comparaison est exacte, pas un endsWith.
        assertFalse(ApkUrlPolicy.isAllowed("https://github.com.attacker.net/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("https://evil-github.com/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("https://notgithub.com/app.apk"))
    }

    @Test
    fun `url vide ou non parsable est refusee`() {
        assertFalse(ApkUrlPolicy.isAllowed(""))
        assertFalse(ApkUrlPolicy.isAllowed("pas une url"))
        assertFalse(ApkUrlPolicy.isAllowed("ftp://github.com/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("file:///sdcard/Download/app.apk"))
    }

    @Test
    fun `la casse de l hote n a pas d importance`() {
        assertTrue(ApkUrlPolicy.isAllowed("https://GitHub.com/malys/EVProfile/app.apk"))
        assertTrue(ApkUrlPolicy.isAllowed("HTTPS://github.com/malys/EVProfile/app.apk"))
    }

    // ── Comparaison de signatures ────────────────────────────────────────────

    @Test
    fun `signatures identiques correspondent`() {
        assertTrue(ApkSignatureVerifier.certsMatch(setOf("aa11"), setOf("aa11")))
    }

    @Test
    fun `signatures differentes ne correspondent pas`() {
        assertFalse(ApkSignatureVerifier.certsMatch(setOf("aa11"), setOf("bb22")))
    }

    @Test
    fun `signature supplementaire dans l archive ne correspond pas`() {
        assertFalse(ApkSignatureVerifier.certsMatch(setOf("aa11", "bb22"), setOf("aa11")))
    }

    @Test
    fun `jeu vide ne correspond jamais - fail closed`() {
        // Archive illisible ou API en échec : refuser, jamais accepter par défaut.
        assertFalse(ApkSignatureVerifier.certsMatch(emptySet(), setOf("aa11")))
        assertFalse(ApkSignatureVerifier.certsMatch(setOf("aa11"), emptySet()))
        assertFalse(ApkSignatureVerifier.certsMatch(emptySet(), emptySet()))
    }
}
