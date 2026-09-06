package com.operator.backend.tts

import com.operator.core.tts.TTSAudioStream
import com.operator.core.tts.TTSProvider
import com.operator.core.tts.TTSRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

class TTSProviderException(message: String, val status: Int? = null, val retryable: Boolean = false) : RuntimeException(message)

@Serializable
private data class ElevenLabsRequest(
    val text: String,
    @SerialName("model_id") val modelId: String,
)

/** Streams ElevenLabs raw 24 kHz PCM without buffering a complete utterance in memory. */
class ElevenLabsTTSProvider(
    private val apiKey: String,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val requestTimeoutMillis: Long = 60_000,
) : TTSProvider, Closeable {
    override val name = "elevenlabs"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = (engine?.let { HttpClient(it) } ?: HttpClient(CIO) {
        engine { requestTimeout = requestTimeoutMillis }
    }).config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }
    private val active = ConcurrentHashMap.newKeySet<ElevenLabsAudioStream>()

    override suspend fun open(request: TTSRequest): TTSAudioStream {
        require(request.text.isNotBlank()) { "speech text must not be blank" }
        val voiceId = request.voiceId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ElevenLabs voice ID is not configured")
        val modelId = request.modelId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ElevenLabs model ID is not configured")
        val response = try {
            client.post("$baseUrl/text-to-speech/${voiceId.encodeURLPathPart()}/stream?output_format=$OUTPUT_FORMAT") {
                header("xi-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(ElevenLabsRequest(request.text, modelId))
            }
        } catch (e: Exception) {
            throw TTSProviderException("ElevenLabs request failed: ${e.message ?: e::class.simpleName}", retryable = true)
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw TTSProviderException(
                "ElevenLabs ${response.status.value}: ${body.take(300).ifBlank { response.status.description }}",
                status = response.status.value,
                retryable = response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500,
            )
        }
        return ElevenLabsAudioStream(response.bodyAsChannel()) { active.remove(it) }.also(active::add)
    }

    override fun cancel() = active.toList().forEach(ElevenLabsAudioStream::close)

    override fun close() {
        cancel()
        client.close()
    }

    private class ElevenLabsAudioStream(
        private val channel: ByteReadChannel,
        private val onClose: (ElevenLabsAudioStream) -> Unit,
    ) : TTSAudioStream {
        override val sampleRateHz = SAMPLE_RATE_HZ
        override val channels = 1
        private var closed = false

        override suspend fun read(buffer: ByteArray): Int {
            if (closed) return -1
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) close()
            return read
        }

        override fun close() {
            if (closed) return
            closed = true
            channel.cancel()
            onClose(this)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.elevenlabs.io/v1"
        const val SAMPLE_RATE_HZ = 24_000
        const val OUTPUT_FORMAT = "pcm_24000"
    }
}
