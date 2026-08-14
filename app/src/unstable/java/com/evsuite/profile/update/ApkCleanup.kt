package com.evsuite.profile.update

import android.os.Environment
import android.util.Log

/**
 * Nettoie automatiquement les APK de l'app dans le dossier Téléchargements.
 * Ne conserve que les [MAX_APK] plus récents.
 */
object ApkCleanup {

    private const val TAG     = "ApkCleanup"
    private const val MAX_APK = 5

    /**
     * Vrai si [name] est un APK de EVProfile dans les Téléchargements.
     * Couvre le naming actuel `EVProfile-<flavor>-<version>.apk` ET l'ancien `MGControl<version>.apk`
     * (pour nettoyer aussi les téléchargements déjà présents avant le changement de nom).
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
                Log.i(TAG, "Supprimé : ${file.name}")
            } else {
                Log.w(TAG, "Échec suppression : ${file.name}")
            }
        }
        Log.i(TAG, "${toDelete.size} APK(s) supprimé(s) — ${MAX_APK} conservé(s)")
    }
}
