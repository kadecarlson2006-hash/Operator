package com.operator.backend.transcription

import com.operator.backend.ai.AIProviderException
import com.operator.backend.providers.ProviderNotConfiguredException
import com.operator.backend.usage.UsageTracker
import com.operator.core.audio.PcmClip
import com.operator.core.transcription.TranscriptionProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class TranscribeResponse(
    val text: String,
    val provider: String,
    val languageCode: String? = null,
    val audioSeconds: Double? = null,
    val latencyMillis: Long = 0,
    /** True when the audio contained no recognisable speech. Silence is a result, not an error. */
    val empty: Boolean = false,
)

/**
 * Milestone 8 hearing.
 *
 *   POST /transcribe?sampleRateHz=16000&channels=1[&sessionId=]
 *   body: raw little-endian PCM-16, content type application/octet-stream
 *
 * Raw PCM rather than JSON because base64 would add a third to every upload on the latency
 * path, and the phone already holds the samples in that form.
 *
 * The phone is expected to have run voice-activity detection already, so what arrives here is
 * one utterance, not a live microphone feed. The backend keeps no copy: the bytes are encoded to
 * WAV in memory, sent, and dropped.
 *
 * This deliberately does NOT chain into the model. Turning a transcript into an answer is a
 * later milestone; keeping the subsystems separate is what makes each one testable on its own.
 */
fun Route.transcriptionRoutes(
    provider: TranscriptionProvider,
    usage: UsageTracker,
    maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    val log = LoggerFactory.getLogger("operator-transcription")

    post("/transcribe") {
        val params = call.request.queryParameters
        val sampleRateHz = params["sampleRateHz"]?.toIntOrNull() ?: 16_000
        val channels = params["channels"]?.toIntOrNull() ?: 1
        val sessionId = params["sessionId"]

        if (sampleRateHz !in 8_000..48_000) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sampleRateHz must be 8000..48000"))
            return@post
        }
        if (channels !in 1..2) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "channels must be 1 or 2"))
            return@post
        }

        val bytes = call.receiveChannel().readBounded(maxBodyBytes)
        if (bytes == null) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "audio exceeds $maxBodyBytes bytes; send one utterance at a time"),
            )
            return@post
        }
        if (bytes.size < 2) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty audio body"))
            return@post
        }

        val clip = PcmClip(samples = bytes.toLittleEndianShorts(), sampleRateHz = sampleRateHz, channels = channels)
        val startedAt = System.currentTimeMillis()
        try {
            val transcript = provider.transcribe(clip)
            val elapsed = System.currentTimeMillis() - startedAt
            usage.record(
                kind = "transcription",
                provider = transcript.provider ?: provider.name,
                model = transcript.provider ?: provider.name,
                latencyMillis = transcript.latencyMillis ?: elapsed,
                sessionId = sessionId,
            )
            call.respond(
                TranscribeResponse(
                    text = transcript.text,
                    provider = transcript.provider ?: provider.name,
                    languageCode = transcript.languageCode,
                    audioSeconds = transcript.audioSeconds,
                    latencyMillis = transcript.latencyMillis ?: elapsed,
                    empty = transcript.text.isBlank(),
                ),
            )
        } catch (e: ProviderNotConfiguredException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "transcription not configured")))
        } catch (e: AIProviderException) {
            log.warn("Transcription failed: {}", e.message)
            usage.record(
                kind = "transcription",
                provider = provider.name,
                model = provider.name,
                latencyMillis = System.currentTimeMillis() - startedAt,
                sessionId = sessionId,
                failed = true,
            )
            val status = if (e.retryable) HttpStatusCode.BadGateway else HttpStatusCode.UnprocessableEntity
            call.respond(status, mapOf("error" to e.message, "retryable" to e.retryable.toString()))
        }
    }
}

const val DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024 // ~5 minutes of 16 kHz mono PCM-16

/**
 * Reads the whole body, or returns null as soon as it would exceed [limit] — so an oversized
 * upload is refused while it streams rather than after it has all been held in memory.
 */
private suspend fun ByteReadChannel.readBounded(limit: Int): ByteArray? {
    val chunk = ByteArray(64 * 1024)
    val out = ByteArrayOutputStream()
    while (true) {
        val n = readAvailable(chunk, 0, chunk.size)
        if (n < 0) break
        if (n == 0) { if (isClosedForRead) break else continue }
        if (out.size() + n > limit) return null
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/** PCM-16 little-endian, the format AudioRecord produces and WavEncoder expects. */
private fun ByteArray.toLittleEndianShorts(): ShortArray {
    val out = ShortArray(size / 2)
    for (i in out.indices) {
        val lo = this[i * 2].toInt() and 0xFF
        val hi = this[i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort()
    }
    return out
}
