package com.operator.backend.ai

import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable

/** A provider call that failed in a way worth reporting verbatim to the caller. */
class AIProviderException(message: String, val status: Int? = null, val retryable: Boolean = false) : RuntimeException(message)

/**
 * OpenRouter implementation of [AIProvider] (ADR-001). Wire format documented and verified in
 * [OpenRouterApi]. The API key never leaves the backend and is never logged.
 */
class OpenRouterProvider(
    private val apiKey: String,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val appTitle: String = "Operator",
    private val appUrl: String? = null,
    private val requestTimeoutMillis: Long = 60_000,
) : AIProvider, Closeable {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient(CIO) {
        engine { requestTimeout = requestTimeoutMillis }
    }).config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    override suspend fun generate(request: AIRequest): AIResponse {
        val body = ChatCompletionRequest(
            model = request.modelId,
            messages = buildList {
                if (request.systemPrompt.isNotBlank()) add(ChatMessage(ChatMessage.SYSTEM, request.systemPrompt))
                add(ChatMessage(ChatMessage.USER, request.userContent))
            },
            models = fallbacks.takeIf { it.isNotEmpty() },
            maxTokens = request.maxOutputTokens,
            temperature = temperature,
            plugins = webSearch?.let { listOf(it) },
            provider = providerPreferences,
        )
        val startedAt = System.nanoTime()
        val response: HttpResponse = try {
            client.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("X-OpenRouter-Title", appTitle)
                appUrl?.let { header("HTTP-Referer", it) }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            throw AIProviderException("OpenRouter request failed: ${e.message ?: e::class.simpleName}", retryable = true)
        }
        val latency = (System.nanoTime() - startedAt) / 1_000_000

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AIProviderException(describeError(response.status, text), status = response.status.value, retryable = response.status.value >= 500 || response.status == HttpStatusCode.TooManyRequests)
        }
        val parsed = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), text)
        } catch (e: Exception) {
            log.warn("Unparseable OpenRouter response ({} bytes)", text.length)
            throw AIProviderException("OpenRouter returned an unreadable response: ${e.message}")
        }
        // A 200 can still carry an error envelope instead of choices.
        val content = parsed.choices.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            throw AIProviderException(describeError(response.status, text).ifBlank { "OpenRouter returned no content" })
        }
        return AIResponse(
            text = content.trim(),
            modelId = parsed.model ?: request.modelId,
            inputTokens = parsed.usage?.promptTokens,
            outputTokens = parsed.usage?.completionTokens,
            latencyMillis = latency,
        ).also { lastCostUsd = parsed.usage?.cost; lastProvider = parsed.provider }
    }

    /** Cost and upstream provider for the most recent call, when OpenRouter reported them. */
    @Volatile var lastCostUsd: Double? = null
        private set

    @Volatile var lastProvider: String? = null
        private set

    /** Fallback model IDs sent as OpenRouter's `models` array. Set per request by the caller. */
    @Volatile var fallbacks: List<String> = emptyList()

    /**
     * Web search, when the caller wants live information (ADR-052).
     *
     * Null by default and set per call rather than globally: search is billed and slow, so the
     * answer path may want it while the decision path - which runs on every lull - must not.
     */
    @Volatile var webSearch: WebSearchOptions? = null

    /** Routing preferences: privacy first, then speed (ADR-053). Null sends nothing. */
    @Volatile var providerPreferences: ProviderPreferences? = null

    private fun describeError(status: HttpStatusCode, body: String): String {
        val envelope = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()?.error
        val detail = envelope?.message ?: body.take(300).ifBlank { status.description }
        return "OpenRouter ${status.value}: $detail"
    }

    override fun close() = client.close()

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private val log = LoggerFactory.getLogger(OpenRouterProvider::class.java)
        private const val temperature = 0.7
    }
}
