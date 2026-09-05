package com.operator.backend.ai

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.memory.DEFAULT_USER_ID
import com.operator.backend.memory.FakeEmbeddings
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.memory.MemorySearch
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.config.OperatorConfig
import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Milestone 7 acceptance scenario from the brief, end to end over HTTP with fakes. */
class MemoryAwareAiRoutesTest {

    private object OkDb : DatabaseGateway { override suspend fun health() = DatabaseHealth(configured = true, reachable = true) }

    private class RecordingAi(private val reply: String = "Chris.") : AIProvider {
        var lastRequest: AIRequest? = null
        var calls = 0
        override suspend fun generate(request: AIRequest): AIResponse {
            calls++
            lastRequest = request
            return AIResponse(reply, request.modelId, inputTokens = 30, outputTokens = 3, latencyMillis = 90)
        }
    }

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit(); File(it, "operator-system-v1.txt").writeText("You are Operator. SILENCE is the default.")
    }

    private fun ApplicationTestBuilder.setup(ai: AIProvider, store: InMemoryMemoryStore = InMemoryMemoryStore()): InMemoryMemoryStore {
        val config = BackendConfig(operator = OperatorConfig(fastModelId = "v/fast"))
        val deps = BackendDependencies(
            config, OkDb, ProviderRegistry(config), store,
            prompts = PromptLibrary(promptDir()), ai = ai, embeddings = FakeEmbeddings(),
        )
        application { operatorModule(deps) }
        return store
    }

    private suspend fun post(client: io.ktor.client.HttpClient, body: String) =
        client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `remember that Chris handles the west, then who handles the west`() = testApplication {
        val ai = RecordingAi()
        val store = setup(ai)

        // 1. The explicit command is stored without a model call.
        val stored = post(client, """{"prompt":"Remember that Chris handles the west."}""")
        assertEquals(HttpStatusCode.OK, stored.status)
        val storedBody = Json.parseToJsonElement(stored.bodyAsText()).jsonObject
        val written = storedBody["memoryWritten"]!!.jsonObject
        assertEquals("Chris handles the west", written["content"]!!.jsonPrimitive.content)
        assertTrue(written["embedded"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", storedBody["model"]!!.jsonPrimitive.content)
        assertEquals(0, ai.calls, "storing a memory must not cost a model call")
        assertTrue(storedBody["text"]!!.jsonPrimitive.content.length < 30)

        // 2. The later question retrieves it and puts it in front of the model.
        val asked = post(client, """{"prompt":"Who handles the west?"}""")
        assertEquals(HttpStatusCode.OK, asked.status)
        val askedBody = Json.parseToJsonElement(asked.bodyAsText()).jsonObject
        assertEquals("Chris.", askedBody["text"]!!.jsonPrimitive.content)
        assertEquals(1, ai.calls)

        val used = askedBody["memoriesUsed"]!!.jsonArray
        assertEquals(1, used.size)
        assertEquals("Chris handles the west", used[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(used[0].jsonObject["why"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(askedBody["semanticRetrieval"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull(askedBody["retrievalMillis"])

        val systemPrompt = ai.lastRequest!!.systemPrompt
        assertTrue(systemPrompt.contains("SILENCE"), "the personality prompt is still sent")
        assertTrue(systemPrompt.contains("Chris handles the west"), "the memory reached the model")
        assertTrue(systemPrompt.contains("NOT live data"))

        // 3. It really persisted.
        runBlocking {
            val rows = store.search(DEFAULT_USER_ID, MemorySearch(text = "west"))
            assertEquals(1, rows.size)
            assertEquals(0.95f, rows.first().confidence)
        }
    }

    @Test
    fun `an unrelated question sends no memories and says so`() = testApplication {
        val ai = RecordingAi("Lisbon.")
        setup(ai)
        post(client, """{"prompt":"Remember that Chris handles the west."}""")
        val response = post(client, """{"prompt":"What is the capital of Portugal?"}""")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, body["memoriesUsed"]!!.jsonArray.size)
        assertTrue(ai.lastRequest!!.systemPrompt.contains("Do not invent any."))
    }

    @Test
    fun `mode decides which memories may be read`() = testApplication {
        val ai = RecordingAi()
        val store = InMemoryMemoryStore()
        runBlocking {
            store.create(DEFAULT_USER_ID, com.operator.backend.memory.NewMemory(MemoryType.WORK_FACT, "The dashboard shows late orders first", privacyScope = PrivacyScope.WORK))
        }
        setup(ai, store)

        val social = post(client, """{"prompt":"What does the dashboard show?","mode":"SOCIAL"}""")
        assertEquals(0, Json.parseToJsonElement(social.bodyAsText()).jsonObject["memoriesUsed"]!!.jsonArray.size)
        assertFalse(ai.lastRequest!!.systemPrompt.contains("late orders"))

        val work = post(client, """{"prompt":"What does the dashboard show?","mode":"WORK"}""")
        assertEquals(1, Json.parseToJsonElement(work.bodyAsText()).jsonObject["memoriesUsed"]!!.jsonArray.size)
        assertTrue(ai.lastRequest!!.systemPrompt.contains("late orders"))
        assertTrue(ai.lastRequest!!.systemPrompt.contains("Mode: WORK"))
    }

    @Test
    fun `useMemory false skips retrieval and the write path entirely`() = testApplication {
        val ai = RecordingAi()
        val store = setup(ai)
        val response = post(client, """{"prompt":"Remember that Chris handles the west.","useMemory":false}""")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["memoryWritten"] == null || body["memoryWritten"].toString() == "null")
        assertEquals(1, ai.calls, "with memory off this is just an ordinary prompt")
        runBlocking { assertEquals(0, store.count(DEFAULT_USER_ID)) }
    }

    @Test
    fun `an invalid mode or wit is rejected`() = testApplication {
        setup(RecordingAi())
        assertEquals(HttpStatusCode.BadRequest, post(client, """{"prompt":"hi","mode":"SLEEPY"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(client, """{"prompt":"hi","wit":"HILARIOUS"}""").status)
    }
}
