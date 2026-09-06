package com.operator.backend.tts

import com.operator.backend.providers.ProviderNotConfiguredException
import com.operator.backend.usage.UsageTracker
import com.operator.core.tts.TTSProvider
import com.operator.core.tts.TTSRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable

@Serializable
data class SynthesizeRequest(val text: String)

fun Route.ttsRoutes(
    provider: TTSProvider,
    usage: UsageTracker,
    voiceId: String?,
    modelId: String?,
) {
    post("/tts/synthesize") {
        val request = call.receive<SynthesizeRequest>()
        val text = request.text.trim()
        if (text.isEmpty() || text.length > MAX_SPOKEN_CHARACTERS) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "text must contain 1..$MAX_SPOKEN_CHARACTERS characters"))
            return@post
        }
        if (voiceId.isNullOrBlank() || modelId.isNullOrBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "ElevenLabs voice and model IDs must be configured"))
            return@post
        }

        val startedAt = System.nanoTime()
        val stream = try {
            provider.open(TTSRequest(text, voiceId, modelId))
        } catch (e: ProviderNotConfiguredException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            return@post
        } catch (e: TTSProviderException) {
            val status = if (e.retryable) HttpStatusCode.ServiceUnavailable else HttpStatusCode.BadGateway
            call.respond(status, mapOf("error" to e.message))
            return@post
        }

        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Operator-Audio-Format", "pcm_s16le")
        call.response.header("X-Operator-Sample-Rate", stream.sampleRateHz.toString())
        call.response.header("X-Operator-Channels", stream.channels.toString())
        call.response.header("X-Operator-Voice-Id", voiceId)
        call.response.header("X-Operator-Model-Id", modelId)

        var firstAudioMillis: Long? = null
        var failed = false
        try {
            call.respondBytesWriter(contentType = ContentType("audio", "pcm")) {
                stream.use {
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val read = it.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        if (firstAudioMillis == null) firstAudioMillis = (System.nanoTime() - startedAt) / 1_000_000
                        writeFully(buffer, 0, read)
                        flush()
                    }
                }
            }
        } catch (e: Exception) {
            failed = true
            throw e
        } finally {
            stream.close()
            usage.record(
                kind = "tts",
                provider = provider.name,
                model = modelId,
                characters = text.length,
                latencyMillis = firstAudioMillis ?: (System.nanoTime() - startedAt) / 1_000_000,
                failed = failed,
            )
        }
    }
}

private const val MAX_SPOKEN_CHARACTERS = 2_000
