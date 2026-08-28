package com.evsuite.profile.automation

/** Pure decision of temperature automation (testable without Android). */
object AutomationDecision {

    enum class Outcome { NOT_APPLICABLE, APPLY }

    /**
     * APPLY only if [enabled], [temp] is readable (not null/NaN), [profileExists], and the condition
     * selon [direction] (borne incluse) :
     *   BELOW → [temp] <= [threshold] ; ABOVE → [temp] >= [threshold].
     * Sinon NOT_APPLICABLE.
     */
    fun evaluate(
        enabled: Boolean,
        temp: Float?,
        threshold: Int,
        direction: AutomationSettings.Direction,
        profileExists: Boolean
    ): Outcome = when {
        !enabled                     -> Outcome.NOT_APPLICABLE
        temp == null || temp.isNaN() -> Outcome.NOT_APPLICABLE
        !profileExists               -> Outcome.NOT_APPLICABLE
        direction == AutomationSettings.Direction.BELOW && temp <= threshold.toFloat() -> Outcome.APPLY
        direction == AutomationSettings.Direction.ABOVE && temp >= threshold.toFloat() -> Outcome.APPLY
        else                         -> Outcome.NOT_APPLICABLE
    }
}
