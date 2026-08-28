package com.evsuite.profile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evsuite.hardware.AppLogger
import com.evsuite.profile.service.EVProfileService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EV_BOOT"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        AppLogger.i(TAG, "onReceive: $action — starting EVProfileService")

        // No delayed retry here: startForegroundService returns normally within
        // failure cases that count, so the old restarts at 3 s and 8 s do not
        // would never trigger — and a postDelayed would survive the duration anyway
        // legitimate life of the receiver. The service restarts alone (START_STICKY).
        val started = tryStart(context, Intent(context, EVProfileService::class.java))
        AppLogger.i(TAG, "startForegroundService → $started")
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "startForegroundService error: ${e.message}")
            try { context.startService(intent); true } catch (_: Exception) { false }
        }
    }
}
