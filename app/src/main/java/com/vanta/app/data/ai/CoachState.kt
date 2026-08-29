package com.vanta.app.data.ai

/**
 * The deterministic coaching state. Kotlin (the physiology engine) decides the
 * "what", and the model only turns it into natural language. The model NEVER decides
 * the state — this keeps on-device (small) models from guessing wrong.
 */
enum class CoachState(val label: String) {
    READY_TO_PUSH("Ready to Push"),
    RECOVERY_FOCUS("Recovery Focus"),
    BUILDING_STRAIN("Building Strain"),
    TARGET_REACHED("Target Reached"),
    LOW_ENERGY("Low Energy"),
    REST_DAY("Rest Day")
}

/**
 * The minimal, task-specific context handed to a model. We deliberately do NOT ship
 * a wall of raw metrics — only what the specific prompt needs to word the state.
 */
data class CoachContext(
    val state: CoachState,
    val recovery: Int,
    val energy: Int,
    val strain: Double,
    val recoveryBaseline: Int? = null,
    val strainTarget: Double? = null,
    val keyInsight: String = ""
)

object CoachStateEngine {

    /**
     * Deterministic, precedence-ordered state derivation from today's real values.
     * Tuned so a small on-device model never has to reason about thresholds.
     */
    fun derive(
        recovery: Int,
        energy: Int,
        strain: Double,
        recoveryBaseline: Int? = null,
        strainTarget: Double? = null
    ): CoachState = when {
        recovery < 55 && energy < 50 -> CoachState.REST_DAY
        strainTarget != null && strain >= strainTarget -> CoachState.TARGET_REACHED
        recovery < 55 -> CoachState.RECOVERY_FOCUS
        energy < 50 -> CoachState.LOW_ENERGY
        strain >= 14.0 -> CoachState.TARGET_REACHED
        recovery >= 70 && energy >= 60 -> CoachState.READY_TO_PUSH
        strain >= 11.0 -> CoachState.BUILDING_STRAIN
        else -> CoachState.READY_TO_PUSH
    }

    /** A one-line, deterministic interpretation to anchor the model's wording. */
    fun keyInsight(state: CoachState, recovery: Int, energy: Int, strain: Double,
                   recoveryBaseline: Int?, strainTarget: Double?): String = when (state) {
        CoachState.READY_TO_PUSH ->
            "Recovery and energy are solid, strain is not yet demanding — there is room to push."
        CoachState.RECOVERY_FOCUS ->
            "Recovery is low, so today is about protecting the body, not chasing load."
        CoachState.BUILDING_STRAIN ->
            "Strain is rising toward the target — a productive training window, watch the load."
        CoachState.TARGET_REACHED ->
            "The strain target is reached — shift focus to recovery and consolidation."
        CoachState.LOW_ENERGY ->
            "Energy is depleted — keep output measured and preserve capacity."
        CoachState.REST_DAY ->
            "Recovery and energy are both low — a full rest day is the highest-value move."
    }

    fun context(recovery: Int, energy: Int, strain: Double,
                recoveryBaseline: Int?, strainTarget: Double?): CoachContext {
        val state = derive(recovery, energy, strain, recoveryBaseline, strainTarget)
        return CoachContext(
            state = state,
            recovery = recovery,
            energy = energy,
            strain = strain,
            recoveryBaseline = recoveryBaseline,
            strainTarget = strainTarget,
            keyInsight = keyInsight(state, recovery, energy, strain, recoveryBaseline, strainTarget)
        )
    }
}
