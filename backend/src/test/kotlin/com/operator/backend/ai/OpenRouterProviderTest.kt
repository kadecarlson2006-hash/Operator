package com.operator.backend.ai

import com.operator.core.ai.AIRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the wire contract documented in [OpenRouterApi] without touching the network. */
class OpenRouterProviderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OpenRouterProvider {
        val engine = MockEngine { request -> handler(request) }
        return OpenRouterProvider(apiKey = "sk-or-test-key", engine = engine, appUrl = "https://example.invalid")
    }

    private val success = """
        {"id":"gen-1","model":"vendor/fast-1","provider":"UpstreamCo",
         "choices":[{"message":{"role":"assistant","content":"  Javier Bardem.  "},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":41,"completion_tokens":7,"total_tokens":48,"cost":0.000123}}
    """.trimIndent()

    @Test
    fun `sends the documented request and parses text, model, tokens and cost`() = runTest {
        var captured: String? = null
        var authorization: String? = null
        var title: String? = null
        var referer: String? = null
        var url: String? = null
        val p = provider { request ->
            captured = (request.body as io.ktor.http.content.TextContent).text
            authorization = request.headers[HttpHeaders.Authorization]
            title = request.headers["X-OpenRouter-Title"]
            referer = request.headers["HTTP-Referer"]
            url = request.url.toString()
            respond(ByteReadChannel(success), HttpStatusCode.OK, jsonHeaders)
        }
        p.fallbacks = listOf("vendor/backup-1")
        val response = p.generate(AIRequest("vendor/fast-1", "You are Operator.", "Who played the villain?", maxOutputTokens = 60))

        assertEquals("Javier Bardem.", response.text, "content is trimmed")
        assertEquals("vendor/fast-1", response.modelId)
        assertEquals(41, response.inputTokens)
        assertEquals(7, response.outputTokens)
        assertEquals(0.000123, p.lastCostUsd)
        assertEquals("UpstreamCo", p.lastProvider)
        assertTrue((response.latencyMillis ?: -1) >= 0)

        assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
        assertEquals("Bearer sk-or-test-key", authorization)
        assertEquals("Operator", title)
        assertEquals("https://example.invalid", referer)

        val body = Json.parseToJsonElement(captured!!).jsonObject
        assertEquals("vendor/fast-1", body["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("vendor/backup-1"), body["models"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(60, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Who played the villain?", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        p.close()
    }

    @Test
    fun `omits the system message when no prompt is configured`() = runTest {
        var captured: String? = null
        val p = provider { request ->
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond(ByteReadChannel(success), HttpStatusCode.OK, jsonHeaders)
        }
        p.generate(AIRequest("vendor/fast-1", "", "hello"))
        val messages = Json.parseToJsonElement(captured!!).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        p.close()
    }

    @Test
    fun `surfaces the provider error envelope and marks retryability`() = runTest {
        val unauthorized = provider {
            respond(ByteReadChannel("""{"error":{"message":"No auth credentials found","code":401}}"""), HttpStatusCode.Unauthorized, jsonHeaders)
        }
        val e = assertFailsWith<AIProviderException> { unauthorized.generate(AIRequest("m", "", "hi")) }
        assertTrue(e.message!!.contains("No auth credentials found"), e.message!!)
        assertEquals(401, e.status)
        assertTrue(!e.retryable, "401 is not worth retrying")
        unauthorized.close()

        val rateLimited = provider { respondError(HttpStatusCode.TooManyRequests) }
        assertTrue(assertFailsWith<AIProviderException> { rateLimited.generate(AIRequest("m", "", "hi")) }.retryable)
        rateLimited.close()

        val serverError = provider { respondError(HttpStatusCode.BadGateway) }
        assertTrue(assertFailsWith<AIProviderException> { serverError.generate(AIRequest("m", "", "hi")) }.retryable)
        serverError.close()
    }

    @Test
    fun `a 200 carrying an error envelope or empty content is a failure, not an empty answer`() = runTest {
        val envelope = provider { respond(ByteReadChannel("""{"error":{"message":"model is down"}}"""), HttpStatusCode.OK, jsonHeaders) }
        assertTrue(assertFailsWith<AIProviderException> { envelope.generate(AIRequest("m", "", "hi")) }.message!!.contains("model is down"))
        envelope.close()

        val blank = provider { respond(ByteReadChannel("""{"choices":[{"message":{"role":"assistant","content":"   "}}]}"""), HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<AIProviderException> { blank.generate(AIRequest("m", "", "hi")) }
        blank.close()
    }

    @Test
    fun `unknown response fields are ignored and missing usage is null`() = runTest {
        val p = provider {
            respond(
                ByteReadChannel("""{"model":"vendor/fast-1","brand_new_field":{"x":1},
                    "choices":[{"message":{"role":"assistant","content":"ok","reasoning":"hidden"},"finish_reason":"stop","native_finish_reason":"end"}]}"""),
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val r = p.generate(AIRequest("vendor/fast-1", "", "hi"))
        assertEquals("ok", r.text)
        assertNull(r.inputTokens)
        assertNull(p.lastCostUsd)
        p.close()
    }

    @Test
    fun `transport failure is reported as retryable`() = runTest {
        val p = provider { throw java.io.IOException("connection reset") }
        val e = assertFailsWith<AIProviderException> { p.generate(AIRequest("m", "", "hi")) }
        assertTrue(e.retryable)
        assertTrue(e.message!!.contains("connection reset"))
        p.close()
    }
}
