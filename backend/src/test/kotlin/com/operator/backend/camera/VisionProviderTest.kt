package com.operator.backend.camera

import com.operator.backend.ai.AIProviderException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VisionProviderTest {

    private val image = byteArrayOf(1, 2, 3, 4, 5)

    private fun provider(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = OK_BODY,
        capture: MutableList<String> = mutableListOf(),
    ) = OpenRouterVisionProvider(
        apiKey = "k",
        modelId = "v/vision",
        engine = MockEngine { request ->
            capture += (request.body as io.ktor.http.content.TextContent).text
            respond(body, status, headersOf("Content-Type", "application/json"))
        },
    )

    @Test
    fun `a described scene comes back as text`(): Unit = runBlocking {
        val p = provider()
        val result = p.describe("what is this", image, "image/jpeg")
        assertEquals("A desk with a laptop and a mug.", result.text)
        assertEquals("v/vision", result.model)
        assertEquals(5, result.imageBytes)
    }

    @Test
    fun `the image travels as a data url in a content part`(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        provider(capture = sent).describe("what is this", image, "image/jpeg")

        val body = Json.parseToJsonElement(sent.single()).jsonObject
        val userContent = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val imagePart = userContent.first { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }
        val url = imagePart.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"), "unexpected image url: ${url.take(40)}")
    }

    @Test
    fun `the privacy rules are in every request, not set once somewhere`(): Unit = runBlocking {
        // They are the reason the feature is allowed to exist, so they travel with the call.
        val sent = mutableListOf<String>()
        provider(capture = sent).describe("what is this", image, "image/jpeg")

        val body = Json.parseToJsonElement(sent.single()).jsonObject
        val system = body["messages"]!!.jsonArray.first().jsonObject["content"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("Do not identify anyone"), "the system prompt must forbid identification")
        assertTrue(system.contains("appearance"), "the system prompt must forbid describing appearance")
    }

    @Test
    fun `an unconfigured provider refuses rather than pretending`() {
        val p = OpenRouterVisionProvider(apiKey = "", modelId = "")
        assertFalse(p.configured)
        assertFailsWith<AIProviderException> { runBlocking { p.describe("q", image, "image/jpeg") } }
    }

    @Test
    fun `an empty image is refused before any request`() {
        val p = provider()
        assertFailsWith<AIProviderException> { runBlocking { p.describe("q", ByteArray(0), "image/jpeg") } }
    }

    @Test
    fun `an error status becomes a provider exception carrying the status`() {
        val p = provider(HttpStatusCode.TooManyRequests, """{"error":{"message":"slow down"}}""")
        val e = assertFailsWith<AIProviderException> { runBlocking { p.describe("q", image, "image/jpeg") } }
        assertEquals(429, e.status)
        assertTrue(e.retryable, "429 should be retryable")
        assertTrue(e.message!!.contains("slow down"))
    }

    @Test
    fun `an error envelope on a 200 is still an error`() {
        // The same trap the text provider has: OpenRouter can answer 200 with no choices.
        val p = provider(HttpStatusCode.OK, """{"error":{"message":"no credit"}}""")
        val e = assertFailsWith<AIProviderException> { runBlocking { p.describe("q", image, "image/jpeg") } }
        assertTrue(e.message!!.contains("no credit"), "expected the envelope message, got: ${e.message}")
    }

    @Test
    fun `an unreadable body is an error, not a crash`() {
        val p = provider(HttpStatusCode.OK, "not json at all")
        assertFailsWith<AIProviderException> { runBlocking { p.describe("q", image, "image/jpeg") } }
    }

    @Test
    fun `NoVisionProvider is honest about being unconfigured`() {
        assertFalse(NoVisionProvider.configured)
        assertFailsWith<AIProviderException> { runBlocking { NoVisionProvider.describe("q", image, "image/jpeg") } }
    }

    private companion object {
        const val OK_BODY = """
            {"model":"v/vision","choices":[{"message":{"role":"assistant","content":"A desk with a laptop and a mug."}}]}
        """
    }
}
