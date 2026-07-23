package com.mg4.control.hardware

import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.VehicleWriteGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verrou d'écriture configurable : OFF => tout passe ; ON => autorisé jusqu'à maxKmh
 * inclus, refus au-dessus, refus si vitesse illisible (fail closed). Logique pure.
 */
class VehicleWriteGateTest {

    // ── Désactivé : jamais de blocage ─────────────────────────────────────────
    @Test
    fun `off autorise quelle que soit la vitesse`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f, enabled = false, maxKmh = 0))
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(200f, enabled = false, maxKmh = 50))
    }

    @Test
    fun `off autorise meme si vitesse illisible`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(null, enabled = false, maxKmh = 50))
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(Float.NaN, enabled = false, maxKmh = 50))
    }

    // ── Activé, seuil 50 : borne inclusive ────────────────────────────────────
    @Test
    fun `on sous ou egal au seuil autorise`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f, enabled = true, maxKmh = 50))
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(49f, enabled = true, maxKmh = 50))
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(50f, enabled = true, maxKmh = 50))
    }

    @Test
    fun `on au dessus du seuil refuse`() {
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(50.1f, enabled = true, maxKmh = 50))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(80f, enabled = true, maxKmh = 50))
    }

    // ── Activé, seuil 0 : comportement d'origine (arrêt seulement) ────────────
    @Test
    fun `on seuil zero equivaut a arret seulement`() {
        assertEquals(Decision.ALLOWED, VehicleWriteGate.decide(0f, enabled = true, maxKmh = 0))
        assertEquals(Decision.REFUSED_MOVING, VehicleWriteGate.decide(1f, enabled = true, maxKmh = 0))
    }

    // ── Activé, vitesse illisible : fail closed ───────────────────────────────
    @Test
    fun `on vitesse illisible refuse`() {
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null, enabled = true, maxKmh = 50))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(Float.NaN, enabled = true, maxKmh = 50))
        assertEquals(Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-3f, enabled = true, maxKmh = 50))
    }

    // ── Clamp de la saisie utilisateur ────────────────────────────────────────
    @Test
    fun `clampSpeed borne entre 0 et 250`() {
        assertEquals(0, VehicleWriteGate.clampSpeed(null))
        assertEquals(0, VehicleWriteGate.clampSpeed(-10))
        assertEquals(0, VehicleWriteGate.clampSpeed(0))
        assertEquals(50, VehicleWriteGate.clampSpeed(50))
        assertEquals(250, VehicleWriteGate.clampSpeed(250))
        assertEquals(250, VehicleWriteGate.clampSpeed(999))
    }
}
