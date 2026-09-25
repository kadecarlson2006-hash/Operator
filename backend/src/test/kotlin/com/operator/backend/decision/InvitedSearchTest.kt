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
        // Searched, so minimal: the results carry the facts (see searchReasoningEffort).
        kotlin.test.assertEquals("minimal", reasoning["effort"]!!.jsonPrimitive.content)
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
        assertTrue(system.contains("never contradict them, or what was said, from memory"), "search results must outrank memory")
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
        engineWith(sent).decide(spoken("Someone: operator did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))
        kotlin.test.assertEquals(2, sent.size, "the rewrite was attempted")
        kotlin.test.assertEquals(
            "did you see the rams aaron donald isn't traveling to AUS with the rest of the team",
            userMessage(sent.last()),
        )
    }

    @Test
    fun `today is the user's date, not UTC's`(): Unit = runBlocking {
        // Live at 11pm on Thursday in Kansas, UTC had already reached Friday, and "the weather in
        // Salina today" came back with Friday's forecast.
        val sent = mutableListOf<String>()
        val provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                val reply = if (searchedIn(body)) speaks else "Salina Kansas weather today"
                respond(
                    """{"model":"m","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(reply))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        val engine = ModelDecisionEngine(
            provider = provider,
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
            webSearch = WebSearchOptions(maxResults = 3),
            clock = { java.time.Instant.parse("2026-09-25T04:04:00Z").toEpochMilli() },
            zone = java.time.ZoneId.of("America/Chicago"),
        )
        fun system(body: String) = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .first().jsonObject["content"]!!.jsonPrimitive.content

        // The weather question is searched as spoken, so the answer is the only call.
        engine.decide(spoken("Someone: operator what's the weather in Salina today"))
        kotlin.test.assertEquals(1, sent.size)
        assertTrue(system(sent.single()).contains("Thursday, September 24, 2026"), system(sent.single()))
        assertTrue(system(sent.single()).contains("11:04 PM"), "at 11pm, today's weather is tonight's")

        // A question that is rewritten gets the user's date in the rewrite too.
        sent.clear()
        engine.decide(spoken("Someone: operator did you see the rams game got moved to AUS"))
        kotlin.test.assertEquals(2, sent.size, "one rewrite, one searched answer")
        sent.forEach { assertTrue(system(it).contains("Thursday, September 24, 2026"), system(it)) }
    }

    private fun engineReplying(reply: String): ModelDecisionEngine {
        val provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine {
                respond(
                    """{"model":"m","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(reply))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return ModelDecisionEngine(
            provider = provider,
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide", searchQueryRewrite = false),
            webSearch = WebSearchOptions(maxResults = 3),
        )
    }

    private val prose = "Yes - ESPN reported on **September 8, 2026**, that Aaron Donald would not travel to Melbourne. " +
        "([espn.com](https://www.espn.com/nfl/story/_/id/1/rams-aaron-donald))"

    @Test
    fun `a prose answer to a direct question is spoken, not dropped`(): Unit = runBlocking {
        // Live, two Rams replies in eight skipped the JSON and just answered, correctly.
        val decision = engineReplying(prose)
            .decide(spoken("Someone: operator did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))
        assertTrue(decision.shouldSpeak, decision.reasonCode)
        kotlin.test.assertEquals(
            "Yes - ESPN reported on September 8, 2026, that Aaron Donald would not travel to Melbourne.",
            decision.response,
        )
    }

    @Test
    fun `an ambient prose reply is still silence`(): Unit = runBlocking {
        val decision = engineReplying(prose).decide(spoken("Someone: the rams play tonight", trigger = DecisionTrigger.AMBIENT))
        assertFalse(decision.shouldSpeak)
    }

    @Test
    fun `a broken JSON reply is never read aloud`(): Unit = runBlocking {
        val decision = engineReplying("""{"shouldSpeak":true,"response":"Sixty""")
            .decide(spoken("Someone: operator what's the weather in Salina today"))
        assertFalse(decision.shouldSpeak)
        kotlin.test.assertEquals("UNREADABLE_DECISION", decision.reasonCode)
    }

    @Test
    fun `a plain question is searched without a rewrite`(): Unit = runBlocking {
        // One round trip instead of two: the weather question was a good query as spoken.
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator what's the weather in Salina today"))
        kotlin.test.assertEquals(1, sent.size, "no rewrite call")
        assertTrue(searchedIn(sent.single()))
        kotlin.test.assertEquals("what's the weather in Salina today", userMessage(sent.single()))
    }

    @Test
    fun `an unsearched decision keeps the ordinary reasoning effort`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        engineWith(sent).decide(spoken("Someone: operator say hello"))
        val reasoning = Json.parseToJsonElement(sent.single()).jsonObject["reasoning"]!!.jsonObject
        kotlin.test.assertEquals("low", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a slow rewrite is abandoned and the words are searched as spoken`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                if (!searchedIn(body)) kotlinx.coroutines.delay(10_000) // the rewrite never returns in time
                respond(
                    """{"model":"m","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(speaks))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        val engine = ModelDecisionEngine(
            provider = provider,
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide"),
            webSearch = WebSearchOptions(maxResults = 3),
        )
        val started = System.currentTimeMillis()
        val decision = engine.decide(spoken("Someone: operator did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))
        val took = System.currentTimeMillis() - started

        assertTrue(decision.shouldSpeak, "the answer still arrives")
        assertTrue(took < 6_000, "waited $took ms; the rewrite should be cut off at about 3.5 seconds")
        kotlin.test.assertEquals(
            "did you see the rams aaron donald isn't traveling to AUS with the rest of the team",
            userMessage(sent.last()),
        )
        assertTrue(engine.lastOutcome.rewriteMillis in 3_000..5_500, "rewrite time is reported: ${engine.lastOutcome.rewriteMillis}")
    }

    private fun fallbackEngine(sent: MutableList<String>, fallbacks: List<String>, answeredBy: String = "m") = ModelDecisionEngine(
        provider = OpenRouterProvider(
            apiKey = "k",
            engine = MockEngine { request ->
                sent += (request.body as io.ktor.http.content.TextContent).text
                respond(
                    """{"model":"$answeredBy","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(speaks))}}}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            },
        ),
        policy = ConversationPolicy(0, 100, 0, 1_000),
        prompts = PromptLibrary(promptDir()),
        config = OperatorConfig(decisionModelId = "v/decide", fastModelId = "v/fast", decisionFallbackModelIds = fallbacks),
        webSearch = WebSearchOptions(maxResults = 3),
    )

    private fun fallbacksIn(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["models"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun `the configured fallback is sent with the decision`(): Unit = runBlocking {
        // Live, the decision model was rate-limited upstream on 3 of 8 calls in ten minutes.
        val sent = mutableListOf<String>()
        val engine = fallbackEngine(sent, listOf("v/fallback"), answeredBy = "v/fallback")
        engine.decide(spoken("Someone: operator say hello"))
        kotlin.test.assertEquals(listOf("v/fallback"), fallbacksIn(sent.single()))
        kotlin.test.assertEquals("v/fallback", engine.lastOutcome.modelId, "the panel must show which model actually answered")
    }

    @Test
    fun `a decision that fails mid-answer is asked of the fallback directly`(): Unit = runBlocking {
        // Live: a 200 whose only choice ended finish_reason "error". OpenRouter's `models` list
        // does not cover that, so the engine asks the fallback itself - once, and with search on.
        val sent = mutableListOf<String>()
        val failed = """{"model":"v/decide","choices":[{"index":0,"finish_reason":"error","message":{"role":"assistant","content":""}}]}"""
        val answered = """{"model":"v/fallback","choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(speaks))}}}]}"""
        val engine = ModelDecisionEngine(
            provider = OpenRouterProvider(
                apiKey = "k",
                engine = MockEngine { request ->
                    sent += (request.body as io.ktor.http.content.TextContent).text
                    respond(if (sent.size == 1) failed else answered, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                },
            ),
            policy = ConversationPolicy(0, 100, 0, 1_000),
            prompts = PromptLibrary(promptDir()),
            config = OperatorConfig(decisionModelId = "v/decide", decisionFallbackModelIds = listOf("v/fallback"), searchQueryRewrite = false),
            webSearch = WebSearchOptions(maxResults = 3),
        )
        val decision = engine.decide(spoken("Someone: operator what's the weather in Salina today"))

        assertTrue(decision.shouldSpeak, decision.reasonCode)
        kotlin.test.assertEquals(2, sent.size, "one retry, not a loop")
        kotlin.test.assertEquals("v/fallback", Json.parseToJsonElement(sent[1]).jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(searchedIn(sent[1]), "the retry still searches")
        kotlin.test.assertEquals("v/fallback", engine.lastOutcome.modelId)
    }

    @Test
    fun `no fallback unless one is configured, and never the fast model by default`(): Unit = runBlocking {
        // The fast model leaked its reasoning into the reply when tried as a decision model.
        val sent = mutableListOf<String>()
        fallbackEngine(sent, emptyList()).decide(spoken("Someone: operator say hello"))
        assertTrue(fallbacksIn(sent.single()).isEmpty())
    }
}
