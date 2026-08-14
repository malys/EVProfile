package com.evsuite.profile.hardware

import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.VehicleWriteGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shared write gate: standstill only, failing closed when speed is unreadable. */
class VehicleWriteGateTest {

    @Test
    fun `standstill is allowed`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f))
    }

    @Test
    fun `movement is refused`() {
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(0.1f))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(80f))
    }

    @Test
    fun `unreadable speed is refused`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(Float.NaN))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-3f))
    }
}
