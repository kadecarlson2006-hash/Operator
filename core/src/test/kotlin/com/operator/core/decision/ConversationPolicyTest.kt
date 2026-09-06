package com.operator.core.decision

import com.operator.core.model.OperatorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationPolicyTest {

    private class FakeClock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun policy(
        minIntervalSeconds: Int = 45,
        maxPer5Minutes: Int = 3,
        minDecisionIntervalSeconds: Int = 0,
        maxDecisionsPer5Minutes: Int = 1_000,
        clock: FakeClock = FakeClock(),
    ) = ConversationPolicy(
        minIntervalSeconds, maxPer5Minutes,
        minDecisionIntervalSeconds, maxDecisionsPer5Minutes,
        clock = clock,
    ) to clock

    private fun ambient(
        transcript: String = "someone is talking about the deadline",
        mode: OperatorMode = OperatorMode.ACTIVE,
        muted: Boolean = false,
        recentComments: List<String> = emptyList(),
    ) = DecisionRequest(
        trigger = DecisionTrigger.AMBIENT,
        recentTranscript = transcript,
        mode = mode,
        muted = muted,
        recentComments = recentComments,
    )

    private fun speaking(
        text: String = "The deadline moved to Thursday.",
        confidence: Float = 0.9f,
        relevance: Float = 0.9f,
        category: ResponseCategory = ResponseCategory.USEFUL_CONTEXT,
    ) = ResponseDecision(
        shouldSpeak = true,
        category = category,
        confidence = confidence,
        urgency = 0.5f,
        relevance = relevance,
        response = text,
        reasonCode = "MODEL_SAID_SO",
    )

    // --- the gate: can Operator speak at all? ---

    @Test
    fun `an ordinary ambient moment in an active mode is allowed through`() {
        val (p, _) = policy()
        assertNull(p.gate(ambient()))
    }

    @Test
    fun `muted refuses everything, including an explicit request`() {
        val (p, _) = policy()
        assertEquals("MUTED", p.gate(ambient(muted = true)))
        assertEquals(
            "MUTED",
            p.gate(DecisionRequest(DecisionTrigger.COMMENT_NOW, "anything", muted = true)),
            "COMMENT NOW must not override mute",
        )
    }

    @Test
    fun `OFF refuses everything`() {
        val (p, _) = policy()
        assertEquals("MODE_OFF", p.gate(ambient(mode = OperatorMode.OFF)))
        assertEquals("MODE_OFF", p.gate(DecisionRequest(DecisionTrigger.DIRECT_ADDRESS, "hey", mode = OperatorMode.OFF)))
    }

    @Test
    fun `modes that never volunteer refuse ambient but answer when addressed`() {
        val (p, _) = policy()
        for (mode in listOf(OperatorMode.STANDBY, OperatorMode.QUIET)) {
            assertEquals("MODE_DOES_NOT_VOLUNTEER", p.gate(ambient(mode = mode)), "$mode should not volunteer")
            assertNull(
                p.gate(DecisionRequest(DecisionTrigger.DIRECT_ADDRESS, "what time is it", mode = mode)),
                "$mode should still answer when addressed",
            )
        }
    }

    @Test
    fun `nothing heard means nothing to comment on`() {
        val (p, _) = policy()
        assertEquals("NOTHING_HEARD", p.gate(ambient(transcript = "")))
        assertEquals("NOTHING_HEARD", p.gate(ambient(transcript = "   ")))
    }

    @Test
    fun `Operator does not speak twice inside the minimum interval`() {
        val (p, clock) = policy(minIntervalSeconds = 45)
        assertNull(p.gate(ambient()))
        p.recordSpoken()

        clock.now = 10_000
        assertEquals("RECENTLY_SPOKE", p.gate(ambient()))

        clock.now = 46_000
        assertNull(p.gate(ambient()), "the interval has passed")
    }

    @Test
    fun `the five-minute cap holds and then expires`() {
        val (p, clock) = policy(minIntervalSeconds = 0, maxPer5Minutes = 3)
        repeat(3) {
            assertNull(p.gate(ambient()))
            p.recordSpoken()
            clock.now += 1_000
        }
        assertEquals("RATE_LIMITED", p.gate(ambient()))
        assertEquals(3, p.recentCommentCount())

        clock.now += 5 * 60 * 1_000
        assertNull(p.gate(ambient()), "the window has rolled past the earlier comments")
        assertEquals(0, p.recentCommentCount())
    }

    @Test
    fun `an explicit request ignores the interval and the cap but not mute`() {
        val (p, clock) = policy(minIntervalSeconds = 45, maxPer5Minutes = 1)
        p.recordSpoken()
        clock.now = 1_000

        assertEquals("RECENTLY_SPOKE", p.gate(ambient()), "ambient is still rate limited")
        assertNull(p.gate(DecisionRequest(DecisionTrigger.COMMENT_NOW, "x")), "the user asked")
        assertNull(p.gate(DecisionRequest(DecisionTrigger.DIRECT_ADDRESS, "x")), "the user asked")
    }

    // --- the decision budget: the only limits that hold while Operator stays silent ---

    @Test
    fun `staying silent does not exempt Operator from the decision interval`() {
        val (p, clock) = policy(minDecisionIntervalSeconds = 20)
        assertNull(p.gate(ambient()))
        p.recordDecision()          // the model was asked and said nothing

        clock.now = 5_000
        assertEquals(
            "DECIDED_RECENTLY",
            p.gate(ambient()),
            "every other limit keys on speaking; without this, silence costs a call per utterance",
        )

        clock.now = 21_000
        assertNull(p.gate(ambient()))
    }

    @Test
    fun `the decision budget caps spend over five minutes and then expires`() {
        val (p, clock) = policy(maxDecisionsPer5Minutes = 4)
        repeat(4) {
            assertNull(p.gate(ambient()))
            p.recordDecision()
            clock.now += 1_000
        }
        assertEquals("DECISION_BUDGET", p.gate(ambient()))
        assertEquals(4, p.recentDecisionCount())

        clock.now += 5 * 60 * 1_000
        assertNull(p.gate(ambient()))
        assertEquals(0, p.recentDecisionCount())
    }

    @Test
    fun `an explicit request is never refused by the budget but still counts against it`() {
        val (p, clock) = policy(minDecisionIntervalSeconds = 20, maxDecisionsPer5Minutes = 1)
        p.recordDecision()
        clock.now = 1_000

        assertNull(p.gate(DecisionRequest(DecisionTrigger.COMMENT_NOW, "x")), "the user asked")
        p.recordDecision()

        assertEquals(2, p.recentDecisionCount(), "an invited call costs the same and is counted")
        assertEquals("DECIDED_RECENTLY", p.gate(ambient()), "so ambient does not spend twice")
    }

    @Test
    fun `speaking and deciding are counted separately`() {
        val (p, _) = policy(minIntervalSeconds = 0, minDecisionIntervalSeconds = 0)
        p.recordDecision()
        p.recordDecision()
        p.recordSpoken()

        assertEquals(2, p.recentDecisionCount())
        assertEquals(1, p.recentCommentCount(), "most decisions produce silence, and that is the point")
    }

    @Test
    fun `reset clears both histories`() {
        val (p, _) = policy()
        p.recordDecision()
        p.recordSpoken()
        p.reset()
        assertEquals(0, p.recentDecisionCount())
        assertEquals(0, p.recentCommentCount())
    }

    // --- the review: is what the model returned worth saying? ---

    @Test
    fun `a good ambient comment survives review`() {
        val (p, _) = policy()
        val reviewed = p.review(speaking(), ambient())
        assertTrue(reviewed.shouldSpeak)
        assertEquals("The deadline moved to Thursday.", reviewed.response)
    }

    @Test
    fun `silence from the model is left alone`() {
        val (p, _) = policy()
        val silent = ResponseDecision.silence("MODEL_CHOSE_SILENCE")
        assertEquals(silent, p.review(silent, ambient()))
    }

    @Test
    fun `a low-confidence ambient comment is not worth interrupting for`() {
        val (p, _) = policy()
        val reviewed = p.review(speaking(confidence = 0.3f), ambient())
        assertFalse(reviewed.shouldSpeak)
        assertEquals("LOW_CONFIDENCE", reviewed.reasonCode)
    }

    @Test
    fun `a barely relevant ambient comment is suppressed`() {
        val (p, _) = policy()
        assertEquals("LOW_RELEVANCE", p.review(speaking(relevance = 0.1f), ambient()).reasonCode)
    }

    @Test
    fun `a direct answer is not held to the interruption bar`() {
        val (p, _) = policy()
        val asked = DecisionRequest(DecisionTrigger.DIRECT_ADDRESS, "what did she say")
        val reviewed = p.review(speaking(confidence = 0.3f, relevance = 0.2f), asked)
        assertTrue(reviewed.shouldSpeak, "the user asked; an unsure answer still beats ignoring them")
    }

    @Test
    fun `a speech longer than three sentences is suppressed`() {
        val (p, _) = policy()
        val long = speaking("One thing. Two things. Three things. Four things.")
        assertEquals("TOO_LONG", p.review(long, ambient()).reasonCode)
        assertTrue(p.review(speaking("One thing. Two things. Three things."), ambient()).shouldSpeak)
    }

    @Test
    fun `a single sentence without a full stop counts as one`() {
        val (p, _) = policy()
        assertTrue(p.review(speaking("Thursday works"), ambient()).shouldSpeak)
    }

    @Test
    fun `Operator does not say the same thing twice`() {
        val (p, _) = policy()
        val request = ambient(recentComments = listOf("The deadline moved to Thursday."))
        assertEquals("ALREADY_SAID", p.review(speaking(), request).reasonCode)
    }

    @Test
    fun `a reworded repeat is still a repeat`() {
        val (p, _) = policy()
        val request = ambient(recentComments = listOf("the deadline moved to thursday"))
        assertEquals("ALREADY_SAID", p.review(speaking("The deadline moved to Thursday!"), request).reasonCode)
    }

    @Test
    fun `a genuinely different comment is not treated as a repeat`() {
        val (p, _) = policy()
        val request = ambient(recentComments = listOf("The deadline moved to Thursday."))
        assertTrue(p.review(speaking("Chris owns the west region."), request).shouldSpeak)
    }

    @Test
    fun `a decision to speak with no text cannot be built at all`() {
        // The policy has no check for this because the type forbids it, including through copy.
        // Anything parsing a model's JSON must turn "speak, but no text" into silence itself.
        for (blank in listOf(null, "", "   ")) {
            assertFailsWith<IllegalArgumentException> { speaking().copy(response = blank) }
        }
    }

    @Test
    fun `a shouldSpeak decision categorised as NO_RESPONSE is refused`() {
        val (p, _) = policy()
        val contradictory = speaking(category = ResponseCategory.NO_RESPONSE)
        assertEquals("CATEGORY_NO_RESPONSE", p.review(contradictory, ambient()).reasonCode)
    }

    @Test
    fun `suppression never leaks the model's own reason`() {
        val (p, _) = policy()
        val reviewed = p.review(speaking(confidence = 0.1f), ambient())
        assertFalse(reviewed.shouldSpeak)
        assertEquals("LOW_CONFIDENCE", reviewed.reasonCode)
        assertTrue(reviewed.reasonCode!!.none { it.isLowerCase() }, "reason codes are labels, not prose")
    }
}
