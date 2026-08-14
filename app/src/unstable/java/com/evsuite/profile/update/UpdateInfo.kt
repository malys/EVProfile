package com.evsuite.profile.update

/**
 * Informations sur une release GitHub disponible.
 */
data class UpdateInfo(
    /** Numeric version parsed from the rolling pre-release APK asset name. */
    val versionName: String,
    /** Fixed rolling release tag: "unstable". */
    val tagName: String,
    /** URL de téléchargement directe du fichier .apk */
    val apkUrl: String,
    /** Corps de la release note (tronqué à 400 caractères) */
    val releaseNotes: String
)
