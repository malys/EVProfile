package com.evsuite.profile.update

import com.evsuite.hardware.AppLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * APK origin policy and signature comparison.
 *
 * Robolectric is only necessary because [ApkUrlPolicy] logs via
 * AppLogger(android.util.Log); the tested logic remains pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkSecurityTest {

    // ── Authorized origins ───────────────────────── ─────────────────────────

    @Test
    fun `https to an allowed host is accepted`() {
        assertTrue(ApkUrlPolicy.isAllowed(
            "https://github.com/malys/EVProfile/releases/download/unstable/EVProfile-unstable-2.7.0.42.apk"))
        assertTrue(ApkUrlPolicy.isAllowed(
            "https://objects.githubusercontent.com/github-production-release-asset/x.apk"))
    }

    @Test
    fun `http is refused even on an allowed host`() {
        assertFalse(ApkUrlPolicy.isAllowed("http://github.com/malys/EVProfile/app.apk"))
    }

    @Test
    fun `foreign host is refused`() {
        assertFalse(ApkUrlPolicy.isAllowed("https://evil.example.com/app.apk"))
    }

    @Test
    fun `host imitating an allowed host is refused`() {
        // Misleading suffix/prefix: the comparison is exact, not an endsWith.
        assertFalse(ApkUrlPolicy.isAllowed("https://github.com.attacker.net/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("https://evil-github.com/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("https://notgithub.com/app.apk"))
    }

    @Test
    fun `empty or invalid URL is refused`() {
        assertFalse(ApkUrlPolicy.isAllowed(""))
        assertFalse(ApkUrlPolicy.isAllowed("not a URL"))
        assertFalse(ApkUrlPolicy.isAllowed("ftp://github.com/app.apk"))
        assertFalse(ApkUrlPolicy.isAllowed("file:///sdcard/Download/app.apk"))
    }

    @Test
    fun `host case does not matter`() {
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
    fun `additional archive signature does not match`() {
        assertFalse(ApkSignatureVerifier.certsMatch(setOf("aa11", "bb22"), setOf("aa11")))
    }

    @Test
    fun `empty set never matches and fails closed`() {
        // Unreadable archive or failed API: refuse, never accept by default.
        assertFalse(ApkSignatureVerifier.certsMatch(emptySet(), setOf("aa11")))
        assertFalse(ApkSignatureVerifier.certsMatch(setOf("aa11"), emptySet()))
        assertFalse(ApkSignatureVerifier.certsMatch(emptySet(), emptySet()))
    }
}
