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
        assertTrue(searchedIn(sent.last()), "a question about today's weather must reach live search")
    }

    @Test
    fun `a spoken question about a roster searches`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(
            spoken("Someone: operator did you see the Rams, Aaron Donald isn't traveling to Australia with the team"),
        )
        assertTrue(searchedIn(sent.last()), "news about a team must reach live search")
    }

    @Test
    fun `the question itself reaches the user message`(): Unit = runBlocking {
        // Search builds its query from the last user turn. When that read only "Trigger:
        // DIRECT_ADDRESS ... Decide now", there was nothing to search for - search would have
        // been enabled and useless.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        val content = userMessage(sent.last())
        assertTrue(content.contains("weather in Salina", ignoreCase = true), "the question is missing: $content")
    }

    @Test
    fun `the wake word is stripped so the query reads as a question`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        val content = userMessage(sent.last())
        assertFalse(
            content.contains("operator what", ignoreCase = true),
            "the address should not be part of the search query: $content",
        )
    }

    @Test
    fun `an old word in the window does not buy a search for a new question`(): Unit = runBlocking {
        // The gate used to judge the whole rolling window, so anything anyone had said in the last
        // few minutes could switch search on. "still" and "now" are common enough that this was
        // close to searching on everything: seconds of wait and four times the cost, to look up a
        // question about the meaning of a word.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(
            spoken(
                """
                Someone: is the shop still open right now
                Someone: I think so
                Someone: operator what does ephemeral mean
                """.trimIndent(),
            ),
        )
        assertFalse(searchedIn(sent.single()), "only the question decides, not the conversation around it")
    }

    @Test
    fun `a current question searches even after settled small talk`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(
            spoken(
                """
                Someone: how many legs does a spider have
                Someone: eight
                Someone: operator what's the weather in Salina today
                """.trimIndent(),
            ),
        )
        assertTrue(searchedIn(sent.last()), "the newest line is the question, and it needs looking up")
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

    @Test
    fun `a search that fails is still reported as a search`(): Unit = runBlocking {
        // The first live run of the weather question came back searched=false, modelMillis=0
        // after thirty seconds - because the failure path reported nothing it had attempted. A
        // failed search and a question the gate never searched for must not look the same.
        val failing = ModelDecisionEngine(
            provider = OpenRouterProvider(
                apiKey = "k",
                engine = MockEngine { respond("""{"error":{"message":"upstream timed out"}}""", HttpStatusCode.BadGateway, headersOf("Content-Type", "application/json")) },
            ),
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
            webSearch = WebSearchOptions(maxResults = 3),
        )

        failing.decide(spoken("Someone: operator what's the weather in Salina today"))

        val outcome = failing.lastOutcome
        kotlin.test.assertEquals("MODEL_UNAVAILABLE", outcome.reasonCode)
        assertTrue(outcome.searched, "the search was attempted and must be reported")
    }

    @Test
    fun `the decision caps reasoning and leaves room to answer`(): Unit = runBlocking {
        // The first live weather question came back 200 with finish_reason=length and no content:
        // a reasoning model spent all 300 tokens thinking.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))

        val body = Json.parseToJsonElement(sent.last()).jsonObject
        val reasoning = body["reasoning"]!!.jsonObject
        kotlin.test.assertEquals("low", reasoning["effort"]!!.jsonPrimitive.content)
        kotlin.test.assertEquals("true", reasoning["exclude"]!!.jsonPrimitive.content, "reasoning is never shown, so never downloaded")
        assertTrue(body["max_tokens"]!!.jsonPrimitive.content.toInt() >= 1_000, "room to think and still answer")
    }

    @Test
    fun `when searching the user message is only the question`(): Unit = runBlocking {
        // Search queries on the last user turn. It carried "Trigger: DIRECT_ADDRESS ... Reply with
        // the JSON object only." around the question, and live the Rams story was not found.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))
        kotlin.test.assertEquals(
            "did you see the rams aaron donald isn't traveling to AUS with the rest of the team",
            userMessage(sent.last()),
        )
    }

    @Test
    fun `the framing still reaches the model when searching`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        val system = Json.parseToJsonElement(sent.last()).jsonObject["messages"]!!.jsonArray
            .first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("Trigger: DIRECT_ADDRESS"), "who asked must not be lost")
        assertTrue(system.contains("Reply with the JSON object only"), "the output contract must not be lost")
    }

    @Test
    fun `without search the message is unchanged`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator say hello"))
        val content = userMessage(sent.single())
        assertTrue(content.contains("Trigger: DIRECT_ADDRESS") && content.contains("say hello"), content)
    }

    @Test
    fun `shorthand is rewritten into a search query before searching`(): Unit = runBlocking {
        // Live, "traveling to AUS" searched for Austin. The rewrite call has no search; the
        // searched call carries the rewritten query, and the model is still told what was said.
        val sent = mutableListOf<String>()
        val provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                val reply = if (searchedIn(body)) speaks else "Aaron Donald Rams not traveling to Australia Melbourne opener 2026"
                respond(
                    """{"model":"m","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(reply))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        ModelDecisionEngine(
            provider = provider,
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
            webSearch = WebSearchOptions(maxResults = 3),
        ).decide(spoken("Someone: operator did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))

        kotlin.test.assertEquals(2, sent.size, "one rewrite, one searched answer")
        assertFalse(searchedIn(sent[0]), "the rewrite must not itself search")
        assertTrue(searchedIn(sent[1]))
        kotlin.test.assertEquals("Aaron Donald Rams not traveling to Australia Melbourne opener 2026", userMessage(sent[1]))
        val system = Json.parseToJsonElement(sent[1]).jsonObject["messages"]!!.jsonArray
            .first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("isn't traveling to AUS"), "the model must still answer what was actually said")
    }

    @Test
    fun `an unusable rewrite falls back to the words as spoken`(): Unit = runBlocking {
        // engineWith answers every call with decision JSON, which is not a query.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        kotlin.test.assertEquals("what's the weather in Salina today", userMessage(sent.last()))
    }
}
