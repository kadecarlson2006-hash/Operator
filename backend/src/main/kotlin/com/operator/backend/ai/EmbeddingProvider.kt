package com.operator.backend.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * Turns text into vectors for semantic memory retrieval (Milestone 7).
 *
 * Retrieval must keep working when this is absent: without embeddings the retrieval engine falls
 * back to lexical and structured matching (ADR-025), so an unset embedding model degrades
 * quality rather than breaking the feature.
 */
interface EmbeddingProvider {
    val available: Boolean
    val modelId: String?
    /** One vector per input, in the same order. Throws [AIProviderException] on failure. */
    suspend fun embed(texts: List<String>): List<List<Float>>
}

/** Used when no embedding model is configured. */
object NoEmbeddingProvider : EmbeddingProvider {
    override val available = false
    override val modelId: String? = null
    override suspend fun embed(texts: List<String>): List<List<Float>> = emptyList()
}

/**
 * OpenRouter embeddings. Verified on 2026-09-05 against `@openrouter/ai-sdk-provider` 3.0.0:
 *   POST /embeddings  {model, input: string[], user?, provider?}
 *   200  {id?, object:"list", data:[{object:"embedding", embedding:number[], index?}],
 *         model, provider?, usage?:{prompt_tokens, total_tokens, cost?}}
 * See docs/RISKS_AND_UNKNOWNS.md item 27 for why the hosted reference could not be checked.
 */
class OpenRouterEmbeddingProvider(
    private val apiKey: String,
    override val modelId: String,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = OpenRouterProvider.DEFAULT_BASE_URL,
) : EmbeddingProvider, Closeable {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient(CIO)).config {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    override val available = true

    /** Tokens billed by the most recent call, when reported. */
    @Volatile var lastPromptTokens: Int? = null
        private set

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (texts.isEmpty()) return emptyList()
        val response = try {
            client.post("$baseUrl/embeddings") {
                header("Authorization", "Bearer $apiKey")
                header("X-OpenRouter-Title", "Operator")
                contentType(ContentType.Application.Json)
                setBody(EmbeddingRequest(model = modelId, input = texts))
            }
        } catch (e: Exception) {
            throw AIProviderException("Embedding request failed: ${e.message ?: e::class.simpleName}", retryable = true)
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body).error?.message }.getOrNull()
            throw AIProviderException(
                "Embeddings ${response.status.value}: ${message ?: body.take(200)}",
                status = response.status.value,
                retryable = response.status.value >= 500 || response.status.value == 429,
            )
        }
        val parsed = try {
            json.decodeFromString(EmbeddingResponse.serializer(), body)
        } catch (e: Exception) {
            throw AIProviderException("Unreadable embeddings response: ${e.message}")
        }
        lastPromptTokens = parsed.usage?.promptTokens
        val vectors = parsed.data.sortedBy { it.index ?: 0 }.map { it.embedding }
        if (vectors.size != texts.size) {
            throw AIProviderException("Embeddings returned ${vectors.size} vectors for ${texts.size} inputs")
        }
        return vectors
    }

    override fun close() = client.close()
}

@Serializable
internal data class EmbeddingRequest(val model: String, val input: List<String>)

@Serializable
internal data class EmbeddingResponse(
    val model: String? = null,
    val provider: String? = null,
    val data: List<EmbeddingDatum> = emptyList(),
    val usage: EmbeddingUsage? = null,
)

@Serializable
internal data class EmbeddingDatum(val embedding: List<Float> = emptyList(), val index: Int? = null)

@Serializable
internal data class EmbeddingUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    val cost: Double? = null,
)
