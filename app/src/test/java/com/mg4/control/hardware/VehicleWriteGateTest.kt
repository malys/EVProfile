package com.mg4.control.hardware

import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.VehicleWriteGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared write gate: standstill only, failing closed when speed is unreadable. */
class VehicleWriteGateTest {

    @Test
    fun `standstill is allowed`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f, allowUpToKmh = 0f))
    }

    @Test
    fun `movement is refused`() {
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(0.1f, allowUpToKmh = 0f))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(80f, allowUpToKmh = 0f))
    }

    @Test
    fun `unreadable speed is refused`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null, allowUpToKmh = 0f))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(Float.NaN, allowUpToKmh = 0f))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-3f, allowUpToKmh = 0f))
    }

    @Test
    fun `park never contradicts a readable moving speed`() {
        assertEquals(
            Decision.REFUSED_MOVING,
            VehicleWriteGate.decide(30f, allowUpToKmh = 0f, parked = true)
        )
    }

    @Test
    fun `park can confirm standstill when speed is unreadable`() {
        assertEquals(
            Decision.ALLOWED,
            VehicleWriteGate.decide(null, allowUpToKmh = 0f, parked = true)
        )
    }
}
