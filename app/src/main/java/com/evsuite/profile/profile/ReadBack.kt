package com.evsuite.profile.profile

import android.os.Looper
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.CustomDrive
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.saic.SaicClimate

/**
 * Read-back lines for the owner's parked tests (RI-006, CR-040, CR-041): the car's state just
 * before and after a write, so the log carries a read/write/read triple per step.
 *
 * Reads only. Nothing here writes, waits, or throws: a failed or null read is a word in the
 * line, and a call on the main thread logs that it skipped instead of blocking on the binder.
 */
object ReadBack {

    /** RI-006. EVHardware publishes no one-pedal reader, and [EVHardware.getRegenLevel] maps an
     *  unknown code to MEDIUM — so this is the mapped level, not the raw int. */
    fun regen(where: String, phase: String) = line("EV_HW", where, phase) {
        "regen=${show { EVHardware.getRegenLevel()?.let { "${it.name}(${it.value})" } }} onePedal=no reader"
    }

    /** CR-040. [CustomDrive.read] answers the position index; null is unreadable or off-scale. */
    fun custom(where: String, phase: String, settings: List<CustomDrive.Setting> = CustomDrive.Setting.values().toList()) =
        line("EV_CUSTOM", where, phase) { settings.joinToString(" ") { "$it=${show { CustomDrive.read(it) }}" } }

    /** CR-041. Every climate reader the plan writes. */
    fun climate(phase: String) = line("EV_PROFILE", "Climate", phase) {
        "power=${show { SaicClimate.powerOn() }} auto=${show { SaicClimate.autoOn() }} " +
            "ac=${show { SaicClimate.acOn() }} temp=${show { SaicClimate.driverTemp() }} " +
            "fan=${show { SaicClimate.fanLevel() }} recirc=${show { SaicClimate.recirculationOn() }} " +
            "frontDefrost=${show { SaicClimate.frontDefrostOn() }} rearDefrost=${show { SaicClimate.rearDefrostOn() }}"
    }

    /** [read]'s value as text; null and exceptions become words, never a throw. */
    internal fun show(read: () -> Any?): String =
        try { read()?.toString() ?: "unreadable" } catch (e: Exception) { "exc(${e.javaClass.simpleName})" }

    private fun line(tag: String, where: String, phase: String, body: () -> String) {
        try {
            val text = if (Looper.myLooper() == Looper.getMainLooper()) "skipped — main thread" else body()
            AppLogger.i(tag, "  [READBACK] $where $phase: $text")
        } catch (_: Exception) {}
    }
}
