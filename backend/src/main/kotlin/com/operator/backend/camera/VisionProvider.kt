package com.operator.backend.camera

import com.operator.backend.ai.ChatCompletionResponse
import com.operator.backend.ai.ErrorEnvelope
import com.operator.backend.ai.AIProviderException
import com.operator.core.camera.SceneDescription
import com.operator.core.camera.VisionConstraints
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Describes an image. Nothing else: no identification, no storage (ADR-049). */
interface VisionProvider {
    val configured: Boolean
    suspend fun describe(question: String, image: ByteArray, mimeType: String): SceneDescription
    fun close() = Unit
}

/** The honest default when no vision model is configured. */
object NoVisionProvider : VisionProvider {
    override val configured = false
    override suspend fun describe(question: String, image: ByteArray, mimeType: String): SceneDescription =
        throw AIProviderException("No vision model configured; set OPERATOR_VISION_MODEL_ID")
}

/**
 * Milestone 16 vision over OpenRouter.
 *
 * The rules in [VisionConstraints] go in the system prompt on every call rather than being set
 * once somewhere: they are the reason this feature is allowed to exist, and a prompt that can be
 * edited without them is a prompt that will eventually be edited without them.
 */
class OpenRouterVisionProvider(
    private val apiKey: String,
    private val modelId: String,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val requestTimeoutMillis: Long = 60_000,
) : VisionProvider, Closeable {

    private val log = LoggerFactory.getLogger("operator-vision")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override val configured = apiKey.isNotBlank() && modelId.isNotBlank()

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient(CIO) {
        engine { requestTimeout = requestTimeoutMillis }
    }).config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun describe(question: String, image: ByteArray, mimeType: String): SceneDescription {
        if (!configured) throw AIProviderException("No vision model configured; set OPERATOR_VISION_MODEL_ID")
        if (image.isEmpty()) throw AIProviderException("No image to look at")

        val body = VisionRequestBody(
            model = modelId,
            messages = listOf(
                VisionMessage(
                    role = "system",
                    content = listOf(VisionContentPart(VisionContentPart.TEXT, text = SYSTEM_PROMPT)),
                ),
                VisionMessage(
                    role = "user",
                    content = listOf(
                        VisionContentPart(VisionContentPart.TEXT, text = question),
                        VisionContentPart(
                            VisionContentPart.IMAGE_URL,
                            imageUrl = VisionImageUrl(dataUrl(image, mimeType)),
                        ),
                    ),
                ),
            ),
            maxTokens = MAX_OUTPUT_TOKENS,
            temperature = 0.2,
        )

        val startedAt = System.nanoTime()
        val response: HttpResponse = try {
            client.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("X-OpenRouter-Title", "Operator")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            throw AIProviderException("Vision request failed: ${e.message ?: e::class.simpleName}", retryable = true)
        }
        val latency = (System.nanoTime() - startedAt) / 1_000_000

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AIProviderException(
                describeError(response.status, text),
                status = response.status.value,
                retryable = response.status.value >= 500 || response.status == HttpStatusCode.TooManyRequests,
            )
        }
        val parsed = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), text)
        } catch (e: Exception) {
            // Length only. A vision response describes a real room, and a failed parse is not a
            // reason to write that description into a log file.
            log.warn("Unparseable vision response ({} bytes)", text.length)
            throw AIProviderException("Vision returned an unreadable response: ${e.message}")
        }
        val content = parsed.choices.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            throw AIProviderException(describeError(response.status, text).ifBlank { "Vision returned no content" })
        }
        return SceneDescription(
            text = content.trim(),
            model = parsed.model ?: modelId,
            latencyMillis = latency,
            imageBytes = image.size,
        )
    }

    private fun describeError(status: HttpStatusCode, body: String): String {
        val envelope = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()?.error
        return "Vision ${status.value}: ${envelope?.message ?: body.take(300).ifBlank { status.description }}"
    }

    override fun close() { runCatching { client.close() } }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val MAX_OUTPUT_TOKENS = 300

        val SYSTEM_PROMPT: String = buildString {
            appendLine("You are Operator's eyes. You describe what is in front of the user, briefly.")
            appendLine("Two or three sentences. No preamble.")
            appendLine()
            append(VisionConstraints.promptBlock())
        }
    }
}
