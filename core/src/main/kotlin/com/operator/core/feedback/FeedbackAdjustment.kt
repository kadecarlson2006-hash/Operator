package com.operator.core.feedback

import kotlin.math.min

/**
 * Turns recent feedback into a stricter gate (Milestone 14).
 *
 * The single rule this class exists to enforce: **feedback can only make Operator quieter.**
 *
 * Positive feedback does not lower a floor, it only stops the penalty from accruing. That is not
 * timidity, it is the same property ADR-038 gives `ConversationPolicy`: the local rules may refuse
 * but never permit. A system that could be talked into speaking more would put "silence is the
 * default" at the mercy of a few approving taps, and the costs are not symmetric — a comment that
 * was wanted and never came is a small loss, while one that was not wanted is the failure that
 * makes people switch the thing off (ADR-045).
 *
 * Old complaints fade. Feedback from last week describes a conversation that has moved on, and a
 * penalty that never decays would eventually silence Operator permanently on the strength of one
 * bad afternoon.
 */
class FeedbackAdjustment(
    /** How long a piece of feedback keeps any weight at all. */
    private val halfLifeMillis: Long = DEFAULT_HALF_LIFE_MILLIS,
    /** How much a single fresh complaint raises the floors. */
    private val perComplaintPenalty: Float = 0.05f,
    /** However much is complained about, the floors never rise past this. */
    private val maxPenalty: Float = 0.25f,
) {
    init {
        require(halfLifeMillis > 0) { "halfLifeMillis must be positive" }
        require(perComplaintPenalty >= 0f) { "perComplaintPenalty must be >= 0" }
        require(maxPenalty in 0f..1f) { "maxPenalty must be within 0..1" }
    }

    /**
     * How much to add to the confidence and relevance floors, given what the user has said lately.
     *
     * Only UNWANTED and TOO_LATE count. WRONG is a content fault, not a "you should not have
     * spoken" fault: raising the bar for speaking would be the wrong correction for it, and the
     * model is told about it through the prompt instead.
     */
    fun penalty(feedback: List<CommentFeedback>, now: Long): Float {
        if (feedback.isEmpty()) return 0f
        var total = 0f
        for (item in feedback) {
            if (!item.verdict.isNegative) continue
            if (item.verdict == VerdictKind.WRONG) continue
            val age = now - item.atMillis
            if (age < 0 || age >= halfLifeMillis) continue
            // Linear decay to zero at the half-life. Not exponential: this number has to be
            // explainable to the person it silences, and "it wears off over an hour" is.
            val weight = 1f - (age.toFloat() / halfLifeMillis.toFloat())
            total += perComplaintPenalty * weight
        }
        return min(total, maxPenalty)
    }

    /** A short, non-identifying summary for diagnostics. Never model reasoning (ADR-009). */
    fun describe(feedback: List<CommentFeedback>, now: Long): String {
        val p = penalty(feedback, now)
        if (p <= 0f) return "no recent complaints"
        return "floors raised by ${"%.2f".format(p)} on recent feedback"
    }

    companion object {
        /** An hour. Long enough to cover a conversation, short enough that a bad hour is not a bad day. */
        const val DEFAULT_HALF_LIFE_MILLIS: Long = 60 * 60 * 1000L
    }
}
