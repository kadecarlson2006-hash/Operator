package com.operator.backend.transcription

import com.operator.backend.ai.AIProviderException
import com.operator.core.audio.PcmClip
import com.operator.core.audio.WavEncoder
import com.operator.core.transcription.Transcript
import com.operator.core.transcription.TranscriptionProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * Speech-to-text over the OpenAI-compatible `/audio/transcriptions` endpoint (Milestone 8).
 *
 * The credential lives here, on the backend, and never on the phone (ADR-005). Audio arrives as
 * PCM that already passed the device's voice-activity gate, is encoded to WAV in memory, posted,
 * and dropped: this class keeps no copy of it and writes nothing to disk.
 *
 * See [TranscriptionApi] for where the wire format was verified.
 */
class OpenAiCompatibleTranscriptionProvider(
    private val apiKey: String,
    private val modelId: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** Optional hint; leave null to let the model detect the language. */
    private val languageCode: String? = null,
    override val name: String = "openai-compatible",
    engine: HttpClientEngine? = null,
) : TranscriptionProvider, Closeable {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient(CIO)).config {
        expectSuccess = false
    }

    /** Audio seconds billed by the most recent call, when the provider reports them. */
    @Volatile
    var lastAudioSeconds: Double? = null
        private set

    override suspend fun transcribe(clip: PcmClip): Transcript {
        require(clip.samples.isNotEmpty()) { "Refusing to transcribe an empty clip" }
        val wav = WavEncoder.encode(clip)
        val startedAt = System.currentTimeMillis()

        val response = try {
            client.post("$baseUrl/audio/transcriptions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("model", modelId)
                            append("response_format", "verbose_json")
                            languageCode?.let { append("language", it) }
                            append(
                                key = "file",
                                value = wav,
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "audio/wav")
                                    append(HttpHeaders.ContentDisposition, "filename=\"utterance.wav\"")
                                },
                            )
                        },
                    ),
                )
            }
        } catch (e: Exception) {
            throw AIProviderException(
                "Transcription request failed: ${e.message ?: e::class.simpleName}",
                retryable = true,
            )
        }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching {
                json.decodeFromString(TranscriptionErrorEnvelope.serializer(), body).error?.message
            }.getOrNull()
            throw AIProviderException(
                "Transcription ${response.status.value}: ${message ?: body.take(200)}",
                status = response.status.value,
                retryable = response.status.value >= 500 || response.status.value == 429,
            )
        }

        // Some gateways answer 200 with an error envelope; treat that as the failure it is.
        runCatching { json.decodeFromString(TranscriptionErrorEnvelope.serializer(), body).error }
            .getOrNull()
            ?.let { throw AIProviderException("Transcription failed: ${it.message ?: it.code ?: "unknown error"}") }

        val parsed = try {
            json.decodeFromString(TranscriptionResponse.serializer(), body)
        } catch (e: Exception) {
            throw AIProviderException("Unreadable transcription response: ${e.message}")
        }

        // An empty transcript is a legitimate outcome, not a failure: the gate can open on a door
        // slam. Silence is a first-class result, so this returns it and lets the caller decide.
        val seconds = parsed.duration ?: parsed.usage?.seconds
        lastAudioSeconds = seconds
        return Transcript(
            text = parsed.text.trim(),
            languageCode = parsed.language ?: languageCode,
            latencyMillis = System.currentTimeMillis() - startedAt,
            audioSeconds = seconds ?: clip.durationMillis / 1000.0,
            provider = name,
        )
    }

    override fun close() = client.close()

    companion object {
        /** Overridable so the same shape can address a self-hosted or third-party endpoint. */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
