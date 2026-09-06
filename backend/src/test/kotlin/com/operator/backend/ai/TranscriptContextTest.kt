package com.operator.backend.ai

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.memory.FakeEmbeddings
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.config.OperatorConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Milestone 11: the phone's rolling window reaches the model as conversation, not as data. */
class TranscriptContextTest {

    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    private class RecordingAi : AIProvider {
        var lastRequest: AIRequest? = null
        override suspend fun generate(request: AIRequest): AIResponse {
            lastRequest = request
            return AIResponse("Thursday.", request.modelId, inputTokens = 20, outputTokens = 2, latencyMillis = 40)
        }
    }

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit(); File(it, "operator-system-v1.txt").writeText("You are Operator. SILENCE is the default.")
    }

    private fun ApplicationTestBuilder.setup(ai: AIProvider) {
        val config = BackendConfig(operator = OperatorConfig(fastModelId = "v/fast"))
        val deps = BackendDependencies(
            config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(),
            prompts = PromptLibrary(promptDir()), ai = ai, embeddings = FakeEmbeddings(),
        )
        application { operatorModule(deps) }
    }

    private suspend fun ask(client: io.ktor.client.HttpClient, body: String) =
        client.post("/ai/respond") { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `the transcript reaches the model labelled as conversation`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val response = ask(
            client,
            """{"prompt":"What day did we settle on?","transcript":[
                 "Someone: are we still on for Thursday",
                 "Someone: Thursday works for me"
               ]}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val prompt = ai.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("Rolling conversation:"), prompt)
        assertTrue(prompt.contains("are we still on for Thursday"), prompt)
        assertTrue(prompt.contains("SILENCE"), "the personality prompt is still sent")

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["transcriptLines"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `no transcript means no conversation section at all`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val response = ask(client, """{"prompt":"What day did we settle on?"}""")
        val prompt = ai.lastRequest!!.systemPrompt
        assertFalse(prompt.contains("Rolling conversation"), "an absent transcript is absent, not an empty heading")
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonObject["transcriptLines"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `blank lines are dropped rather than padding the prompt`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val response = ask(client, """{"prompt":"anything","transcript":["  ","Someone: real line",""]}""")
        assertEquals(1, Json.parseToJsonElement(response.bodyAsText()).jsonObject["transcriptLines"]!!.jsonPrimitive.content.toInt())
        assertTrue(ai.lastRequest!!.systemPrompt.contains("Someone: real line"))
    }

    @Test
    fun `an oversized transcript is capped, keeping the newest lines`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val lines = (1..200).joinToString(",") { "\"Someone: line $it\"" }
        val response = ask(client, """{"prompt":"anything","transcript":[$lines]}""")

        assertEquals(
            MAX_TRANSCRIPT_LINES,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["transcriptLines"]!!.jsonPrimitive.content.toInt(),
        )
        val prompt = ai.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("line 200"), "the newest lines are the relevant ones")
        assertFalse(prompt.contains("line 1:"), prompt.take(200))
        assertFalse(prompt.contains("Someone: line 100\n"), "line 100 is outside the last $MAX_TRANSCRIPT_LINES")
    }

    @Test
    fun `a single enormous line is truncated`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val huge = "x".repeat(5_000)
        ask(client, """{"prompt":"anything","transcript":["$huge"]}""")
        val prompt = ai.lastRequest!!.systemPrompt
        assertFalse(prompt.contains("x".repeat(MAX_TRANSCRIPT_LINE_CHARS + 1)), "a client must not be able to send an unbounded line")
        assertTrue(prompt.contains("x".repeat(MAX_TRANSCRIPT_LINE_CHARS)))
    }

    @Test
    fun `an explicit memory command still short-circuits before any transcript is used`() = testApplication {
        val ai = RecordingAi()
        setup(ai)
        val response = ask(
            client,
            """{"prompt":"Remember that Chris handles the west.","transcript":["Someone: unrelated chatter"]}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("none", Json.parseToJsonElement(response.bodyAsText()).jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(null, ai.lastRequest, "storing a memory must still cost no model call")
    }
}
