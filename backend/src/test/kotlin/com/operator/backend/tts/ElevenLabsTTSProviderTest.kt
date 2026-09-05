package com.operator.backend.tts

import com.operator.core.tts.TTSRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElevenLabsTTSProviderTest {
    @Test
    fun `streams configured PCM request without exposing the key in the body`() = runTest {
        var url: String? = null
        var apiKey: String? = null
        var body: String? = null
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6)
        val provider = ElevenLabsTTSProvider(
            apiKey = "eleven-secret",
            engine = MockEngine { request ->
                url = request.url.toString()
                apiKey = request.headers["xi-api-key"]
                body = (request.body as TextContent).text
                respond(ByteReadChannel(expected), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/pcm"))
            },
        )

        val stream = provider.open(TTSRequest("Good evening.", "voice/id", "model-fast"))
        val actual = ByteArray(expected.size)
        assertEquals(expected.size, stream.read(actual))
        assertEquals(-1, stream.read(ByteArray(2)))

        assertContentEquals(expected, actual)
        assertEquals(24_000, stream.sampleRateHz)
        assertEquals(1, stream.channels)
        assertEquals("eleven-secret", apiKey)
        assertTrue(url!!.contains("voice%2Fid/stream?output_format=pcm_24000"), url)
        val json = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("Good evening.", json["text"]!!.jsonPrimitive.content)
        assertEquals("model-fast", json["model_id"]!!.jsonPrimitive.content)
        assertTrue(!body!!.contains("eleven-secret"))
        provider.close()
    }

    @Test
    fun `classifies rate limits and rejects missing configuration`() = runTest {
        val provider = ElevenLabsTTSProvider(
            apiKey = "key",
            engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) },
        )
        val error = assertFailsWith<TTSProviderException> {
            provider.open(TTSRequest("hello", "voice", "model"))
        }
        assertEquals(429, error.status)
        assertTrue(error.retryable)
        assertFailsWith<IllegalStateException> { provider.open(TTSRequest("hello", null, "model")) }
        provider.close()
    }
}
