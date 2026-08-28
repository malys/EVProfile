package com.evsuite.profile.profile

import android.os.Environment
import com.google.gson.Gson
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.model.DrivingProfile
import com.evsuite.hardware.model.ProfileBackup
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reads/writes the profile backup file — and only it.
 *
 * The file lives in a SHARED folder in the car which survives uninstallation
 * of the app (the private storage of the app is deleted). The app being signed platform
 * (`uid.system`), it writes directly there.
 */
class ProfileBackupManager {

    companion object {
        private const val TAG = "EV_PROFILE_BACKUP"
        private const val DIR_NAME  = "EVProfile"
        private const val FILE_NAME = "ev_profiles_backup.json"
    }

    private val gson = Gson()

    private val backupDir: File
        get() = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    private val backupFile: File
        get() = File(backupDir, FILE_NAME)

    /** True if a non-empty save file is present. */
    fun backupExists(): Boolean = backupFile.isFile && backupFile.length() > 0

    /**
     * Writes the backup ATOMICALLY (temporary file then rename), in order to
     * that an interrupted write never leaves a file corrupted. Never blocking:
     * any errors (unwritable storage, etc.) are logged and ignored.
     */
    fun writeBackup(profiles: List<DrivingProfile>, defaultId: String?): Boolean {
        return try {
            if (!backupDir.exists()) backupDir.mkdirs()
            val payload = ProfileBackup(
                schemaVersion = ProfileBackup.CURRENT_SCHEMA_VERSION,
                defaultId = defaultId,
                profiles = profiles
            )
            val json = gson.toJson(payload)
            // SINGLE temporary file: two concurrent backups were writing the same
            // ".tmp" and interleaved their content.
            val tmp = File.createTempFile(FILE_NAME, ".tmp", backupDir)
            tmp.writeText(json)
            // Atomic replacement WITHOUT prior delete(): the old backup remains
            // readable up to the second when the news takes its place.
            try {
                Files.move(tmp.toPath(), backupFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                // File systems without atomic move: copy then delete the tmp.
                tmp.copyTo(backupFile, overwrite = true)
                tmp.delete()
            }
            AppLogger.i(TAG, "writeBackup → ${profiles.size} profile(s) @ ${backupFile.absolutePath}")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "writeBackup failed (${backupFile.absolutePath}): ${e.message}")
            false
        }
    }

    /** Reads the backup, or null if missing/unreadable/corrupt. */
    fun readBackup(): ProfileBackup? {
        return try {
            if (!backupExists()) return null
            val json = backupFile.readText()
            gson.fromJson(json, ProfileBackup::class.java)
        } catch (e: Exception) {
            AppLogger.w(TAG, "readBackup illisible/corrompu: ${e.message}")
            null
        }
    }
}
