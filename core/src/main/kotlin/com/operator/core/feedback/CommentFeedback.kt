package com.operator.core.feedback

/**
 * What the user thought of something Operator said (Milestone 14).
 *
 * Deliberately small. Each verdict has to earn its place by changing what Operator does next,
 * because a scale nobody can apply consistently produces noise that looks like signal.
 */
enum class VerdictKind(val label: String, val isNegative: Boolean) {
    /** Worth hearing. The only verdict that is not a complaint. */
    HELPFUL(label = "Helpful", isNegative = false),

    /** Should not have spoken at all. The nuisance signal, and the one that matters most. */
    UNWANTED(label = "Didn't want that", isNegative = true),

    /** Fine to speak, but the content was incorrect. A different fault from speaking at all. */
    WRONG(label = "That was wrong", isNegative = true),

    /**
     * The right thing at the wrong moment.
     *
     * Separated from UNWANTED because the fix is different: this one points at the settle delay
     * and provider latency (risks 47 and 7), not at whether Operator should have spoken.
     */
    TOO_LATE(label = "Too late to be useful", isNegative = true),
}

/**
 * One piece of feedback about one thing Operator said.
 *
 * Note what is *not* here: the conversation that prompted the comment. Only Operator's own line is
 * kept, with the scores it was given at the time. Storing the surrounding talk would build exactly
 * the permanent record of other people's conversations the brief forbids, and it is not needed —
 * the useful signal is "you said this, and it was unwanted", not what everyone else was saying
 * (ADR-046).
 */
data class CommentFeedback(
    /** What Operator said, verbatim. */
    val comment: String,
    val verdict: VerdictKind,
    /** The trigger that produced the comment, so ambient nuisance is distinguishable from a bad answer. */
    val trigger: String,
    /** What the model claimed at the time, so a verdict can be correlated with the scores that allowed it. */
    val confidence: Float = 0f,
    val relevance: Float = 0f,
    val category: String? = null,
    /** Optional free text from the user. Never sent to a provider unless the user asked for it. */
    val note: String? = null,
    val atMillis: Long = 0L,
) {
    init {
        require(comment.isNotBlank()) { "comment must not be blank" }
    }
}
