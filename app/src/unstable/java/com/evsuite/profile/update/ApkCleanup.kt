package com.evsuite.profile.update

import android.os.Environment
import android.util.Log

/**
 * Automatically cleans the app's APKs in the Downloads folder.
 * Only keeps the most recent [MAX_APK].
 */
object ApkCleanup {

    private const val TAG     = "ApkCleanup"
    private const val MAX_APK = 5

    /**
     * True if [name] is an APK of EVProfile in Downloads.
     * Covers the current `EVProfile-<flavor>-<version>.apk` name and legacy `MGControl<version>.apk`.
     * (to also clean downloads already present before the name change).
     */
    fun isAppApk(name: String): Boolean =
        (name.startsWith("EVProfile", ignoreCase = true) ||
         name.startsWith("MGControl", ignoreCase = true)) &&
        name.endsWith(".apk", ignoreCase = true)

    fun cleanIfNeeded() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFiles = downloadsDir.listFiles { f ->
            f.isFile && isAppApk(f.name)
        } ?: return

        if (apkFiles.size <= MAX_APK) return

        val toDelete = apkFiles.sortedBy { it.lastModified() }.take(apkFiles.size - MAX_APK)
        toDelete.forEach { file ->
            if (file.delete()) {
                Log.i(TAG, "Deleted: ${file.name}")
            } else {
                Log.w(TAG, "Delete failed: ${file.name}")
            }
        }
        Log.i(TAG, "${toDelete.size} APK(s) deleted — ${MAX_APK} retained")
    }
}
