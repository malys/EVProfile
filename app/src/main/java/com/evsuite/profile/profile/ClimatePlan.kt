package com.evsuite.profile.profile

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.VehicleEnums
import com.evsuite.hardware.model.ProfileClimate
import com.evsuite.hardware.saic.SaicClimate

/**
 * The ordered climate writes a profile performs (CR-041). Pure, so the three rules below are
 * testable without a car:
 *
 *  - **Off writes nothing else.** A setpoint sent to an idle unit can switch it back on.
 *  - **Fan only in manual.** Setting a fan speed takes the unit out of AUTO, so the fan is
 *    written only when the profile asks for AUTO off — never when AUTO is on or "unchanged".
 *  - **Null is unchanged.** A control the profile leaves null is not written at all.
 *
 * Defrost goes last: it can move the fan and air source itself, and the profile's own value
 * for those must not be what the car overrides a moment later.
 */
object ClimatePlan {

    enum class Control { POWER, AUTO, AC, TEMP, FAN, RECIRCULATION, FRONT_DEFROST, REAR_DEFROST }

    /** One write; booleans travel as 1/0. */
    data class Step(val control: Control, val value: Int)

    fun steps(c: ProfileClimate): List<Step> {
        if (!c.powerOn) return listOf(Step(Control.POWER, 0))
        val out = mutableListOf(Step(Control.POWER, 1))
        fun add(control: Control, v: Int?) { if (v != null) out += Step(control, v) }
        fun flag(b: Boolean?) = b?.let { if (it) 1 else 0 }
        add(Control.AUTO, flag(c.autoOn))
        add(Control.AC, flag(c.acOn))
        add(Control.TEMP, c.tempCelsius?.coerceIn(SaicClimate.TEMP_MIN, SaicClimate.TEMP_MAX))
        if (c.autoOn == false) add(Control.FAN, c.fanLevel?.coerceIn(FAN_MIN, FAN_MAX))
        add(Control.RECIRCULATION, flag(c.recirculation))
        add(Control.FRONT_DEFROST, flag(c.frontDefrost))
        add(Control.REAR_DEFROST, flag(c.rearDefrost))
        return out
    }

    /** Whether this firmware has a climate write path at all (SWI133 has none). */
    fun isSupported(gen: FirmwareInfo.Gen = FirmwareInfo.getGeneration()): Boolean =
        gen != FirmwareInfo.Gen.UNKNOWN &&
            FirmwareSupport.isSupported(ActionType.SET_CLIMATE_POWER, FirmwareSupport.parse(gen.name))

    const val FAN_MIN = 1
    const val FAN_MAX = VehicleEnums.FAN_LEVEL_MAX

    fun write(step: Step): Boolean {
        val on = step.value != 0
        return when (step.control) {
            Control.POWER -> SaicClimate.setPower(on)
            Control.AUTO -> SaicClimate.setAuto(on)
            Control.AC -> SaicClimate.setAc(on)
            Control.TEMP -> SaicClimate.setDriverTemp(step.value)
            Control.FAN -> SaicClimate.setFanLevel(step.value)
            Control.RECIRCULATION -> SaicClimate.setRecirculation(on)
            Control.FRONT_DEFROST -> SaicClimate.setFrontDefrost(on)
            Control.REAR_DEFROST -> SaicClimate.setRearDefrost(on)
        }
    }
}
