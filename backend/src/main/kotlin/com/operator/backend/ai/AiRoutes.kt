package com.operator.backend.ai

import com.operator.backend.usage.UsageTracker
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class AskRequest(
    val prompt: String,
    /** FAST, DEEP or VISION. Omit to let the router decide. */
    val tier: String? = null,
    val maxOutputTokens: Int? = null,
    val sessionId: String? = null,
    /** Overrides the configured prompt version for this call (debugging). */
    val promptVersion: String? = null,
)

@Serializable
data class AskResponse(
    val text: String,
    val model: String,
    val tier: String,
    val routingReason: String,
    val promptVersion: String?,
    val latencyMillis: Long,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: Double? = null,
    val upstreamProvider: String? = null,
)

/**
 * Milestone 6 text AI.
 *
 *   POST /ai/respond   { prompt, tier?, maxOutputTokens?, sessionId?, promptVersion? }
 *   GET  /usage        rolling token/latency/cost counters
 *
 * The phone sends a prompt and gets text back; the API key, model IDs, and system prompt all
 * stay on the backend.
 */
fun Route.aiRoutes(
    provider: AIProvider,
    router: ModelRouter,
    prompts: PromptLibrary,
    usage: UsageTracker,
    defaultPromptVersion: String,
) {
    val log = LoggerFactory.getLogger("operator-ai")

    post("/ai/respond") {
        val request = call.receive<AskRequest>()
        if (request.prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "prompt must not be blank"))
            return@post
        }
        val requestedTier = request.tier?.let { raw ->
            ModelTier.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tier must be one of ${ModelTier.entries.joinToString { it.name }}"))
                    return@post
                }
        }

        val decision = try {
            router.route(request.prompt, requestedTier)
        } catch (e: ModelNotConfiguredException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            return@post
        }

        val promptVersion = request.promptVersion ?: defaultPromptVersion
        val systemPrompt = prompts.load(promptVersion)
        if (systemPrompt == null) log.warn("Prompt version {} unavailable; sending without a system prompt", promptVersion)

        (provider as? OpenRouterProvider)?.fallbacks = decision.fallbacks

        val startedAt = System.nanoTime()
        val result = try {
            provider.generate(
                AIRequest(
                    modelId = decision.modelId,
                    systemPrompt = systemPrompt.orEmpty(),
                    userContent = request.prompt,
                    maxOutputTokens = request.maxOutputTokens,
                ),
            )
        } catch (e: AIProviderException) {
            usage.record(
                kind = "model", provider = "openrouter", model = decision.modelId, tier = decision.tier.name,
                latencyMillis = (System.nanoTime() - startedAt) / 1_000_000, sessionId = request.sessionId, failed = true,
            )
            log.warn("AI request failed: {}", e.message)
            val status = when {
                e.status == 401 || e.status == 403 -> HttpStatusCode.BadGateway
                e.retryable -> HttpStatusCode.ServiceUnavailable
                else -> HttpStatusCode.BadGateway
            }
            call.respond(status, mapOf("error" to e.message, "model" to decision.modelId))
            return@post
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "AI provider not configured")))
            return@post
        }

        val cost = (provider as? OpenRouterProvider)?.lastCostUsd
        usage.record(
            kind = "model", provider = "openrouter", model = result.modelId, tier = decision.tier.name,
            inputTokens = result.inputTokens ?: 0, outputTokens = result.outputTokens ?: 0,
            latencyMillis = result.latencyMillis ?: 0, costUsd = cost, sessionId = request.sessionId,
        )
        call.respond(
            AskResponse(
                text = result.text,
                model = result.modelId,
                tier = decision.tier.name,
                routingReason = decision.reason,
                promptVersion = systemPrompt?.let { promptVersion },
                latencyMillis = result.latencyMillis ?: 0,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                costUsd = cost,
                upstreamProvider = (provider as? OpenRouterProvider)?.lastProvider,
            ),
        )
    }

    get("/usage") { call.respond(usage.report()) }
}
