package com.operator.app.backend

import com.operator.core.diagnostics.LatencyCheckpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskOperatorControllerTest {

    private class FakeBackend(private val reply: AskResponse? = null, private val failure: Exception? = null) : OperatorBackend {
        override val configured = true
        var asked: String? = null
        override suspend fun ask(prompt: String, tier: String?, sessionId: String?): AskResponse {
            asked = prompt
            delay(50)
            failure?.let { throw it }
            return reply!!
        }
    }

    private val answer = AskResponse(
        text = "Javier Bardem.", model = "v/fast", tier = "FAST", routingReason = "default conversational tier",
        promptVersion = "operator-system-v1", latencyMillis = 700, inputTokens = 40, outputTokens = 6,
    )

    @Test
    fun `send trims the prompt, fills the answer and measures the round trip`() = runTest {
        var now = 1_000L
        val backend = FakeBackend(answer)
        val c = AskOperatorController(backend, this, clock = { now })
        assertFalse("empty prompt cannot be sent", c.send())

        c.setPrompt("  Who played the villain?  ")
        assertTrue(c.state.value.canSend)
        assertTrue(c.send())
        assertTrue(c.state.value.inFlight)
        assertFalse("no double send while in flight", c.send())
        now = 1_900L
        advanceUntilIdle()

        val s = c.state.value
        assertFalse(s.inFlight)
        assertEquals("Javier Bardem.", s.answer)
        assertEquals("Who played the villain?", backend.asked)
        assertEquals("v/fast", s.model)
        assertEquals("FAST", s.tier)
        assertEquals(900L, s.roundTripMillis)
        assertEquals(700L, s.modelLatencyMillis)
        assertEquals(40, s.inputTokens as Int)
        assertNull(s.error)
        assertEquals(900L, c.lastTimeline.between(LatencyCheckpoint.AI_REQUEST_STARTED, LatencyCheckpoint.AI_FIRST_TOKEN))
    }

    @Test
    fun `a backend failure becomes a readable error and clears the in-flight flag`() = runTest {
        val c = AskOperatorController(FakeBackend(failure = BackendException("Backend unreachable at http://x")), this)
        c.setPrompt("hello")
        c.send()
        advanceUntilIdle()
        val s = c.state.value
        assertFalse(s.inFlight)
        assertNull(s.answer)
        assertEquals("Backend unreachable at http://x", s.error)
        assertTrue("a failed ask can be retried", c.state.value.canSend)
    }

    @Test
    fun `clear resets everything`() = runTest {
        val c = AskOperatorController(FakeBackend(answer), this)
        c.setPrompt("hi")
        c.send()
        advanceUntilIdle()
        assertNotNull(c.state.value.answer)
        c.clear()
        assertEquals(AskState(), c.state.value)
        assertTrue(c.lastTimeline.isEmpty())
    }
}
