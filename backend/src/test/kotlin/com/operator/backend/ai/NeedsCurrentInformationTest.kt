package com.operator.backend.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsCurrentInformationTest {

    @Test
    fun `the question that started this needs looking up`() {
        // "Who wears number 95 for the Los Angeles Rams" was answered from stale training data,
        // confidently and wrongly. It has no time word in it at all.
        assertTrue(NeedsCurrentInformation.judge("Who wears number 95 for the Los Angeles Rams?"))
    }

    @Test
    fun `a greeting does not`() {
        // The first live test searched the web to say hello: four times the cost, for nothing.
        assertFalse(NeedsCurrentInformation.judge("Say hello in one short sentence."))
        assertFalse(NeedsCurrentInformation.judge("Good morning"))
    }

    @Test
    fun `settled facts do not`() {
        assertFalse(NeedsCurrentInformation.judge("What does ephemeral mean"))
        assertFalse(NeedsCurrentInformation.judge("How many legs does a spider have"))
        assertFalse(NeedsCurrentInformation.judge("Explain how a heat pump works"))
    }

    @Test
    fun `arithmetic does not`() {
        assertFalse(NeedsCurrentInformation.judge("What is 17 times 23"))
    }

    @Test
    fun `explicitly present-tense questions do`() {
        assertTrue(NeedsCurrentInformation.judge("What is the weather like today"))
        assertTrue(NeedsCurrentInformation.judge("What is the latest on the strike"))
        assertTrue(NeedsCurrentInformation.judge("How much does a Model 3 cost now"))
        assertTrue(NeedsCurrentInformation.judge("Who is the president of France"))
    }

    @Test
    fun `subjects that change do, even without a time word`() {
        assertTrue(NeedsCurrentInformation.judge("Who plays for the Rams at quarterback"))
        assertTrue(NeedsCurrentInformation.judge("Who is the CEO of Boeing"))
    }

    @Test
    fun `a jersey number is always about a season`() {
        assertTrue(NeedsCurrentInformation.judge("who has number 7"))
        assertFalse(NeedsCurrentInformation.judge("what is number theory"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(NeedsCurrentInformation.judge("WHAT IS THE LATEST NEWS"))
    }

    @Test
    fun `an empty prompt does not trigger a search`() {
        assertFalse(NeedsCurrentInformation.judge(""))
    }
}
