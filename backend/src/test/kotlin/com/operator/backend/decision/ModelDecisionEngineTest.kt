package com.operator.backend.decision

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.PromptLibrary
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.config.OperatorConfig
import com.operator.core.decision.ConversationPolicy
import com.operator.core.decision.DecisionRequest
import com.operator.core.decision.DecisionTrigger
import com.operator.core.decision.ResponseCategory
import com.operator.core.model.OperatorMode
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDecisionEngineTest {

    private class FakeAi(private val reply: String = SPEAK_JSON, private val failure: Exception? = null) : AIProvider {
        var calls = 0
        var lastRequest: AIRequest? = null
        override suspend fun generate(request: AIRequest): AIResponse {
            calls++
            lastRequest = request
            failure?.let { throw it }
            return AIResponse(reply, request.modelId, latencyMillis = 20)
        }
    }

    private fun prompts(): PromptLibrary {
        val dir = Files.createTempDirectory("decision-prompts").toFile()
        dir.deleteOnExit()
        File(dir, "operator-decision-v1.txt").writeText("You are the decision stage. SILENCE IS THE DEFAULT.")
        return PromptLibrary(dir)
    }

    private fun engine(
        ai: AIProvider,
        policy: ConversationPolicy = ConversationPolicy(45, 3),
        config: OperatorConfig = OperatorConfig(decisionModelId = "v/decide", fastModelId = "v/fast"),
    ) = ModelDecisionEngine(ai, policy, prompts(), config)

    private fun ambient(transcript: String = "the deadline is thursday", mode: OperatorMode = OperatorMode.ACTIVE) =
        DecisionRequest(DecisionTrigger.AMBIENT, transcript, mode = mode)

    @Test
    fun `a moment the local rules refuse never reaches the model`() = runTest {
        val ai = FakeAi()
        val decision = engine(ai).decide(ambient(mode = OperatorMode.QUIET))

        assertFalse(decision.shouldSpeak)
        assertEquals("MODE_DOES_NOT_VOLUNTEER", decision.reasonCode)
        assertEquals(0, ai.calls, "silence must be free; a refused moment costs no model call")
    }

    @Test
    fun `mute is refused before the model, even for COMMENT NOW`() = runTest {
        val ai = FakeAi()
        val decision = engine(ai).decide(DecisionRequest(DecisionTrigger.COMMENT_NOW, "x", muted = true))
        assertEquals("MUTED", decision.reasonCode)
        assertEquals(0, ai.calls)
    }

    @Test
    fun `a good comment survives and is recorded as spoken`() = runTest {
        val ai = FakeAi()
        val policy = ConversationPolicy(45, 3)
        val e = engine(ai, policy)

        val decision = e.decide(ambient())
        assertTrue(decision.shouldSpeak)
        assertEquals("The deadline moved to Thursday.", decision.response)
        assertEquals(ResponseCategory.USEFUL_CONTEXT, decision.category)
        assertEquals(1, ai.calls)
        assertTrue(e.lastOutcome.modelCalled)
        assertEquals("v/decide", e.lastOutcome.modelId)

        // Having spoken, the interval now applies.
        assertEquals("RECENTLY_SPOKE", e.decide(ambient()).reasonCode)
    }

    @Test
    fun `the model is told to stay silent and that is respected`() = runTest {
        val ai = FakeAi(SILENT_JSON)
        val decision = engine(ai).decide(ambient())
        assertFalse(decision.shouldSpeak)
        assertEquals("NOTHING_WORTH_SAYING", decision.reasonCode)
        assertNull(decision.response)
    }

    @Test
    fun `a model that wants to speak can still be overruled locally`() = runTest {
        val ai = FakeAi(LOW_CONFIDENCE_JSON)
        val e = engine(ai)
        val decision = e.decide(ambient())

        assertFalse(decision.shouldSpeak, "the model's shouldSpeak is a suggestion, not an instruction")
        assertEquals("LOW_CONFIDENCE", decision.reasonCode)
        assertTrue(e.lastOutcome.suppressedAfterModel)
    }

    @Test
    fun `speak with no text is read as silence rather than throwing`() = runTest {
        val ai = FakeAi("""{"shouldSpeak": true, "category": "HUMOR", "confidence": 0.9, "response": null}""")
        val decision = engine(ai).decide(ambient())
        assertFalse(decision.shouldSpeak)
        assertNull(decision.response)
    }

    @Test
    fun `JSON wrapped in prose or fences is still read`() = runTest {
        val wrapped = "Sure, here is my decision:\n```json\n$SPEAK_JSON\n```\nHope that helps."
        assertTrue(engine(FakeAi(wrapped)).decide(ambient()).shouldSpeak)
    }

    @Test
    fun `an unreadable reply is silence, not a crash`() = runTest {
        for (junk in listOf("I think you should say something!", "", "{ not json at all ")) {
            val decision = engine(FakeAi(junk)).decide(ambient())
            assertFalse(decision.shouldSpeak, "junk reply: $junk")
        }
    }

    @Test
    fun `an unavailable model is silence, not an error`() = runTest {
        val ai = FakeAi(failure = AIProviderException("upstream down", status = 503, retryable = true))
        val decision = engine(ai).decide(ambient())
        assertFalse(decision.shouldSpeak)
        assertEquals("MODEL_UNAVAILABLE", decision.reasonCode)
    }

    @Test
    fun `out-of-range scores are clamped rather than rejected`() = runTest {
        val ai = FakeAi("""{"shouldSpeak": true, "category": "WARNING", "confidence": 5.0, "urgency": -2.0, "relevance": 1.5, "response": "Careful.", "reasonCode": "X"}""")
        val decision = engine(ai).decide(ambient())
        assertEquals(1f, decision.confidence)
        assertEquals(0f, decision.urgency)
        assertEquals(1f, decision.relevance)
    }

    @Test
    fun `the decision model is used, falling back to fast when unset`() = runTest {
        val ai = FakeAi()
        engine(ai, config = OperatorConfig(decisionModelId = null, fastModelId = "v/fast")).decide(ambient())
        assertEquals("v/fast", ai.lastRequest!!.modelId)

        val ai2 = FakeAi()
        engine(ai2, config = OperatorConfig(decisionModelId = "v/decide", fastModelId = "v/fast")).decide(ambient())
        assertEquals("v/decide", ai2.lastRequest!!.modelId)
    }

    @Test
    fun `no model configured at all is reported rather than guessed`() = runTest {
        val e = engine(FakeAi(), config = OperatorConfig())
        assertFailsWith<DecisionModelNotConfiguredException> { e.decide(ambient()) }
    }

    @Test
    fun `the decision prompt and the transcript both reach the model`() = runTest {
        val ai = FakeAi()
        engine(ai).decide(ambient("chris said the west region is behind"))
        val prompt = ai.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("SILENCE IS THE DEFAULT"), prompt)
        assertTrue(prompt.contains("chris said the west region is behind"), prompt)
        assertTrue(ai.lastRequest!!.userContent.contains("AMBIENT"))
    }

    private companion object {
        const val SPEAK_JSON = """{"shouldSpeak": true, "category": "USEFUL_CONTEXT", "confidence": 0.9,
            "urgency": 0.4, "relevance": 0.9, "response": "The deadline moved to Thursday.",
            "reasonCode": "USEFUL_CONTEXT"}"""
        const val SILENT_JSON = """{"shouldSpeak": false, "category": "NO_RESPONSE", "confidence": 0.0,
            "urgency": 0.0, "relevance": 0.0, "response": null, "reasonCode": "NOTHING_WORTH_SAYING"}"""
        const val LOW_CONFIDENCE_JSON = """{"shouldSpeak": true, "category": "HUMOR", "confidence": 0.2,
            "urgency": 0.1, "relevance": 0.9, "response": "Something mildly amusing.", "reasonCode": "HUMOR"}"""
    }
}
