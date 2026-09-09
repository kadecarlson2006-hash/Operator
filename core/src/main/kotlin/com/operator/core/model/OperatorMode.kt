package com.operator.core.model

/**
 * The operating posture of Operator. Mode governs *whether and how readily* Operator participates;
 * [WitLevel] governs *how much humor* it brings when it does.
 *
 * Modes carry their own restraint rather than only a permission flag. Before this, every mode that
 * allowed volunteering behaved identically - the selector looked like it controlled how much
 * Operator talked, and it did not, because all the frequency lived in fixed thresholds. The
 * factors below are what make the choice mean something (ADR-050).
 */
enum class OperatorMode(
    /** Short label shown in the UI. */
    val label: String,
    /** Whether Operator may speak without being directly addressed. */
    val allowsUnsolicitedComments: Boolean,
    /** Whether Operator listens to the conversation at all. */
    val listens: Boolean,
    /**
     * Added to the configured confidence and relevance floors for uninvited comments. Negative
     * lowers the bar. Applied on top of any feedback penalty, which can only ever raise it
     * (ADR-045), so a complaint still tightens even the most talkative mode.
     */
    val floorAdjustment: Float = 0f,
    /**
     * Multiplies the configured intervals - both between speaking and between *asking*. Below 1
     * means Operator may comment more often, and costs more, since asking is what is billed.
     */
    val intervalFactor: Float = 1f,
    /** Multiplies the five-minute caps on speaking and deciding. */
    val budgetFactor: Float = 1f,
) {
    /** No Operator processing whatsoever. */
    OFF(label = "OFF", allowsUnsolicitedComments = false, listens = false),

    /**
     * Follows the conversation and may occasionally offer information, corrections, reminders or
     * humor. The reserved everyday posture: present, rarely heard.
     */
    STANDBY(label = "STANDBY", allowsUnsolicitedComments = true, listens = true),

    /**
     * Actively participating. Speaks readily rather than occasionally.
     *
     * The floors drop and the intervals shorten, so Operator answers things it would otherwise
     * let pass. This is the mode to use when alone or when you want a companion rather than a
     * safety net - it is also the one that will talk over a room containing other people, and it
     * costs more, because being asked is what is billed (risk 40).
     */
    ACTIVE(
        label = "ACTIVE", allowsUnsolicitedComments = true, listens = true,
        floorAdjustment = -0.25f, intervalFactor = 0.2f, budgetFactor = 4f,
    ),

    /** Business information, numbers, commitments, risks, deadlines. Reduced humor. */
    WORK(
        label = "WORK", allowsUnsolicitedComments = true, listens = true,
        floorAdjustment = -0.05f, intervalFactor = 0.7f, budgetFactor = 1.5f,
    ),

    /** Names, conversation prompts, subtle social assistance, appropriate humor. */
    SOCIAL(
        label = "SOCIAL", allowsUnsolicitedComments = true, listens = true,
        floorAdjustment = -0.05f, intervalFactor = 0.7f, budgetFactor = 1.5f,
    ),

    /** Only responds when explicitly addressed or manually triggered. */
    QUIET(label = "QUIET", allowsUnsolicitedComments = false, listens = true),

    /**
     * Lower humor threshold, more sarcasm and callbacks. All safety and privacy rules still apply.
     *
     * Chattier than STANDBY but not as forward as ACTIVE: CHAOS changes what is worth saying more
     * than how often it is said.
     */
    CHAOS(
        label = "CHAOS", allowsUnsolicitedComments = true, listens = true,
        floorAdjustment = -0.1f, intervalFactor = 0.5f, budgetFactor = 2f,
    );

    /** Modes the user can pick from the main-screen selector (OFF is reached via its own control). */
    companion object {
        val selectable: List<OperatorMode> = listOf(STANDBY, ACTIVE, WORK, SOCIAL, QUIET, CHAOS)
    }
}
