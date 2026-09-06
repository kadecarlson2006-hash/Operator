package com.operator.app.backend

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

@Serializable
data class AskRequest(
    val prompt: String,
    val tier: String? = null,
    val sessionId: String? = null,
    /** Operator mode decides which memory scopes the backend may read (ADR-026). */
    val mode: String? = null,
    val wit: String? = null,
    /** The rolling conversation window (Milestone 11), oldest line first. */
    val transcript: List<String> = emptyList(),
)

/** A memory the backend put in front of the model, with the reason it chose it. */
@Serializable
data class UsedMemory(val id: String, val type: String, val content: String, val confidence: Float = 0f, val why: String = "")

/** Present when the request was an explicit "remember that…" command. */
@Serializable
data class MemoryWritten(val id: String, val type: String, val content: String, val updatedExisting: Boolean = false, val embedded: Boolean = false)

@Serializable
data class AskResponse(
    val text: String,
    val model: String,
    val tier: String,
    val routingReason: String? = null,
    val promptVersion: String? = null,
    val latencyMillis: Long = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: Double? = null,
    val upstreamProvider: String? = null,
    val memoriesUsed: List<UsedMemory> = emptyList(),
    /** Transcript lines the backend actually used, after its own cap. */
    val transcriptLines: Int = 0,
    val memoryWritten: MemoryWritten? = null,
    val retrievalMillis: Long? = null,
    val semanticRetrieval: Boolean = false,
    val retrievalNote: String? = null,
)

/** The transcript of one utterance (Milestone 8). */
@Serializable
data class TranscribeResponse(
    val text: String,
    val provider: String = "",
    val languageCode: String? = null,
    val audioSeconds: Double? = null,
    val latencyMillis: Long = 0,
    /** True when the audio held no recognisable speech. Silence is a result, not an error. */
    val empty: Boolean = false,
)

/** A failed backend call, already phrased for the user. */
class BackendException(message: String) : Exception(message)

/**
 * Talks to the Operator backend (Milestone 6). The phone holds no provider credentials: it sends
 * a prompt to `POST /ai/respond` and renders what comes back (ADR-005).
 */
interface OperatorBackend {
    val configured: Boolean
    suspend fun ask(
        prompt: String,
        tier: String? = null,
        sessionId: String? = null,
        mode: String? = null,
        wit: String? = null,
        transcript: List<String> = emptyList(),
    ): AskResponse

    /**
     * Sends one utterance for transcription. [pcm] is little-endian PCM-16, which is what
     * AudioRecord produces; sending it raw avoids the third that base64 would add on the
     * latency path.
     */
    suspend fun transcribe(pcm: ByteArray, sampleRateHz: Int, channels: Int = 1, sessionId: String? = null): TranscribeResponse

    fun close() = Unit
}

interface SpeechAudioStream : Closeable {
    val sampleRateHz: Int
    val channels: Int
    suspend fun read(buffer: ByteArray): Int
}

interface OperatorSpeechBackend {
    val speechConfigured: Boolean
    suspend fun openSpeech(text: String): SpeechAudioStream
}

/** Ktor/OkHttp implementation. */
class OperatorBackendClient(private val baseUrl: String?) : OperatorBackend, OperatorSpeechBackend {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
    }

    override val configured: Boolean get() = !baseUrl.isNullOrBlank()
    override val speechConfigured: Boolean get() = configured

    override suspend fun ask(
        prompt: String,
        tier: String?,
        sessionId: String?,
        mode: String?,
        wit: String?,
        transcript: List<String>,
    ): AskResponse {
        val base = baseUrl?.trimEnd('/')
            ?: throw BackendException("No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.")
        val response = try {
            client.post("$base/ai/respond") {
                contentType(ContentType.Application.Json)
                setBody(AskRequest(prompt, tier, sessionId, mode, wit, transcript))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend unreachable", e)
            throw BackendException("Backend unreachable at $base (${e.message ?: e::class.simpleName})")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw BackendException("Backend ${response.status.value}: ${message ?: text.take(200)}")
        }
        return try {
            response.body<AskResponse>()
        } catch (e: Exception) {
            throw BackendException("Unreadable backend response: ${e.message}")
        }
    }

    override suspend fun transcribe(pcm: ByteArray, sampleRateHz: Int, channels: Int, sessionId: String?): TranscribeResponse {
        val base = baseUrl?.trimEnd('/')
            ?: throw BackendException("No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.")
        val query = buildString {
            append("?sampleRateHz=").append(sampleRateHz)
            append("&channels=").append(channels)
            sessionId?.let { append("&sessionId=").append(it) }
        }
        val response = try {
            client.post("$base/transcribe$query") {
                contentType(ContentType.Application.OctetStream)
                setBody(pcm)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend unreachable", e)
            throw BackendException("Backend unreachable at $base (${e.message ?: e::class.simpleName})")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw BackendException("Transcription ${response.status.value}: ${message ?: text.take(200)}")
        }
        return try {
            response.body<TranscribeResponse>()
        } catch (e: Exception) {
            throw BackendException("Unreadable transcription response: ${e.message}")
        }
    }

    override suspend fun openSpeech(text: String): SpeechAudioStream {
        val base = baseUrl?.trimEnd('/')
            ?: throw BackendException("No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.")
        val response = try {
            client.post("$base/tts/synthesize") {
                contentType(ContentType.Application.Json)
                setBody(SynthesizeRequest(text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speech backend unreachable", e)
            throw BackendException("Backend unreachable at $base (${e.message ?: e::class.simpleName})")
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            val message = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw BackendException("Backend ${response.status.value}: ${message ?: body.take(200)}")
        }
        val channel = response.bodyAsChannel()
        val sampleRate = response.headers["X-Operator-Sample-Rate"]?.toIntOrNull()
            ?: run {
                channel.cancel()
                throw BackendException("Speech response did not include a valid sample rate")
            }
        val channels = response.headers["X-Operator-Channels"]?.toIntOrNull()
            ?: run {
                channel.cancel()
                throw BackendException("Speech response did not include a valid channel count")
            }
        if (sampleRate !in 8_000..48_000 || channels != 1) {
            channel.cancel()
            throw BackendException("Unsupported speech format: ${sampleRate}Hz, $channels channel(s)")
        }
        return BackendSpeechAudioStream(channel, sampleRate, channels)
    }

    override fun close() { runCatching { client.close() } }

    private companion object {
        const val TAG = "OperatorBackendClient"
    }
}

@Serializable
private data class SynthesizeRequest(val text: String)

private class BackendSpeechAudioStream(
    private val channel: ByteReadChannel,
    override val sampleRateHz: Int,
    override val channels: Int,
) : SpeechAudioStream {
    override suspend fun read(buffer: ByteArray): Int = channel.readAvailable(buffer, 0, buffer.size)
    override fun close() = channel.cancel()
}
