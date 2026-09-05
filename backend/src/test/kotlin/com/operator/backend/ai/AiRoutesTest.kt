package com.operator.backend.ai

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRoutesTest {
    private object OkDb : DatabaseGateway { override suspend fun health() = DatabaseHealth(configured = true, reachable = true) }

    private class FakeAi(
        private val reply: String = "Javier Bardem.",
        private val failure: Exception? = null,
    ) : AIProvider {
        var lastRequest: AIRequest? = null
        override suspend fun generate(request: AIRequest): AIResponse {
            lastRequest = request
            failure?.let { throw it }
            return AIResponse(reply, request.modelId, inputTokens = 40, outputTokens = 6, latencyMillis = 123)
        }
    }

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit(); File(it, "operator-system-v1.txt").writeText("You are Operator. SILENCE is the default.")
    }

    private fun ApplicationTestBuilder.setup(ai: AIProvider, config: BackendConfig = BackendConfig(operator = OperatorConfig(fastModelId = "v/fast", deepModelId = "v/deep"))): UsageTracker {
        val usage = UsageTracker()
        val deps = BackendDependencies(config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(), usage, PromptLibrary(promptDir()), ai)
        application { operatorModule(deps) }
        return usage
    }

    @Test
    fun `respond returns text with model, tier, latency and tokens, and records usage`() = testApplication {
        val ai = FakeAi()
        val usage = setup(ai)
        val response = client.post("/ai/respond") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"Who played the villain in that film?"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Javier Bardem.", body["text"]!!.jsonPrimitive.content)
        assertEquals("v/fast", body["model"]!!.jsonPrimitive.content)
        assertEquals("FAST", body["tier"]!!.jsonPrimitive.content)
        assertEquals("operator-system-v1", body["promptVersion"]!!.jsonPrimitive.content)
        assertEquals(123, body["latencyMillis"]!!.jsonPrimitive.content.toLong())
        assertEquals(40, body["inputTokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(ai.lastRequest!!.systemPrompt.contains("SILENCE"), "the versioned system prompt is sent")

        val report = usage.report()
        assertEquals(1, report.allTime.calls)
        assertEquals(46, report.allTime.inputTokens + report.allTime.outputTokens)
        assertEquals(0, report.allTime.failures)
    }

    @Test
    fun `tier is routed automatically and can be forced`() = testApplication {
        val ai = FakeAi()
        setup(ai)
        val deep = client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"Analyze whether this is a good investment"}""") }
        assertEquals("DEEP", Json.parseToJsonElement(deep.bodyAsText()).jsonObject["tier"]!!.jsonPrimitive.content)
        assertEquals("v/deep", ai.lastRequest!!.modelId)

        val forced = client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"Analyze this","tier":"fast"}""") }
        assertEquals("FAST", Json.parseToJsonElement(forced.bodyAsText()).jsonObject["tier"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.BadRequest, client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"hi","tier":"turbo"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"   "}""") }.status)
    }

    @Test
    fun `provider failure is a gateway error and is counted as a failed call`() = testApplication {
        val usage = setup(FakeAi(failure = AIProviderException("OpenRouter 429: slow down", status = 429, retryable = true)))
        val response = client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"hello"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("slow down"))
        assertEquals(1, usage.report().allTime.failures)
    }

    @Test
    fun `no configured model is a 503, not a crash`() = testApplication {
        setup(FakeAi(), BackendConfig())
        val response = client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"hello"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains(OperatorConfig.Keys.FAST_MODEL_ID))
    }

    @Test
    fun `usage endpoint reports totals and never reveals prompts`() = testApplication {
        setup(FakeAi())
        client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody("""{"prompt":"secret question about the quote","sessionId":"s-1"}""") }
        val report = client.get("/usage")
        assertEquals(HttpStatusCode.OK, report.status)
        val text = report.bodyAsText()
        assertTrue(text.contains("\"calls\": 1") || text.contains("\"calls\":1"), text.take(200))
        assertTrue(!text.contains("secret question"), "usage must not echo prompt content")
    }
}
