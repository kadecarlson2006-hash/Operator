package com.operator.backend.transcription

import com.operator.backend.ai.AIProviderException
import com.operator.core.audio.PcmClip
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiCompatibleTranscriptionProviderTest {

    private fun clip(samples: Int = 1600) = PcmClip(ShortArray(samples) { (it % 100).toShort() }, 16_000, 1)

    private fun provider(
        baseUrl: String = "https://stt.example/v1",
        languageCode: String? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = OpenAiCompatibleTranscriptionProvider(
        apiKey = "secret-key",
        modelId = "vendor/whisper",
        baseUrl = baseUrl,
        languageCode = languageCode,
        engine = MockEngine { request -> handler(request) },
    )

    private fun json(body: String) = HttpStatusCode.OK to body

    @Test
    fun `posts multipart to the configured base url with bearer auth`(): Unit = runBlocking {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenContentType: String? = null
        val p = provider { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenContentType = request.body.contentType?.contentType + "/" + request.body.contentType?.contentSubtype
            respond(
                """{"text":"hello there","language":"en","duration":1.5}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val t = p.transcribe(clip())
        assertEquals("https://stt.example/v1/audio/transcriptions", seenUrl)
        assertEquals("Bearer secret-key", seenAuth)
        assertEquals("multipart/form-data", seenContentType)
        assertEquals("hello there", t.text)
        assertEquals("en", t.languageCode)
        assertEquals(1.5, t.audioSeconds)
        assertEquals("openai-compatible", t.provider)
        assertNotNull(t.latencyMillis)
    }

    @Test
    fun `the multipart body carries the model, the response format and a wav file`(): Unit = runBlocking {
        var body = ""
        val p = provider { request ->
            body = request.body.toDebugString()
            respond("""{"text":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        p.transcribe(clip())
        assertTrue(body.contains("vendor/whisper"), "the model must be sent: $body")
        assertTrue(body.contains("verbose_json"), "response_format must be sent: $body")
        assertTrue(body.contains("utterance.wav"), "the audio must be sent as a file: $body")
        assertTrue(body.contains("RIFF"), "the file must be a WAV container: $body")
    }

    @Test
    fun `a language hint is sent when configured and omitted when not`(): Unit = runBlocking {
        var withHint = ""
        provider(languageCode = "en") { request ->
            withHint = request.body.toDebugString()
            respond("""{"text":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }.transcribe(clip())
        assertTrue(withHint.contains("language"))

        var without = ""
        provider(languageCode = null) { request ->
            without = request.body.toDebugString()
            respond("""{"text":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }.transcribe(clip())
        assertTrue(!without.contains("name=language"), "no hint should be sent when none is configured")
    }

    @Test
    fun `falls back to the clip's own duration when the provider reports none`(): Unit = runBlocking {
        val p = provider { respond("""{"text":"ok"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val t = p.transcribe(clip(samples = 16_000)) // exactly one second
        assertEquals(1.0, t.audioSeconds)
    }

    @Test
    fun `an empty transcript is a result, not a failure`(): Unit = runBlocking {
        val p = provider { respond("""{"text":"   "}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("", p.transcribe(clip()).text)
    }

    @Test
    fun `an http error surfaces the provider's message`(): Unit = runBlocking {
        val p = provider {
            respond(
                """{"error":{"message":"model not found","type":"invalid_request_error","code":"model_not_found"}}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val e = assertFailsWith<AIProviderException> { p.transcribe(clip()) }
        assertTrue(e.message!!.contains("model not found"), e.message!!)
        assertEquals(404, e.status)
        assertTrue(!e.retryable, "a 404 is not worth retrying")
    }

    @Test
    fun `server errors and rate limits are retryable`(): Unit = runBlocking {
        val p = provider { respond("""{"error":{"message":"slow down"}}""", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertTrue(assertFailsWith<AIProviderException> { p.transcribe(clip()) }.retryable)

        val q = provider { respond("""{"error":{"message":"boom"}}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertTrue(assertFailsWith<AIProviderException> { q.transcribe(clip()) }.retryable)
    }

    @Test
    fun `an error envelope arriving on a 200 is still a failure`(): Unit = runBlocking {
        val p = provider {
            respond(
                """{"error":{"message":"audio too short","code":"bad_audio"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertTrue(assertFailsWith<AIProviderException> { p.transcribe(clip()) }.message!!.contains("audio too short"))
    }

    @Test
    fun `an unreadable body is reported rather than parsed into nonsense`(): Unit = runBlocking {
        val p = provider { respond("<html>gateway</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        assertTrue(assertFailsWith<AIProviderException> { p.transcribe(clip()) }.message!!.contains("Unreadable"))
    }

    @Test
    fun `an empty clip is refused before any request is made`(): Unit = runBlocking {
        var called = false
        val p = provider { called = true; respond("""{"text":""}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertFailsWith<IllegalArgumentException> { p.transcribe(PcmClip(ShortArray(0), 16_000, 1)) }
        assertTrue(!called, "no network call should be made for an empty clip")
    }
}

/** Renders an outgoing multipart body to text so tests can assert on the parts it contains. */
private suspend fun OutgoingContent.toDebugString(): String = String(toByteArray(), Charsets.ISO_8859_1)
