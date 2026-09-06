package com.operator.backend.feedback

import com.operator.backend.BackendDependencies
import com.operator.backend.ai.PromptLibrary
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
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeedbackRoutesTest {

    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    private class FakeAi(private val reply: String) : AIProvider {
        var lastSystemPrompt: String? = null
        override suspend fun generate(request: AIRequest): AIResponse {
            lastSystemPrompt = request.systemPrompt
            return AIResponse(reply, request.modelId, latencyMillis = 5)
        }
    }

    private fun promptDir(): File = Files.createTempDirectory("prompts").toFile().also {
        it.deleteOnExit()
        File(it, "operator-system-v1.txt").writeText("You are Operator. SILENCE is the default.")
        File(it, "operator-decision-v1.txt").writeText("You are the decision stage.")
    }

    private lateinit var ai: FakeAi

    private fun ApplicationTestBuilder.setup(reply: String = SPEAK_JSON) {
        val config = BackendConfig(operator = OperatorConfig(fastModelId = "v/fast", decisionModelId = "v/decide"))
        ai = FakeAi(reply)
        val deps = BackendDependencies(
            config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(),
            prompts = PromptLibrary(promptDir()), ai = ai, embeddings = FakeEmbeddings(),
        )
        application { operatorModule(deps) }
    }

    private suspend fun send(client: HttpClient, body: String) =
        client.post("/feedback") { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `a verdict is recorded and reports the resulting penalty`() = testApplication {
        setup()
        val response = send(client, """{"comment":"The deadline moved to Thursday.","verdict":"UNWANTED","confidence":0.7,"relevance":0.6}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UNWANTED", body["verdict"]!!.jsonPrimitive.content)
        assertTrue(
            body["penalty"]!!.jsonPrimitive.content.toFloat() > 0f,
            "a complaint should immediately raise the floors",
        )
    }

    @Test
    fun `approval records but raises nothing`() = testApplication {
        setup()
        val response = send(client, """{"comment":"Net thirty with Halvorsen.","verdict":"HELPFUL"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0f, body["penalty"]!!.jsonPrimitive.content.toFloat())
        assertTrue(
            body["note"]!!.jsonPrimitive.content.contains("does not make Operator speak more"),
            "the asymmetry should be stated back to the user, because it surprises people",
        )
    }

    @Test
    fun `an unknown verdict is refused rather than guessed at`() = testApplication {
        setup()
        val response = send(client, """{"comment":"something","verdict":"MEH"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("UNWANTED"), "the error should list what is accepted")
    }

    @Test
    fun `a blank comment is refused`() = testApplication {
        setup()
        assertEquals(HttpStatusCode.BadRequest, send(client, """{"comment":"   ","verdict":"HELPFUL"}""").status)
    }

    @Test
    fun `a malformed sessionId is refused rather than dropped`() = testApplication {
        setup()
        val response = send(client, """{"comment":"something","verdict":"HELPFUL","sessionId":"not-a-uuid"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `the summary reports the scores the complained-about comments carried`() = testApplication {
        setup()
        send(client, """{"comment":"one","verdict":"UNWANTED","confidence":0.7,"relevance":0.55}""")
        send(client, """{"comment":"two","verdict":"UNWANTED","confidence":0.9,"relevance":0.65}""")
        send(client, """{"comment":"three","verdict":"HELPFUL","confidence":0.2,"relevance":0.2}""")

        val body = Json.parseToJsonElement(client.get("/feedback/summary").bodyAsText()).jsonObject
        assertEquals(3, body["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["complaints"]!!.jsonPrimitive.content.toInt())
        // The whole point of the milestone: the mean excludes the approved comment, so it says
        // what the floor should have been rather than what the average comment scored (risk 44).
        assertEquals(0.8f, body["meanConfidenceOfComplaints"]!!.jsonPrimitive.content.toFloat(), 0.001f)
        assertEquals(0.6f, body["meanRelevanceOfComplaints"]!!.jsonPrimitive.content.toFloat(), 0.001f)
    }

    @Test
    fun `recent feedback comes back oldest first`() = testApplication {
        setup()
        send(client, """{"comment":"first","verdict":"UNWANTED"}""")
        send(client, """{"comment":"second","verdict":"HELPFUL"}""")
        val rows = Json.parseToJsonElement(client.get("/feedback/recent").bodyAsText()).jsonArray
        assertEquals(2, rows.size)
        assertEquals("first", rows[0].jsonObject["comment"]!!.jsonPrimitive.content)
        assertEquals("second", rows[1].jsonObject["comment"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a rejected remark reaches the decision prompt`() = testApplication {
        setup()
        send(client, """{"comment":"Everyone loves a weather update.","verdict":"UNWANTED"}""")
        client.post("/decide") {
            contentType(ContentType.Application.Json)
            setBody("""{"trigger":"COMMENT_NOW","transcript":["Someone: it is raining"]}""")
        }
        val prompt = ai.lastSystemPrompt.orEmpty()
        assertTrue(
            prompt.contains("Everyone loves a weather update."),
            "the model should be shown what was rejected, or it cannot learn the shape of it",
        )
        assertTrue(prompt.contains("marked these earlier remarks"), "expected the rejection heading")
    }

    @Test
    fun `an approved remark is not fed back as an example`() = testApplication {
        setup()
        send(client, """{"comment":"Net thirty with Halvorsen.","verdict":"HELPFUL"}""")
        client.post("/decide") {
            contentType(ContentType.Application.Json)
            setBody("""{"trigger":"COMMENT_NOW","transcript":["Someone: what were the terms"]}""")
        }
        assertTrue(
            !ai.lastSystemPrompt.orEmpty().contains("Net thirty with Halvorsen."),
            "a list of approved remarks would read to the model as a licence to say more of them",
        )
    }

    private companion object {
        const val SPEAK_JSON =
            """{"shouldSpeak":true,"category":"USEFUL_CONTEXT","confidence":0.9,"urgency":0.4,"relevance":0.8,"response":"The deadline moved to Thursday."}"""
    }
}
