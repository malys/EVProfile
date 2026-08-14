package com.evsuite.profile.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.evsuite.hardware.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the single rolling GitHub pre-release used by the unstable channel. */
object UpdateChecker {
    private const val TAG = "EV_UPDATE"
    private const val RELEASE_API =
        "https://api.github.com/repos/malys/EVProfile/releases/tags/unstable"
    private const val PREFS_SKIP = "ev_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    private data class RawRelease(
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    fun check(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: return@launch
                val skipped = context.getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
                    .getString(KEY_SKIP_VERSION, null)
                val release = fetchRollingRelease() ?: run {
                    withContext(Dispatchers.Main) { onError?.invoke() }
                    return@launch
                }
                when {
                    !isNewer(release.versionName, current) ->
                        withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                    release.versionName == skipped ->
                        withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                    else -> withContext(Dispatchers.Main) {
                        onUpdateAvailable(
                            UpdateInfo(
                                release.versionName,
                                "unstable",
                                release.apkUrl,
                                release.releaseNotes
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Unstable update check failed: ${e.message}")
                withContext(Dispatchers.Main) { onError?.invoke() }
            }
        }
    }

    private fun fetchRollingRelease(): RawRelease? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "EVProfile-Android")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (connection.responseCode != 200) {
                AppLogger.w(TAG, "Rolling release API returned ${connection.responseCode}")
                return null
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("prerelease", false)) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", true) || !name.contains("unstable", true)) continue
                val version = versionFromAssetName(name) ?: continue
                val url = asset.optString("browser_download_url")
                if (!ApkUrlPolicy.isAllowedLogged(url, "GitHub release")) continue
                return RawRelease(version, url, json.optString("body", "").take(400))
            }
            AppLogger.w(TAG, "Rolling release contains no versioned unstable APK")
            null
        } catch (e: Exception) {
            AppLogger.w(TAG, "Rolling release unavailable: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    @VisibleForTesting
    internal fun versionFromAssetName(assetName: String): String? {
        if (!assetName.contains("-unstable-", ignoreCase = true)) return null
        return Regex("-(\\d[0-9.]*?)\\.apk$", RegexOption.IGNORE_CASE)
            .find(assetName)?.groupValues?.get(1)
    }

    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP_VERSION, version).apply()
    }

    @VisibleForTesting
    internal fun isNewer(remote: String, current: String): Boolean {
        val remoteSegments = segments(remote)
        val currentSegments = segments(current)
        for (index in 0 until maxOf(remoteSegments.size, currentSegments.size)) {
            val remotePart = remoteSegments.getOrElse(index) { 0 }
            val currentPart = currentSegments.getOrElse(index) { 0 }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }
        return false
    }

    @VisibleForTesting
    internal fun segments(version: String): List<Int> =
        version.trimStart('v', 'V')
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
