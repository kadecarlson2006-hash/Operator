package com.operator.app.decision

import com.operator.app.backend.AskResponse
import com.operator.app.backend.BackendException
import com.operator.app.backend.DecideResponse
import com.operator.app.backend.OperatorBackend
import com.operator.app.backend.TranscribeResponse
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import com.operator.core.transcription.RollingTranscript
import com.operator.core.transcription.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DecisionControllerTest {

    private class FakeBackend(
        private val decision: DecideResponse = silent,
        private val failure: Exception? = null,
        override val configured: Boolean = true,
    ) : OperatorBackend {
        var calls = 0
        var lastTranscript: List<String> = emptyList()
        var lastRecentComments: List<String> = emptyList()
        var lastMuted: Boolean? = null
        var lastTrigger: String? = null

        override suspend fun ask(prompt: String, tier: String?, sessionId: String?, mode: String?, wit: String?, transcript: List<String>): AskResponse =
            throw UnsupportedOperationException("this fake only decides")

        override suspend fun transcribe(pcm: ByteArray, sampleRateHz: Int, channels: Int, sessionId: String?): TranscribeResponse =
            throw UnsupportedOperationException("this fake only decides")

        override suspend fun decide(
            trigger: String,
            transcript: List<String>,
            mode: String?,
            wit: String?,
            recentComments: List<String>,
            muted: Boolean,
            sessionId: String?,
        ): DecideResponse {
            calls++
            lastTrigger = trigger
            lastTranscript = transcript
            lastRecentComments = recentComments
            lastMuted = muted
            failure?.let { throw it }
            return decision
        }
    }

    private fun controller(
        backend: FakeBackend,
        transcript: RollingTranscript = RollingTranscript(),
        scope: kotlinx.coroutines.CoroutineScope,
        speak: (String) -> Boolean = { true },
        muted: Boolean = false,
    ) = DecisionController(
        backend = backend,
        transcript = transcript,
        scope = scope,
        speak = speak,
        stateSupplier = { Triple(OperatorMode.ACTIVE, WitLevel.NORMAL, muted) },
    )

    @Test
    fun `a decision to speak is spoken and joins the transcript`() = runTest {
        val backend = FakeBackend(speaks)
        val window = RollingTranscript()
        val spoken = mutableListOf<String>()
        val c = controller(backend, window, this, speak = { spoken += it; true })

        c.request("COMMENT_NOW")
        advanceUntilIdle()

        assertEquals(listOf("The deadline moved to Thursday."), spoken)
        assertEquals(listOf("The deadline moved to Thursday."), window.entries().map { it.text })
        assertEquals(Speaker.OPERATOR, window.entries().single().speaker)
        assertEquals(1, c.state.value.spokenCount)
        assertTrue(c.state.value.shouldSpeak)
    }

    @Test
    fun `silence is displayed, not treated as a failure`() = runTest {
        val backend = FakeBackend(silent)
        val spoken = mutableListOf<String>()
        val window = RollingTranscript()
        val c = controller(backend, window, this, speak = { spoken += it; true })

        c.request("AMBIENT")
        advanceUntilIdle()

        assertTrue(spoken.isEmpty())
        assertTrue("silence is not a turn in the conversation", window.entries().isEmpty())
        assertFalse(c.state.value.shouldSpeak)
        assertEquals("NOTHING_WORTH_SAYING", c.state.value.reasonCode)
        assertEquals(0, c.state.value.spokenCount)
        assertEquals(1, c.state.value.decisions)
        assertNull(c.state.value.error)
    }

    @Test
    fun `a decision to speak that speech refuses is not recorded as spoken`() = runTest {
        val window = RollingTranscript()
        val c = controller(FakeBackend(speaks), window, this, speak = { false })

        c.request("COMMENT_NOW")
        advanceUntilIdle()

        assertEquals(0, c.state.value.spokenCount)
        assertNull(c.state.value.spoken)
        assertTrue("nothing was said, so nothing is remembered as said", window.entries().isEmpty())
        assertTrue(c.state.value.error!!.contains("speech was refused"))
    }

    @Test
    fun `the window and Operator's own lines are both sent`() = runTest {
        val window = RollingTranscript()
        window.add("are we still on for Thursday", Speaker.UNKNOWN)
        window.add("The deadline moved to Thursday.", Speaker.OPERATOR)
        val backend = FakeBackend(silent)

        controller(backend, window, this).request("AMBIENT")
        advanceUntilIdle()

        assertEquals(2, backend.lastTranscript.size)
        assertTrue(backend.lastTranscript.first().startsWith("Someone: "))
        assertEquals(
            "only Operator's own lines are what it must not repeat",
            listOf("The deadline moved to Thursday."),
            backend.lastRecentComments,
        )
    }

    @Test
    fun `mute is reported to the backend rather than assumed`() = runTest {
        val backend = FakeBackend(silent)
        controller(backend, RollingTranscript(), this, muted = true).request("COMMENT_NOW")
        advanceUntilIdle()
        assertEquals(true, backend.lastMuted)
    }

    @Test
    fun `the trigger is passed through`() = runTest {
        val backend = FakeBackend(silent)
        val c = controller(backend, RollingTranscript(), this)
        c.request("AMBIENT")
        advanceUntilIdle()
        assertEquals("AMBIENT", backend.lastTrigger)
    }

    @Test
    fun `only one decision runs at a time`() = runTest {
        val backend = FakeBackend(silent)
        val c = controller(backend, RollingTranscript(), this)
        assertTrue(c.request("COMMENT_NOW"))
        assertFalse("a second request while one is in flight is refused", c.request("COMMENT_NOW"))
        advanceUntilIdle()
        assertEquals(1, backend.calls)
    }

    @Test
    fun `an unconfigured backend fails fast without calling anything`() = runTest {
        val backend = FakeBackend(silent, configured = false)
        val c = controller(backend, RollingTranscript(), this)
        assertFalse(c.request("COMMENT_NOW"))
        advanceUntilIdle()
        assertEquals(0, backend.calls)
        assertTrue(c.state.value.error!!.contains("OPERATOR_BACKEND_URL"))
    }

    @Test
    fun `a backend failure is reported and nothing is spoken`() = runTest {
        val spoken = mutableListOf<String>()
        val c = controller(FakeBackend(speaks, failure = BackendException("no route to host")), RollingTranscript(), this, speak = { spoken += it; true })

        c.request("COMMENT_NOW")
        advanceUntilIdle()

        assertEquals("no route to host", c.state.value.error)
        assertTrue(spoken.isEmpty())
        assertFalse(c.state.value.inFlight)
    }

    @Test
    fun `clear resets everything`() = runTest {
        val c = controller(FakeBackend(speaks), RollingTranscript(), this)
        c.request("COMMENT_NOW")
        advanceUntilIdle()
        assertEquals(1, c.state.value.decisions)

        c.clear()
        assertEquals(0, c.state.value.decisions)
        assertNull(c.state.value.reasonCode)
    }

    private companion object {
        val speaks = DecideResponse(
            shouldSpeak = true, category = "USEFUL_CONTEXT", reasonCode = "USEFUL_CONTEXT",
            response = "The deadline moved to Thursday.", confidence = 0.9f, relevance = 0.9f,
            modelCalled = true, model = "v/decide", latencyMillis = 40,
        )
        val silent = DecideResponse(
            shouldSpeak = false, category = "NO_RESPONSE", reasonCode = "NOTHING_WORTH_SAYING",
            modelCalled = true, model = "v/decide", latencyMillis = 30,
        )
    }
}
