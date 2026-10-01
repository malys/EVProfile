package com.evsuite.profile.profile

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.model.ProfileClimate
import com.evsuite.profile.profile.ClimatePlan.Control
import com.evsuite.profile.profile.ClimatePlan.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimatePlanTest {

    private fun controls(c: ProfileClimate) = ClimatePlan.steps(c).map { it.control }

    @Test fun `off writes power off and nothing else`() {
        val c = ProfileClimate(powerOn = false, autoOn = true, tempCelsius = 22, frontDefrost = true)
        assertEquals(listOf(Step(Control.POWER, 0)), ClimatePlan.steps(c))
    }

    @Test fun `fan is never written in AUTO or when AUTO is unchanged`() {
        assertFalse(Control.FAN in controls(ProfileClimate(autoOn = true, fanLevel = 5)))
        assertFalse(Control.FAN in controls(ProfileClimate(autoOn = null, fanLevel = 5)))
        assertTrue(Control.FAN in controls(ProfileClimate(autoOn = false, fanLevel = 5)))
    }

    @Test fun `unchanged controls are not written`() {
        assertEquals(listOf(Control.POWER), controls(ProfileClimate()))
    }

    @Test fun `full manual profile writes in order with defrost last`() {
        val c = ProfileClimate(
            powerOn = true, autoOn = false, acOn = true, tempCelsius = 20, fanLevel = 4,
            recirculation = false, frontDefrost = true, rearDefrost = false
        )
        assertEquals(
            listOf(
                Step(Control.POWER, 1), Step(Control.AUTO, 0), Step(Control.AC, 1),
                Step(Control.TEMP, 20), Step(Control.FAN, 4), Step(Control.RECIRCULATION, 0),
                Step(Control.FRONT_DEFROST, 1), Step(Control.REAR_DEFROST, 0)
            ),
            ClimatePlan.steps(c)
        )
    }

    @Test fun `setpoint and fan are clamped`() {
        val steps = ClimatePlan.steps(ProfileClimate(autoOn = false, tempCelsius = 5, fanLevel = 99))
        assertEquals(17, steps.first { it.control == Control.TEMP }.value)
        assertEquals(ClimatePlan.FAN_MAX, steps.first { it.control == Control.FAN }.value)
    }

    @Test fun `climate is offered only where the catalogue has a write path`() {
        assertTrue(ClimatePlan.isSupported(FirmwareInfo.Gen.SWI68))
        assertTrue(ClimatePlan.isSupported(FirmwareInfo.Gen.SWI132))
        assertFalse(ClimatePlan.isSupported(FirmwareInfo.Gen.SWI133))
        assertFalse(ClimatePlan.isSupported(FirmwareInfo.Gen.UNKNOWN))
    }
}
