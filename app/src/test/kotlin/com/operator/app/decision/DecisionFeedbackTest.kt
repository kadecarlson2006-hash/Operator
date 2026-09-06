package com.operator.app.decision

import com.operator.app.backend.AskResponse
import com.operator.app.backend.BackendException
import com.operator.app.backend.DecideResponse
import com.operator.app.backend.FeedbackResponse
import com.operator.app.backend.OperatorBackend
import com.operator.app.backend.TranscribeResponse
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import com.operator.core.transcription.RollingTranscript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Milestone 14 on the phone.
 *
 * NOTE for anyone editing: `:app` uses JUnit 4, where the message is the FIRST argument to an
 * assertion. `:core` and `:backend` use kotlin.test, where it is the last. Getting these the wrong
 * way round has broken CI three times.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecisionFeedbackTest {

    private class FakeBackend(
        private val decision: DecideResponse,
        private val feedbackFailure: Exception? = null,
        override val configured: Boolean = true,
    ) : OperatorBackend {
        var sent = 0
        var lastComment: String? = null
        var lastVerdict: String? = null
        var lastTrigger: String? = null
        var lastConfidence: Float? = null

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
        ): DecideResponse = decision

        override suspend fun sendFeedback(
            comment: String,
            verdict: String,
            trigger: String,
            confidence: Float,
            relevance: Float,
            category: String?,
            sessionId: String?,
        ): FeedbackResponse {
            sent++
            lastComment = comment
            lastVerdict = verdict
            lastTrigger = trigger
            lastConfidence = confidence
            feedbackFailure?.let { throw it }
            return FeedbackResponse(id = "1", verdict = verdict, penalty = 0.05f, note = "recorded")
        }
    }

    private fun controller(backend: FakeBackend, scope: CoroutineScope) = DecisionController(
        backend = backend,
        transcript = RollingTranscript(),
        scope = scope,
        speak = { true },
        stateSupplier = { Triple(OperatorMode.ACTIVE, WitLevel.NORMAL, false) },
    )

    @Test
    fun `a verdict is sent with the scores the comment carried`() = runTest {
        val backend = FakeBackend(speaks)
        val c = controller(backend, this)
        c.request("AMBIENT")
        advanceUntilIdle()

        c.sendFeedback("UNWANTED")
        advanceUntilIdle()

        assertEquals("one verdict should have been sent", 1, backend.sent)
        assertEquals("The deadline moved to Thursday.", backend.lastComment)
        assertEquals("UNWANTED", backend.lastVerdict)
        assertEquals("AMBIENT", backend.lastTrigger)
        // The scores are what make the floors measurable later (risk 44), so they must travel
        // with the verdict rather than being recomputed from anything.
        assertEquals(0.9, backend.lastConfidence!!.toDouble(), 0.001)
        assertEquals("UNWANTED", c.state.value.feedbackSent)
    }

    @Test
    fun `a second tap on the same comment is ignored`() = runTest {
        val backend = FakeBackend(speaks)
        val c = controller(backend, this)
        c.request("AMBIENT")
        advanceUntilIdle()

        c.sendFeedback("UNWANTED")
        c.sendFeedback("HELPFUL")
        advanceUntilIdle()

        assertEquals("one comment should produce at most one verdict", 1, backend.sent)
        assertEquals("UNWANTED", backend.lastVerdict)
    }

    @Test
    fun `silence cannot be judged`() = runTest {
        // A decision that never reached the user's ears is not a thing they can have an opinion
        // about, and a verdict on it would describe something that did not happen.
        val backend = FakeBackend(silent)
        val c = controller(backend, this)
        c.request("AMBIENT")
        advanceUntilIdle()

        c.sendFeedback("UNWANTED")
        advanceUntilIdle()

        assertEquals("nothing was said, so nothing should be sent", 0, backend.sent)
        assertNull(c.state.value.feedbackSent)
    }

    @Test
    fun `a failed send puts the buttons back rather than claiming success`() = runTest {
        val backend = FakeBackend(speaks, feedbackFailure = BackendException("unreachable"))
        val c = controller(backend, this)
        c.request("AMBIENT")
        advanceUntilIdle()

        c.sendFeedback("UNWANTED")
        advanceUntilIdle()

        assertEquals(1, backend.sent)
        assertNull("a failed verdict must not look recorded", c.state.value.feedbackSent)
    }

    @Test
    fun `a new decision clears the earlier verdict`() = runTest {
        val backend = FakeBackend(speaks)
        val c = controller(backend, this)
        c.request("AMBIENT")
        advanceUntilIdle()
        c.sendFeedback("HELPFUL")
        advanceUntilIdle()
        assertEquals("HELPFUL", c.state.value.feedbackSent)

        c.request("COMMENT_NOW")
        advanceUntilIdle()

        assertNull("a new comment is a new thing to judge", c.state.value.feedbackSent)
    }

    private companion object {
        val speaks = DecideResponse(
            shouldSpeak = true, category = "USEFUL_CONTEXT", reasonCode = "USEFUL_CONTEXT",
            response = "The deadline moved to Thursday.", confidence = 0.9f, relevance = 0.8f,
            modelCalled = true,
        )
        val silent = DecideResponse(
            shouldSpeak = false, category = "NO_RESPONSE", reasonCode = "NOTHING_WORTH_SAYING",
            gatedLocally = true,
        )
    }
}
