package com.evsuite.profile.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.evsuite.hardware.AppLogger
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * OTA channel security controls.
 *
 * The app runs in `uid.system`: an unverified original APK installed under this
 * identity compromises the vehicle. Two locks, both in “fail closed”:
 *   1. [ApkUrlPolicy] — where the APK has the right to come from.
 *   2. [ApkSignatureVerifier] — that the APK is signed by the same key as us.
 */

/** Authorized origins for an update APK. */
object ApkUrlPolicy {

    private const val TAG = "EV_UPDATE"

    /**
     * Authorized hosts. The two domains `githubusercontent.com` are the CDNs to
     * which github.com redirects the download of a release asset; without them
     * the redirect is refused and the update fails.
     */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    /**
     * True if [url] is https and points to an authorized host.
     *
     * Deny everything else: http (including a downgrade https -> http to
     * being redirected), unknown host, unparsable URL, and subdomains
     * not listed explicitly (`evil-github.com`, `github.com.attacker.net`).
     */
    fun isAllowed(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS
    }

    /** Like [isAllowed], but logs the denial — for entry points. */
    fun isAllowedLogged(url: String, where: String): Boolean {
        val ok = isAllowed(url)
        if (!ok) AppLogger.w(TAG, "$where: APK URL refused (origin not allowed): $url")
        return ok
    }
}

/** Verifies that an APK is signed by the same key as the running app. */
object ApkSignatureVerifier {

    private const val TAG = "EV_UPDATE"

    /**
     * Compare deux jeux d'empreintes de certificats.
     *
     * Fail closed: an empty game (illegible archive, missing signature, API which has
     * failed) never matches, even against another empty game.
     */
    fun certsMatch(archive: Set<String>, installed: Set<String>): Boolean =
        archive.isNotEmpty() && installed.isNotEmpty() && archive == installed

    /**
     * True if [apk] is signed by exactly the same key as the installed app.
     * Every error, including a corrupt archive or unavailable API, returns false.
     */
    fun matchesRunningApp(context: Context, apk: File): Boolean {
        val archive = fingerprintsOfArchive(context, apk)
        val installed = fingerprintsOfInstalled(context)
        val ok = certsMatch(archive, installed)
        if (!ok) {
            AppLogger.w(TAG, "APK signature mismatch — installation refused " +
                    "(archive=${archive.size} cert(s), installed=${installed.size} cert(s))")
        }
        return ok
    }

    /** SHA-256 fingerprints of the certificates signing the APK [apk] file. */
    private fun fingerprintsOfArchive(context: Context, apk: File): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else
            PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Unable to read archive signature: ${e.message}")
        emptySet()
    }

    /** SHA-256 fingerprints of the certificates signing the running app. */
    private fun fingerprintsOfInstalled(context: Context): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else
            PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        signatureDigests(info)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Lecture de notre propre signature impossible : ${e.message}")
        emptySet()
    }

    private fun signatureDigests(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig ->
            sig?.toByteArray()?.let { md.digest(it).joinToString("") { b -> "%02x".format(b) } }
        }.toSet()
    }
}
