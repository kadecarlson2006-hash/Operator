package com.operator.backend.decision

import com.operator.backend.ai.OpenRouterProvider
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.ai.WebSearchOptions
import com.operator.core.config.OperatorConfig
import com.operator.core.decision.ConversationPolicy
import com.operator.core.decision.DecisionRequest
import com.operator.core.decision.DecisionTrigger
import com.operator.core.model.OperatorMode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two things the user asked for, as tests:
 *
 *   "Operator, what's the weather in Salina today"
 *   "Operator, did you see the Rams - Aaron Donald isn't travelling to Australia"
 *
 * Both arrive spoken, which means they reach the *decision* engine rather than the answer route.
 * Search was excluded from this path entirely, so neither could ever have been answered with
 * current information (ADR-052).
 */
class InvitedSearchTest {

    private val speaks =
        """{"shouldSpeak":true,"category":"DIRECT_REQUEST","confidence":0.9,"relevance":0.9,"response":"Sixty-eight and clear."}"""

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit()
        File(it, "operator-decision-v1.txt").writeText("decision stage")
    }

    private fun engineWith(sent: MutableList<String>): ModelDecisionEngine {
        val provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine { request ->
                sent += (request.body as io.ktor.http.content.TextContent).text
                respond(
                    """{"model":"m","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(speaks))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return ModelDecisionEngine(
            provider = provider,
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
            webSearch = WebSearchOptions(maxResults = 3),
        )
    }

    private fun spoken(text: String, trigger: DecisionTrigger = DecisionTrigger.DIRECT_ADDRESS) =
        DecisionRequest(trigger = trigger, recentTranscript = text, mode = OperatorMode.ACTIVE)

    private fun searchedIn(body: String) = Json.parseToJsonElement(body).jsonObject.containsKey("plugins")

    /** The last user message, which is what the model reads as the request and search queries on. */
    private fun userMessage(body: String): String =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .last().jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun `a spoken weather question searches`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        assertTrue(searchedIn(sent.single()), "a question about today's weather must reach live search")
    }

    @Test
    fun `a spoken question about a roster searches`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(
            spoken("Someone: operator did you see the Rams, Aaron Donald isn't traveling to Australia with the team"),
        )
        assertTrue(searchedIn(sent.single()), "news about a team must reach live search")
    }

    @Test
    fun `the question itself reaches the user message`(): Unit = runBlocking {
        // Search builds its query from the last user turn. When that read only "Trigger:
        // DIRECT_ADDRESS ... Decide now", there was nothing to search for - search would have
        // been enabled and useless.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        val content = userMessage(sent.single())
        assertTrue(content.contains("weather in Salina", ignoreCase = true), "the question is missing: $content")
    }

    @Test
    fun `the wake word is stripped so the query reads as a question`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        val content = userMessage(sent.single())
        assertFalse(
            content.contains("operator what", ignoreCase = true),
            "the address should not be part of the search query: $content",
        )
    }

    @Test
    fun `an ambient moment never searches, however current it sounds`(): Unit = runBlocking {
        // The cost argument for ADR-052: this path runs on every lull, dozens of times an hour,
        // usually to conclude nothing needs saying.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: the weather today is awful", trigger = DecisionTrigger.AMBIENT))
        assertFalse(searchedIn(sent.single()), "ambient decisions must never buy a search")
    }

    @Test
    fun `an invited question about something settled does not search`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what does ephemeral mean"))
        assertFalse(searchedIn(sent.single()), "a settled fact needs no search")
    }
}
