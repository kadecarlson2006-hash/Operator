package com.operator.backend.decision

import com.operator.backend.ai.PromptLibrary
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.config.OperatorConfig
import com.operator.core.decision.ConversationPolicy
import com.operator.core.decision.DecisionRequest
import com.operator.core.decision.DecisionTrigger
import com.operator.core.model.OperatorMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * Risk 50: a silent decision used to report confidence 1.00 and relevance 0.00 whatever the model
 * said. Those constants look like measurements, and were read as such during a live session -
 * "confidence is 1.00 a lot but relevance 0.00" - which is exactly the wrong basis for tuning a
 * threshold.
 */
class ModelDecisionEngineScoresTest {

    private class FakeAi(private val reply: String) : AIProvider {
        override suspend fun generate(request: AIRequest) = AIResponse(reply, request.modelId, latencyMillis = 1)
    }

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit()
        File(it, "operator-decision-v1.txt").writeText("decision stage")
    }

    private fun engine(reply: String) = ModelDecisionEngine(
        provider = FakeAi(reply),
        policy = ConversationPolicy(0, 100, 0, 1_000),
        prompts = PromptLibrary(promptDir()),
        config = OperatorConfig(decisionModelId = "v/decide", fastModelId = "v/fast"),
    )

    private fun ambient() = DecisionRequest(
        trigger = DecisionTrigger.AMBIENT,
        recentTranscript = "Someone: the deadline is Thursday",
        mode = OperatorMode.STANDBY,
    )

    @Test
    fun `a model that declines keeps its own scores`(): Unit = runBlocking {
        val decision = engine(
            """{"shouldSpeak":false,"category":"NO_RESPONSE","confidence":0.41,"relevance":0.38,"urgency":0.12,"reasonCode":"NOTHING_WORTH_SAYING"}""",
        ).decide(ambient())

        assertFalse(decision.shouldSpeak)
        // 0.38 relevance says it nearly had something. 0.00 would have said it saw nothing at all,
        // and those call for different responses from whoever is tuning the floors.
        assertEquals(0.41f, decision.confidence, 0.001f)
        assertEquals(0.38f, decision.relevance, 0.001f)
        assertEquals(0.12f, decision.urgency, 0.001f)
        assertEquals("NOTHING_WORTH_SAYING", decision.reasonCode)
    }

    @Test
    fun `a locally gated decision is still certain of its silence`(): Unit = runBlocking {
        // No model was asked, so there are no model scores to keep. Confidence 1 is honest here:
        // the rules are not in doubt.
        val policy = ConversationPolicy(0, 100, 0, 1_000)
        val decision = ModelDecisionEngine(
            provider = FakeAi("unused"),
            policy = policy,
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
        ).decide(ambient().copy(muted = true))

        assertFalse(decision.shouldSpeak)
        assertEquals("MUTED", decision.reasonCode)
        assertEquals(1f, decision.confidence)
    }

    @Test
    fun `out of range scores from the model are clamped rather than trusted`(): Unit = runBlocking {
        val decision = engine(
            """{"shouldSpeak":false,"confidence":9.5,"relevance":-3.0,"reasonCode":"ODD"}""",
        ).decide(ambient())

        assertEquals(1f, decision.confidence)
        assertEquals(0f, decision.relevance)
    }
}
