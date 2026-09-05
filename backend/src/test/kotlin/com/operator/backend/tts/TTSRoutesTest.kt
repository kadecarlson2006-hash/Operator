package com.operator.backend.tts

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import com.operator.backend.usage.UsageTracker
import com.operator.core.config.OperatorConfig
import com.operator.core.tts.TTSAudioStream
import com.operator.core.tts.TTSProvider
import com.operator.core.tts.TTSRequest
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TTSRoutesTest {
    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    private class FakeTts(private val audio: ByteArray) : TTSProvider {
        override val name = "fake-elevenlabs"
        var request: TTSRequest? = null
        override suspend fun open(request: TTSRequest): TTSAudioStream {
            this.request = request
            return object : TTSAudioStream {
                override val sampleRateHz = 24_000
                override val channels = 1
                private var sent = false
                override suspend fun read(buffer: ByteArray): Int {
                    if (sent) return -1
                    audio.copyInto(buffer)
                    sent = true
                    return audio.size
                }
                override fun close() = Unit
            }
        }
        override fun cancel() = Unit
    }

    @Test
    fun `streams PCM with format headers and records characters`() = testApplication {
        val audio = byteArrayOf(1, 2, 3, 4)
        val tts = FakeTts(audio)
        val usage = UsageTracker()
        val operator = OperatorConfig(
            ttsProvider = "elevenlabs",
            elevenLabsVoiceId = "golden-voice",
            elevenLabsModelId = "fast-model",
            fastModelId = "unused-fast",
        )
        val config = BackendConfig(operator = operator)
        val deps = BackendDependencies(
            config,
            OkDb,
            ProviderRegistry(config),
            InMemoryMemoryStore(),
            usage = usage,
            tts = tts,
        )
        application { operatorModule(deps) }

        val response = client.post("/tts/synthesize") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"Good evening."}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("24000", response.headers["X-Operator-Sample-Rate"])
        assertEquals("1", response.headers["X-Operator-Channels"])
        assertContentEquals(audio, response.body<ByteArray>())
        assertEquals("golden-voice", tts.request!!.voiceId)
        assertEquals("fast-model", tts.request!!.modelId)
        assertEquals("Good evening.".length.toLong(), usage.report().allTime.characters)
    }

    @Test
    fun `rejects blank text and missing voice configuration`() = testApplication {
        val tts = FakeTts(byteArrayOf(1, 2))
        val config = BackendConfig(operator = OperatorConfig(fastModelId = "unused"))
        application {
            operatorModule(BackendDependencies(config, OkDb, ProviderRegistry(config), InMemoryMemoryStore(), tts = tts))
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/tts/synthesize") { contentType(ContentType.Application.Json); setBody("""{"text":" "}""") }.status,
        )
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.post("/tts/synthesize") { contentType(ContentType.Application.Json); setBody("""{"text":"hello"}""") }.status,
        )
    }
}
