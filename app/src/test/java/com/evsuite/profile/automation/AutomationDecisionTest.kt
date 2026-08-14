package com.evsuite.profile.automation

import com.evsuite.profile.automation.AutomationDecision.Outcome
import com.evsuite.profile.automation.AutomationSettings.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationDecisionTest {

    @Test fun `desactive - non applicable`() {
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(false, 30f, 25, Direction.BELOW, true))
    }

    @Test fun `temp illisible - non applicable`() {
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(true, null, 25, Direction.BELOW, true))
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(true, Float.NaN, 25, Direction.ABOVE, true))
    }

    @Test fun `profil absent - non applicable`() {
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(true, 30f, 25, Direction.BELOW, false))
    }

    // ── Direction BELOW : déclenche quand il fait ≤ seuil ─────────────────────
    @Test fun `below - sous le seuil applique`() {
        assertEquals(Outcome.APPLY, AutomationDecision.evaluate(true, 24.9f, 25, Direction.BELOW, true))
    }

    @Test fun `below - au seuil applique (borne incluse)`() {
        assertEquals(Outcome.APPLY, AutomationDecision.evaluate(true, 25f, 25, Direction.BELOW, true))
    }

    @Test fun `below - au dessus du seuil non applicable`() {
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(true, 31.5f, 25, Direction.BELOW, true))
    }

    // ── Direction ABOVE : déclenche quand il fait ≥ seuil ─────────────────────
    @Test fun `above - au dessus du seuil applique`() {
        assertEquals(Outcome.APPLY, AutomationDecision.evaluate(true, 26.5f, 25, Direction.ABOVE, true))
    }

    @Test fun `above - au seuil applique (borne incluse)`() {
        assertEquals(Outcome.APPLY, AutomationDecision.evaluate(true, 25f, 25, Direction.ABOVE, true))
    }

    @Test fun `above - sous le seuil non applicable`() {
        assertEquals(Outcome.NOT_APPLICABLE, AutomationDecision.evaluate(true, 24.9f, 25, Direction.ABOVE, true))
    }

    @Test fun `clampTemp borne 0 a 60, defaut si null`() {
        assertEquals(25, AutomationSettings.clampTemp(null))
        assertEquals(0, AutomationSettings.clampTemp(-5))
        assertEquals(60, AutomationSettings.clampTemp(120))
        assertEquals(18, AutomationSettings.clampTemp(18))
    }
}
