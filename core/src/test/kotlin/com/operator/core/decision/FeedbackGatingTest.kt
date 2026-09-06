package com.operator.core.decision

import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.FeedbackAdjustment
import com.operator.core.feedback.VerdictKind
import com.operator.core.model.OperatorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Milestone 14: what recent feedback does to the gate.
 *
 * The property under test throughout is the asymmetry — complaints tighten, approval does not
 * loosen (ADR-045).
 */
class FeedbackGatingTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun policy(clock: FakeClock = FakeClock()) = ConversationPolicy(
        minCommentIntervalSeconds = 0,
        maxCommentsPer5Minutes = 100,
        minDecisionIntervalSeconds = 0,
        maxDecisionsPer5Minutes = 1_000,
        minConfidence = 0.6f,
        minRelevance = 0.5f,
        feedbackAdjustment = FeedbackAdjustment(),
        clock = clock,
    ) to clock

    private fun ambient() = DecisionRequest(
        trigger = DecisionTrigger.AMBIENT,
        recentTranscript = "someone is talking about the deadline",
        mode = OperatorMode.ACTIVE,
    )

    private fun invited() = DecisionRequest(
        trigger = DecisionTrigger.COMMENT_NOW,
        recentTranscript = "someone is talking about the deadline",
        mode = OperatorMode.ACTIVE,
    )

    /** Just above the base floors, so any penalty at all pushes it under. */
    private fun marginal() = ResponseDecision(
        shouldSpeak = true,
        category = ResponseCategory.USEFUL_CONTEXT,
        confidence = 0.62f,
        urgency = 0.3f,
        relevance = 0.52f,
        response = "The deadline moved to Thursday.",
        reasonCode = "USEFUL_CONTEXT",
    )

    private fun complaint(clock: FakeClock) = CommentFeedback(
        comment = "an earlier remark nobody wanted",
        verdict = VerdictKind.UNWANTED,
        trigger = "AMBIENT",
        atMillis = clock.now,
    )

    @Test
    fun `a marginal comment survives when nobody has complained`() {
        val (p, _) = policy()
        assertTrue(p.review(marginal(), ambient()).shouldSpeak)
    }

    @Test
    fun `the same comment is suppressed after a complaint`() {
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        val reviewed = p.review(marginal(), ambient())
        assertFalse(reviewed.shouldSpeak, "a fresh complaint should raise the floor past a marginal comment")
        assertEquals("LOW_CONFIDENCE", reviewed.reasonCode)
    }

    @Test
    fun `approval does not restore it`() {
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        repeat(20) {
            p.recordFeedback(
                CommentFeedback("a remark they liked", VerdictKind.HELPFUL, "AMBIENT", atMillis = clock.now),
            )
        }
        assertFalse(
            p.review(marginal(), ambient()).shouldSpeak,
            "praise must not cancel a complaint, or the floors become negotiable",
        )
    }

    @Test
    fun `an invited answer is not penalised by an unrelated complaint`() {
        // The user pressing a button is asking. Answering them worse because an ambient remark
        // annoyed them an hour ago would punish the wrong request.
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        assertTrue(p.review(marginal(), invited()).shouldSpeak)
    }

    @Test
    fun `the penalty wears off`() {
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        assertFalse(p.review(marginal(), ambient()).shouldSpeak)

        clock.now += FeedbackAdjustment.DEFAULT_HALF_LIFE_MILLIS
        assertTrue(
            p.review(marginal(), ambient()).shouldSpeak,
            "a complaint should not silence Operator forever",
        )
    }

    @Test
    fun `a confident comment still gets through a complaint`() {
        // The penalty is a nudge, not a mute. Something Operator is sure about should survive one
        // bad review, or feedback becomes an off switch with extra steps.
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        val strong = marginal().copy(confidence = 0.95f, relevance = 0.9f)
        assertTrue(p.review(strong, ambient()).shouldSpeak)
    }

    @Test
    fun `feedback is bounded so a long session cannot grow it without limit`() {
        val (p, clock) = policy()
        repeat(500) { p.recordFeedback(complaint(clock)) }
        // Capped by FeedbackAdjustment regardless of how many are retained.
        assertEquals(0.25f, p.feedbackPenalty(), 0.001f)
    }

    @Test
    fun `setFeedback replaces rather than appends`() {
        val (p, clock) = policy()
        p.recordFeedback(complaint(clock))
        assertTrue(p.feedbackPenalty() > 0f)
        p.setFeedback(emptyList())
        assertEquals(0f, p.feedbackPenalty(), "clearing the feedback should clear the penalty")
    }

    @Test
    fun `a policy with no adjustment configured is unaffected by feedback`() {
        val clock = FakeClock()
        val p = ConversationPolicy(
            minCommentIntervalSeconds = 0, maxCommentsPer5Minutes = 100,
            minDecisionIntervalSeconds = 0, maxDecisionsPer5Minutes = 1_000,
            clock = clock,
        )
        p.recordFeedback(complaint(clock))
        assertEquals(0f, p.feedbackPenalty())
        assertTrue(p.review(marginal(), ambient()).shouldSpeak)
    }
}
