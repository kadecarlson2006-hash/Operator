package com.operator.app.decision

import com.operator.core.transcription.RollingTranscript
import com.operator.core.transcription.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientDeciderTest {

    private class Recorder {
        var calls = 0
        fun decide(): Boolean { calls++; return true }
    }

    private fun decider(
        transcript: RollingTranscript,
        scope: kotlinx.coroutines.CoroutineScope,
        recorder: Recorder,
        audioAllowed: () -> Boolean = { true },
        settleMillis: Long = 2_500,
    ) = AmbientDecider(transcript, scope, { recorder.decide() }, audioAllowed, settleMillis)

    @Test
    fun `it does nothing until switched on`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)

        t.add("someone is talking")
        advanceUntilIdle()

        assertFalse("off by default: the thresholds are estimates", d.enabled)
        assertEquals(0, r.calls)
    }

    @Test
    fun `once on, it decides after the room goes quiet`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)

        t.add("are we still on for Thursday")
        advanceTimeBy(1_000)
        assertEquals("still mid-conversation", 0, r.calls)

        advanceTimeBy(2_000)
        advanceUntilIdle()
        assertEquals(1, r.calls)
        assertEquals(1, d.state.value.considered)

        d.setEnabled(false)
    }

    @Test
    fun `someone still talking restarts the wait rather than interrupting them`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)

        repeat(4) {
            t.add("line $it")
            advanceTimeBy(1_000)   // each new line lands before the lull elapses
            assertEquals("a thought in progress is not a gap to fill", 0, r.calls)
        }

        advanceUntilIdle()
        assertEquals("one decision for the whole exchange, not one per line", 1, r.calls)

        d.setEnabled(false)
    }

    @Test
    fun `Operator's own last word is not something to react to`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)

        t.add("The deadline moved to Thursday.", Speaker.OPERATOR)
        advanceUntilIdle()

        assertEquals("replying to itself is how a loop starts", 0, r.calls)

        d.setEnabled(false)
    }

    @Test
    fun `it does not decide when audio is not allowed`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r, audioAllowed = { false })
        d.setEnabled(true)

        t.add("someone is talking")
        advanceUntilIdle()

        assertEquals(0, r.calls)

        d.setEnabled(false)
    }

    @Test
    fun `switching off stops it mid-wait`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)

        t.add("someone is talking")
        advanceTimeBy(1_000)
        d.setEnabled(false)
        advanceUntilIdle()

        assertEquals("a pending decision is abandoned, not delivered late", 0, r.calls)
        assertFalse(d.enabled)
        assertFalse(d.state.value.waiting)
    }

    @Test
    fun `an empty window is nothing to decide about`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)

        advanceUntilIdle()
        assertEquals(0, r.calls)

        t.add("something")
        advanceUntilIdle()
        assertEquals(1, r.calls)

        t.clear()
        advanceUntilIdle()
        assertEquals("clearing the window is not new speech", 1, r.calls)

        d.setEnabled(false)
    }

    @Test
    fun `enabling twice does not start two watchers`() = runTest {
        val t = RollingTranscript()
        val r = Recorder()
        val d = decider(t, this, r)
        d.setEnabled(true)
        d.setEnabled(true)

        t.add("someone is talking")
        advanceUntilIdle()

        assertEquals(1, r.calls)

        d.setEnabled(false)
    }
}
