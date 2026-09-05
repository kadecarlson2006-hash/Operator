package com.operator.backend.transcription

import com.operator.backend.BackendDependencies
import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderNotConfiguredException
import com.operator.backend.providers.ProviderRegistry
import com.operator.backend.usage.UsageTracker
import com.operator.core.audio.PcmClip
import com.operator.core.transcription.Transcript
import com.operator.core.transcription.TranscriptionProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptionRoutesTest {

    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    private class FakeStt(
        private val text: String = "turn the lights down",
        private val failWith: Exception? = null,
    ) : TranscriptionProvider {
        override val name = "fake-stt"
        var lastClip: PcmClip? = null
        var calls = 0
        override suspend fun transcribe(clip: PcmClip): Transcript {
            calls++
            lastClip = clip
            failWith?.let { throw it }
            return Transcript(text = text, languageCode = "en", latencyMillis = 42, audioSeconds = 1.25, provider = name)
        }
    }

    private lateinit var usage: UsageTracker

    private fun ApplicationTestBuilder.setup(stt: TranscriptionProvider) {
        val config = BackendConfig()
        usage = UsageTracker()
        val deps = BackendDependencies(
            config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(),
            usage = usage, prompts = PromptLibrary(), transcription = stt,
        )
        application { operatorModule(deps) }
    }

    /** One second of little-endian PCM-16 at 16 kHz. */
    private fun pcm(samples: Int = 16_000): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = ((i % 200) - 100) * 100
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun `posts pcm and gets a transcript back`() = testApplication {
        val stt = FakeStt()
        setup(stt)
        val response = client.post("/transcribe?sampleRateHz=16000&channels=1&sessionId=s1") {
            contentType(ContentType.Application.OctetStream)
            setBody(pcm())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("turn the lights down", body["text"]!!.jsonPrimitive.content)
        assertEquals("fake-stt", body["provider"]!!.jsonPrimitive.content)
        assertEquals("en", body["languageCode"]!!.jsonPrimitive.content)
        assertEquals(42L, body["latencyMillis"]!!.jsonPrimitive.content.toLong())
        assertFalse(body["empty"]!!.jsonPrimitive.content.toBoolean())

        val clip = stt.lastClip!!
        assertEquals(16_000, clip.sampleRateHz)
        assertEquals(1, clip.channels)
        assertEquals(16_000, clip.samples.size, "every sample should survive the byte decoding")
    }

    @Test
    fun `the raw bytes decode to the samples the phone sent`() = testApplication {
        val stt = FakeStt()
        setup(stt)
        val samples = shortArrayOf(0, 1, -1, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = s.toInt().toByte()
            bytes[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(bytes) }
        assertTrue(samples.contentEquals(stt.lastClip!!.samples))
    }

    @Test
    fun `an empty transcript is reported as empty, not as an error`() = testApplication {
        setup(FakeStt(text = "   "))
        val response = client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm(1600)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("   ", body["text"]!!.jsonPrimitive.content)
        assertTrue(body["empty"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `an unconfigured provider answers 503 rather than crashing`() = testApplication {
        setup(object : TranscriptionProvider {
            override val name = "none"
            override suspend fun transcribe(clip: PcmClip) = throw ProviderNotConfiguredException("Transcription")
        })
        val response = client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm(1600)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("not configured"))
    }

    @Test
    fun `a retryable provider failure is a bad gateway and a permanent one is not`() = testApplication {
        setup(FakeStt(failWith = AIProviderException("upstream down", status = 500, retryable = true)))
        val retryable = client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm(1600)) }
        assertEquals(HttpStatusCode.BadGateway, retryable.status)
        assertTrue(retryable.bodyAsText().contains("upstream down"))
    }

    @Test
    fun `a permanent provider failure is unprocessable`() = testApplication {
        setup(FakeStt(failWith = AIProviderException("bad audio", status = 400, retryable = false)))
        val response = client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm(1600)) }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `nonsense audio parameters are rejected before the provider is called`() = testApplication {
        val stt = FakeStt()
        setup(stt)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/transcribe?sampleRateHz=99") { setBody(pcm(1600)) }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/transcribe?channels=7") { setBody(pcm(1600)) }.status,
        )
        assertEquals(0, stt.calls, "no audio should be sent upstream with bad parameters")
    }

    @Test
    fun `an empty body is rejected`() = testApplication {
        val stt = FakeStt()
        setup(stt)
        val response = client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(ByteArray(0)) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, stt.calls)
    }

    @Test
    fun `a successful transcription is recorded in usage`() = testApplication {
        setup(FakeStt())
        client.post("/transcribe?sessionId=s9") { contentType(ContentType.Application.OctetStream); setBody(pcm()) }
        val report = usage.report()
        assertEquals(1, report.allTime.calls)
        assertEquals(0, report.allTime.failures)
        assertEquals("transcription", report.recent.first().kind)
        assertEquals("s9", report.recent.first().sessionId)
    }

    @Test
    fun `a failed transcription is recorded as a failure`() = testApplication {
        setup(FakeStt(failWith = AIProviderException("boom", retryable = true)))
        client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm(1600)) }
        val report = usage.report()
        assertEquals(1, report.allTime.failures)
        assertTrue(report.recent.first().failed)
    }

    @Test
    fun `audio beyond the cap is refused instead of being buffered`() = testApplication {
        val stt = FakeStt()
        application {
            install(ContentNegotiation) { json() }
            routing { transcriptionRoutes(stt, UsageTracker(), maxBodyBytes = 1024) }
        }
        val response = client.post("/transcribe") {
            contentType(ContentType.Application.OctetStream)
            setBody(pcm(4_000)) // 8000 bytes, well past the cap
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0, stt.calls, "oversized audio must never reach the provider")
    }

    @Test
    fun `usage is visible through the usage endpoint alongside model calls`() = testApplication {
        setup(FakeStt())
        client.post("/transcribe") { contentType(ContentType.Application.OctetStream); setBody(pcm()) }
        val report = Json.parseToJsonElement(client.get("/usage").bodyAsText()).jsonObject
        val recent = report["recent"]!!.jsonArray
        assertEquals(1, recent.size)
        assertEquals("transcription", recent[0].jsonObject["kind"]!!.jsonPrimitive.content)
    }
}
