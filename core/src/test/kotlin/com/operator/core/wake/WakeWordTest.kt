package com.operator.core.wake

import com.operator.core.decision.DecisionTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordTest {

    @Test
    fun `plainly addressed`() {
        assertTrue(WakeWord.isAddressed("Operator, what are all the colors in the rainbow"))
        assertTrue(WakeWord.isAddressed("operator what time is it"))
        assertTrue(WakeWord.isAddressed("Hey Operator, remind me later"))
        assertTrue(WakeWord.isAddressed("OK operator - who plays John Dutton"))
    }

    @Test
    fun `works on a transcript line with its speaker prefix`() {
        // The rolling window stores "Someone: ...", so the prefix must not hide the name.
        assertTrue(WakeWord.isAddressed("Someone: operator, what time is it"))
        assertTrue(WakeWord.isAddressed("Kade: Operator what is the capital of France"))
    }

    @Test
    fun `the name late in a sentence is being talked about, not talked to`() {
        assertFalse(WakeWord.isAddressed("the switchboard operator called this morning"))
        assertFalse(WakeWord.isAddressed("I used to work as a crane operator for years"))
    }

    @Test
    fun `transcription near-misses still count`() {
        // Whisper mangles a four-syllable word regularly. Being ignored when you used its name is
        // a worse failure than answering once when you did not.
        assertTrue(WakeWord.isAddressed("operater what time is it"))
        assertTrue(WakeWord.isAddressed("opperator, what time is it"))
        assertTrue(WakeWord.isAddressed("operatr what time is it"))
    }

    @Test
    fun `ordinary words about machinery are not the name`() {
        assertFalse(WakeWord.isAddressed("operate the lever slowly"))
        assertFalse(WakeWord.isAddressed("operation went fine thanks"))
        assertFalse(WakeWord.isAddressed("opera tonight was wonderful"))
    }

    @Test
    fun `nothing and nonsense are not an address`() {
        assertFalse(WakeWord.isAddressed(""))
        assertFalse(WakeWord.isAddressed("   "))
        assertFalse(WakeWord.isAddressed("Someone: "))
    }

    @Test
    fun `the address is stripped so the request stands alone`() {
        assertEquals("what time is it", WakeWord.stripAddress("Operator, what time is it"))
        assertEquals("what time is it", WakeWord.stripAddress("Someone: operator what time is it"))
        assertEquals("remind me later", WakeWord.stripAddress("operator - remind me later"))
    }

    @Test
    fun `stripping leaves a line that was never addressed alone`() {
        val line = "the switchboard operator called"
        assertEquals(line, WakeWord.stripAddress(line))
    }

    @Test
    fun `stripping a bare name leaves the name rather than nothing`() {
        // "Operator." on its own is someone getting its attention. Returning an empty string
        // would give the decision stage nothing to judge.
        assertEquals("Operator.", WakeWord.stripAddress("Operator."))
    }

    @Test
    fun `an addressed line becomes a direct address`() {
        assertEquals(DecisionTrigger.DIRECT_ADDRESS, WakeWord.triggerFor("Operator, what time is it"))
        assertEquals(DecisionTrigger.AMBIENT, WakeWord.triggerFor("the deadline is Thursday"))
    }

    @Test
    fun `the fallback trigger is preserved when not addressed`() {
        assertEquals(
            DecisionTrigger.COMMENT_NOW,
            WakeWord.triggerFor("the deadline is Thursday", fallback = DecisionTrigger.COMMENT_NOW),
        )
    }

    @Test
    fun `a different name can be configured`() {
        assertTrue(WakeWord.isAddressed("Jarvis, what time is it", name = "jarvis"))
        assertFalse(WakeWord.isAddressed("Operator, what time is it", name = "jarvis"))
    }
}
