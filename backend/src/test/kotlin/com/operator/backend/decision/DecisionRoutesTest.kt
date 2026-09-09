package com.operator.backend.decision

import com.operator.backend.BackendDependencies
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.memory.FakeEmbeddings
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import com.operator.backend.usage.UsageTracker
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.config.OperatorConfig
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionRoutesTest {

    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    private class FakeAi(private val reply: String) : AIProvider {
        var calls = 0
        override suspend fun generate(request: AIRequest): AIResponse {
            calls++
            return AIResponse(reply, request.modelId, latencyMillis = 15)
        }
    }

    private lateinit var usage: UsageTracker

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit()
        File(it, "operator-system-v1.txt").writeText("You are Operator. SILENCE is the default.")
        File(it, "operator-decision-v1.txt").writeText("You are the decision stage. SILENCE IS THE DEFAULT.")
    }

    private fun ApplicationTestBuilder.setup(ai: AIProvider) {
        val config = BackendConfig(operator = OperatorConfig(fastModelId = "v/fast", decisionModelId = "v/decide"))
        usage = UsageTracker()
        val deps = BackendDependencies(
            config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(),
            usage = usage, prompts = PromptLibrary(promptDir()), ai = ai, embeddings = FakeEmbeddings(),
        )
        application { operatorModule(deps) }
    }

    private suspend fun decide(client: io.ktor.client.HttpClient, body: String) =
        client.post("/decide") { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `an ambient moment worth commenting on returns a comment`() = testApplication {
        setup(FakeAi(SPEAK_JSON))
        val response = decide(client, """{"trigger":"AMBIENT","transcript":["Someone: the deadline is Thursday"]}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["shouldSpeak"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("The deadline moved to Thursday.", body["response"]!!.jsonPrimitive.content)
        assertEquals("USEFUL_CONTEXT", body["category"]!!.jsonPrimitive.content)
        assertTrue(body["modelCalled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("v/decide", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `silence is a normal 200, not an error`() = testApplication {
        setup(FakeAi(SILENT_JSON))
        val response = decide(client, """{"trigger":"AMBIENT","transcript":["Someone: nice weather"]}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["shouldSpeak"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NO_RESPONSE", body["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a locally gated moment says so and costs no model call`() = testApplication {
        val ai = FakeAi(SPEAK_JSON)
        setup(ai)
        val response = decide(client, """{"trigger":"AMBIENT","transcript":["Someone: hello"],"mode":"QUIET"}""")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["shouldSpeak"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("MODE_DOES_NOT_VOLUNTEER", body["reasonCode"]!!.jsonPrimitive.content)
        assertTrue(body["gatedLocally"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(body["modelCalled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, ai.calls)
    }

    @Test
    fun `muted refuses even an explicit COMMENT NOW`() = testApplication {
        val ai = FakeAi(SPEAK_JSON)
        setup(ai)
        val body = Json.parseToJsonElement(
            decide(client, """{"trigger":"COMMENT_NOW","transcript":["x"],"muted":true}""").bodyAsText(),
        ).jsonObject
        assertEquals("MUTED", body["reasonCode"]!!.jsonPrimitive.content)
        assertEquals(0, ai.calls)
    }

    @Test
    fun `a comment Operator just made is not repeated`() = testApplication {
        setup(FakeAi(SPEAK_JSON))
        val body = Json.parseToJsonElement(
            decide(
                client,
                """{"trigger":"COMMENT_NOW","transcript":["Someone: and the deadline?"],
                    "recentComments":["The deadline moved to Thursday."]}""",
            ).bodyAsText(),
        ).jsonObject
        assertFalse(body["shouldSpeak"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ALREADY_SAID", body["reasonCode"]!!.jsonPrimitive.content)
        assertTrue(body["suppressedAfterModel"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an unknown trigger, mode or wit is rejected`() = testApplication {
        setup(FakeAi(SPEAK_JSON))
        assertEquals(HttpStatusCode.BadRequest, decide(client, """{"trigger":"WHENEVER"}""").status)
        assertEquals(HttpStatusCode.BadRequest, decide(client, """{"trigger":"AMBIENT","mode":"LOUD"}""").status)
        assertEquals(HttpStatusCode.BadRequest, decide(client, """{"trigger":"AMBIENT","wit":"HILARIOUS"}""").status)
    }

    @Test
    fun `only model calls are billed, and they show up in usage`() = testApplication {
        setup(FakeAi(SPEAK_JSON))
        // Gated locally: no model call, so nothing recorded. QUIET rather than STANDBY, which
        // volunteers occasionally since ADR-050 - QUIET is now the mode that never does.
        decide(client, """{"trigger":"AMBIENT","transcript":["x"],"mode":"QUIET"}""")
        assertEquals(0, usage.report().allTime.calls)

        decide(client, """{"trigger":"COMMENT_NOW","transcript":["Someone: the deadline?"]}""")
        val report = Json.parseToJsonElement(client.get("/usage").bodyAsText()).jsonObject
        val recent = report["recent"]!!.jsonArray
        assertEquals(1, recent.size)
        assertEquals("decision", recent[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("v/decide", recent[0].jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an oversized transcript is capped before it reaches the model`() = testApplication {
        setup(FakeAi(SILENT_JSON))
        val lines = (1..300).joinToString(",") { "\"Someone: line $it\"" }
        assertEquals(
            HttpStatusCode.OK,
            decide(client, """{"trigger":"COMMENT_NOW","transcript":[$lines]}""").status,
        )
    }

    private companion object {
        const val SPEAK_JSON = """{"shouldSpeak": true, "category": "USEFUL_CONTEXT", "confidence": 0.9,
            "urgency": 0.4, "relevance": 0.9, "response": "The deadline moved to Thursday.",
            "reasonCode": "USEFUL_CONTEXT"}"""
        const val SILENT_JSON = """{"shouldSpeak": false, "category": "NO_RESPONSE", "confidence": 0.0,
            "urgency": 0.0, "relevance": 0.0, "response": null, "reasonCode": "NOTHING_WORTH_SAYING"}"""
    }
}
