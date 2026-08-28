package com.evsuite.profile.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.evsuite.hardware.model.DrivingProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProfileManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ev_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_DEFAULT_ID = "default_profile_id"
        const val MAX_PROFILES = 5

        /**
         * Process lock, NOT instance: UI and service each build their own
         * ProfileManager on the same preferences file. Each mutation is a
         * read-modify-write to a JSON blob — without this lock, two backups
         * simultaneous lose one silently.
         */
        private val MUTATION_LOCK = Any()

        /** Single scope for background backups (only one per process). */
        private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val backupManager = ProfileBackupManager()

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    fun getAll(): List<DrivingProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DrivingProfile>>() {}.type
            val list: List<DrivingProfile> = gson.fromJson(json, type) ?: emptyList()
            // Migration: profiles created before adding AEB have aebMode=0 (default JVM value).
            // We initialize them to AEB activated + Alert+Brake mode by default.
            list.map { p ->
                if (p.aebMode == 0) p.copy(aebEnabled = true, aebMode = 2) else p
            }
        } catch (_: Exception) { emptyList() }
    }

    fun save(profile: DrivingProfile): Boolean {
        synchronized(MUTATION_LOCK) {
            val list = getAll().toMutableList()
            val existing = list.indexOfFirst { it.id == profile.id }
            if (existing >= 0) {
                list[existing] = profile
            } else {
                if (list.size >= MAX_PROFILES) return false
                list.add(profile)
            }
            persist(list)
        }
        triggerBackup()
        return true
    }

    fun delete(profileId: String) {
        synchronized(MUTATION_LOCK) {
            val list = getAll().toMutableList()
            list.removeAll { it.id == profileId }
            persist(list)
            // Clear default if it was this profile
            if (prefs.getString(KEY_DEFAULT_ID, null) == profileId) {
                prefs.edit().remove(KEY_DEFAULT_ID).apply()
            }
        }
        triggerBackup()
    }

    fun setDefault(profileId: String) {
        prefs.edit().putString(KEY_DEFAULT_ID, profileId).apply()
        triggerBackup()
    }

    fun clearDefault() {
        prefs.edit().remove(KEY_DEFAULT_ID).apply()
        triggerBackup()
    }

    fun getDefaultProfile(): DrivingProfile? {
        val defaultId = prefs.getString(KEY_DEFAULT_ID, null) ?: return null
        return getAll().firstOrNull { it.id == defaultId }
    }

    fun getDefaultId(): String? = prefs.getString(KEY_DEFAULT_ID, null)

    fun getById(id: String): DrivingProfile? = getAll().firstOrNull { it.id == id }

    /** Legacy lookup retained only so profiles saved by older releases remain readable. */
    fun getProfileForBtDevice(mac: String): DrivingProfile? =
        getAll().firstOrNull { it.btDeviceMac.equals(mac, ignoreCase = true) }

    // -------------------------------------------------------------------------
    // Backup/restore (car memory file — survives uninstallation)
    // -------------------------------------------------------------------------

    fun hasBackup(): Boolean = backupManager.backupExists()

    /** Reads the backup without modifying anything (to propose restoration). */
    fun readBackup() = backupManager.readBackup()

    /**
     * Restores profiles from a backup: replaces local profiles with those
     * of the backup (capped at [MAX_PROFILES]) and applies the default profile.
     * Returns the number of profiles restored.
     */
    fun restoreFrom(backup: com.evsuite.hardware.model.ProfileBackup): Int {
        val restored = backup.profiles.take(MAX_PROFILES)
        synchronized(MUTATION_LOCK) {
            persist(restored)
            val defId = backup.defaultId
            if (defId != null && restored.any { it.id == defId }) {
                prefs.edit().putString(KEY_DEFAULT_ID, defId).apply()
            } else {
                prefs.edit().remove(KEY_DEFAULT_ID).apply()
            }
        }
        triggerBackup()
        return restored.size
    }

    /** Rewrites the backup file in the background (current state of profiles). */
    private fun triggerBackup() {
        val snapshot = getAll()
        val defaultId = getDefaultId()
        backupScope.launch {
            backupManager.writeBackup(snapshot, defaultId)
        }
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private fun persist(list: List<DrivingProfile>) {
        // commit() and not apply(): the following mutation immediately rereads the blob,
        // an asynchronous write would reopen the update loss window.
        prefs.edit().putString(KEY_PROFILES, gson.toJson(list)).commit()
    }
}
