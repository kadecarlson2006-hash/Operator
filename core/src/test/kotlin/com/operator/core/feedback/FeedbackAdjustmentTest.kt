package com.operator.core.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackAdjustmentTest {

    private val now = 1_000_000L
    private val adjustment = FeedbackAdjustment()

    private fun item(
        verdict: VerdictKind,
        agoMillis: Long = 0L,
        comment: String = "something Operator said",
    ) = CommentFeedback(comment = comment, verdict = verdict, trigger = "AMBIENT", atMillis = now - agoMillis)

    @Test
    fun `no feedback means no penalty`() {
        assertEquals(0f, adjustment.penalty(emptyList(), now))
    }

    @Test
    fun `a fresh complaint raises the floors`() {
        assertEquals(0.05f, adjustment.penalty(listOf(item(VerdictKind.UNWANTED)), now), 0.001f)
    }

    @Test
    fun `approval never lowers a floor`() {
        // The property the whole class exists for: praise cannot buy Operator permission to talk
        // more than its configured floors already allow (ADR-045).
        val praised = List(20) { item(VerdictKind.HELPFUL) }
        assertEquals(0f, adjustment.penalty(praised, now))
    }

    @Test
    fun `approval does not cancel out a complaint`() {
        val mixed = listOf(item(VerdictKind.UNWANTED)) + List(10) { item(VerdictKind.HELPFUL) }
        assertEquals(
            adjustment.penalty(listOf(item(VerdictKind.UNWANTED)), now),
            adjustment.penalty(mixed, now),
            0.001f,
        )
    }

    @Test
    fun `WRONG does not raise the bar for speaking`() {
        // Being wrong is a content fault. Making Operator speak less often does not make it more
        // accurate, so that verdict is fed to the model through the prompt instead.
        assertEquals(0f, adjustment.penalty(listOf(item(VerdictKind.WRONG)), now))
    }

    @Test
    fun `TOO_LATE counts, because a late comment should not have been made`() {
        assertTrue(adjustment.penalty(listOf(item(VerdictKind.TOO_LATE)), now) > 0f)
    }

    @Test
    fun `complaints decay to nothing by the half life`() {
        val half = FeedbackAdjustment.DEFAULT_HALF_LIFE_MILLIS
        val fresh = adjustment.penalty(listOf(item(VerdictKind.UNWANTED)), now)
        val middling = adjustment.penalty(listOf(item(VerdictKind.UNWANTED, agoMillis = half / 2)), now)
        val expired = adjustment.penalty(listOf(item(VerdictKind.UNWANTED, agoMillis = half)), now)

        assertTrue(middling < fresh, "a half-old complaint should weigh less than a fresh one")
        assertTrue(middling > 0f, "a half-old complaint should still weigh something")
        assertEquals(0f, expired, "a complaint at the half-life should have decayed away")
    }

    @Test
    fun `feedback from the future is ignored rather than trusted`() {
        // Clock skew between the phone and the backend is ordinary. A negative age would otherwise
        // produce a weight above 1 and silence Operator on a timestamp bug.
        assertEquals(0f, adjustment.penalty(listOf(item(VerdictKind.UNWANTED, agoMillis = -5_000L)), now))
    }

    @Test
    fun `the penalty is capped however much is complained about`() {
        val many = List(100) { item(VerdictKind.UNWANTED) }
        assertEquals(0.25f, adjustment.penalty(many, now), 0.001f)
    }

    @Test
    fun `describe says nothing when there is nothing to say`() {
        assertEquals("no recent complaints", adjustment.describe(emptyList(), now))
    }

    @Test
    fun `describe reports the penalty without quoting anybody`() {
        val text = adjustment.describe(listOf(item(VerdictKind.UNWANTED, comment = "a distinctive phrase")), now)
        assertTrue(text.contains("0.05"), "expected the penalty in the description, got: $text")
        assertTrue(!text.contains("distinctive"), "the description must not quote what was said")
    }

    @Test
    fun `a blank comment is refused at construction`() {
        val error = runCatching { CommentFeedback(comment = "   ", verdict = VerdictKind.HELPFUL, trigger = "AMBIENT") }
        assertTrue(error.isFailure, "a blank comment should not be storable")
    }
}
