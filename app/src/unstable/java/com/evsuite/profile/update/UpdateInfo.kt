package com.evsuite.profile.update

/**
 * Information about an available GitHub release.
 */
data class UpdateInfo(
    /** Numeric version parsed from the rolling pre-release APK asset name. */
    val versionName: String,
    /** Fixed rolling release tag: "unstable". */
    val tagName: String,
    /** Direct download URL of .apk file */
    val apkUrl: String,
    /** Body of the release note (truncated to 400 characters) */
    val releaseNotes: String
)
