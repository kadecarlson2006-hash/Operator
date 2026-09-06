package com.operator.backend.camera

import com.operator.backend.ai.AIProviderException
import com.operator.backend.transcription.readBounded
import com.operator.backend.usage.UsageTracker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class LookResponse(
    val text: String,
    val model: String? = null,
    val latencyMillis: Long = 0,
    val imageBytes: Int = 0,
)

/**
 * Milestone 16 camera context.
 *
 *   POST /look?question=...   body: the image bytes, content type image/jpeg or image/png
 *
 * Raw bytes rather than JSON for the same reason `/transcribe` takes raw PCM: base64 would add a
 * third to every upload on a latency path, and the caller already holds the bytes.
 *
 * The image is sent and dropped. It is never written to disk, never stored, and never attached to
 * a memory - only the description survives, and the rules the model is given forbid that
 * description from identifying anyone (ADR-049).
 *
 * Whether Operator is *allowed* to look is decided on the phone, where the camera, the permission
 * and the mode are (`CameraContextPolicy`). The backend does not re-derive that; it describes what
 * it is sent.
 */
fun Route.visionRoutes(
    provider: VisionProvider,
    usage: UsageTracker,
    maxBodyBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
) {
    val log = LoggerFactory.getLogger("operator-vision")

    post("/look") {
        val question = call.request.queryParameters["question"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_QUESTION
        val mimeType = call.request.headers["Content-Type"]
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it in ALLOWED_IMAGE_TYPES }
            ?: run {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    mapOf("error" to "Content-Type must be one of ${ALLOWED_IMAGE_TYPES.joinToString()}"),
                )
                return@post
            }

        val bytes = call.receiveChannel().readBounded(maxBodyBytes)
        if (bytes == null) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to "image exceeds $maxBodyBytes bytes"),
            )
            return@post
        }
        if (bytes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no image"))
            return@post
        }

        val description = try {
            provider.describe(question, bytes, mimeType)
        } catch (e: AIProviderException) {
            // The question is logged, never the description: what a camera saw is exactly the
            // thing that should not accumulate in a log file.
            log.warn("Look failed: {}", e.message)
            val status = when {
                e.status == null -> HttpStatusCode.ServiceUnavailable
                e.status in 400..499 -> HttpStatusCode.UnprocessableEntity
                else -> HttpStatusCode.BadGateway
            }
            call.respond(status, mapOf("error" to (e.message ?: "vision failed")))
            return@post
        }

        usage.record(
            kind = "vision",
            provider = "openrouter",
            model = description.model ?: "unknown",
            tier = "VISION",
            // Counted as characters rather than tokens: what an image costs in tokens depends on
            // the model's own tiling, which is not reported, and inventing a number would be worse
            // than recording the one thing actually known.
            characters = bytes.size,
            latencyMillis = description.latencyMillis,
        )
        call.respond(
            LookResponse(
                text = description.text,
                model = description.model,
                latencyMillis = description.latencyMillis,
                imageBytes = description.imageBytes,
            ),
        )
    }
}

private const val DEFAULT_QUESTION = "What is in front of me?"
private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

/** A glasses photo, not a raw sensor dump. Large enough for a JPEG, small enough to bound cost. */
private const val DEFAULT_MAX_IMAGE_BYTES = 6 * 1024 * 1024
