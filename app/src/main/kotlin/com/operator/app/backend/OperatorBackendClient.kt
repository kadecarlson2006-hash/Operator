package com.operator.app.backend

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AskRequest(val prompt: String, val tier: String? = null, val sessionId: String? = null)

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
)

/** A failed backend call, already phrased for the user. */
class BackendException(message: String) : Exception(message)

/**
 * Talks to the Operator backend (Milestone 6). The phone holds no provider credentials: it sends
 * a prompt to `POST /ai/respond` and renders what comes back (ADR-005).
 */
interface OperatorBackend {
    val configured: Boolean
    suspend fun ask(prompt: String, tier: String? = null, sessionId: String? = null): AskResponse
    fun close() = Unit
}

/** Ktor/OkHttp implementation. */
class OperatorBackendClient(private val baseUrl: String?) : OperatorBackend {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
    }

    override val configured: Boolean get() = !baseUrl.isNullOrBlank()

    override suspend fun ask(prompt: String, tier: String?, sessionId: String?): AskResponse {
        val base = baseUrl?.trimEnd('/')
            ?: throw BackendException("No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.")
        val response = try {
            client.post("$base/ai/respond") {
                contentType(ContentType.Application.Json)
                setBody(AskRequest(prompt, tier, sessionId))
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

    override fun close() { runCatching { client.close() } }

    private companion object {
        const val TAG = "OperatorBackendClient"
    }
}
