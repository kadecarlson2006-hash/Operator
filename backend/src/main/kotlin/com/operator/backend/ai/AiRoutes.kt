package com.operator.backend.ai

import com.operator.backend.memory.MemoryRetrievalEngine
import com.operator.backend.memory.MemoryWriteEngine
import com.operator.backend.memory.RetrievalTrigger
import com.operator.backend.usage.UsageTracker
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
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
    /** Operator mode, which decides which memory scopes may be read (ADR-026). */
    val mode: String? = null,
    val wit: String? = null,
    /** Set false to answer without touching memory. */
    val useMemory: Boolean = true,
    /**
     * The phone's rolling conversation window (Milestone 11), oldest line first, already
     * speaker-labelled. The backend keeps no copy: it is read into one prompt and dropped.
     */
    val transcript: List<String> = emptyList(),
)

/** A memory that was put in front of the model, with the reason it was chosen. */
@Serializable
data class UsedMemory(val id: String, val type: String, val content: String, val confidence: Float, val why: String)

/** Set when the request was an explicit "remember that…" command instead of a question. */
@Serializable
data class MemoryWritten(val id: String, val type: String, val content: String, val updatedExisting: Boolean, val embedded: Boolean)

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
    /** Memory-aware answering (Milestone 7). */
    val memoriesUsed: List<UsedMemory> = emptyList(),
    /** How many transcript lines the answer was given, after the server-side cap. */
    val transcriptLines: Int = 0,
    val memoryWritten: MemoryWritten? = null,
    val retrievalMillis: Long? = null,
    val semanticRetrieval: Boolean = false,
    val retrievalNote: String? = null,
)

/**
 * Milestone 6 text AI.
 *
 *   POST /ai/respond   { prompt, tier?, maxOutputTokens?, sessionId?, promptVersion?,
 *                        mode?, wit?, useMemory?, transcript? }
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
    retrieval: MemoryRetrievalEngine? = null,
    writeEngine: MemoryWriteEngine? = null,
) {
    val log = LoggerFactory.getLogger("operator-ai")

    post("/ai/respond") {
        val request = call.receive<AskRequest>()
        if (request.prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "prompt must not be blank"))
            return@post
        }
        val mode = request.mode?.let { raw ->
            OperatorMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "mode must be one of ${OperatorMode.entries.joinToString { it.name }}"))
                    return@post
                }
        } ?: OperatorMode.ACTIVE
        val wit = request.wit?.let { raw ->
            WitLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "wit must be one of ${WitLevel.entries.joinToString { it.name }}"))
                    return@post
                }
        } ?: WitLevel.NORMAL

        // An explicit "remember that…" is stored directly. No model call: the user already said
        // exactly what to keep, and a round trip would only add latency and a way to get it wrong.
        if (request.useMemory && writeEngine != null) {
            val command = writeEngine.detect(request.prompt)
            if (command != null) {
                val written = try {
                    writeEngine.write(command, mode = mode, sourceReference = request.sessionId?.let { "session:$it" })
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (e.message ?: "cannot write memory in this mode")))
                    return@post
                }
                log.info("Stored explicit memory {} ({})", written.memory.id, written.memory.memoryType)
                call.respond(
                    AskResponse(
                        text = written.confirmation,
                        model = "none",
                        tier = "NONE",
                        routingReason = "explicit memory command, stored without a model call",
                        promptVersion = null,
                        latencyMillis = 0,
                        memoryWritten = MemoryWritten(
                            id = written.memory.id, type = written.memory.memoryType.name, content = written.memory.content,
                            updatedExisting = written.updatedExisting, embedded = written.embedded,
                        ),
                    ),
                )
                return@post
            }
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

        val retrieved = if (request.useMemory && retrieval != null) {
            try {
                retrieval.retrieve(request.prompt, mode = mode, trigger = RetrievalTrigger.DIRECT_REQUEST)
            } catch (e: Exception) {
                log.warn("Memory retrieval failed, answering without it: {}", e.message)
                null
            }
        } else null

        // The window is the phone's, but the size limit is ours: a client should not be able to
        // push an unbounded prompt through us. Newest lines win — they are the relevant ones.
        val transcriptLines = request.transcript
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_TRANSCRIPT_LINES)
            .map { it.take(MAX_TRANSCRIPT_LINE_CHARS) }
        val rollingTranscript = transcriptLines.joinToString("\n").ifBlank { null }

        val promptVersion = request.promptVersion ?: defaultPromptVersion
        val systemPrompt = prompts.load(promptVersion)
        if (systemPrompt == null) log.warn("Prompt version {} unavailable; sending without a system prompt", promptVersion)

        (provider as? OpenRouterProvider)?.fallbacks = decision.fallbacks

        val startedAt = System.nanoTime()
        val result = try {
            provider.generate(
                AIRequest(
                    modelId = decision.modelId,
                    systemPrompt = listOfNotNull(
                        systemPrompt,
                        ContextAssembler.build(
                            mode, wit, retrieved, RetrievalTrigger.DIRECT_REQUEST,
                            rollingTranscript = rollingTranscript,
                        ),
                    ).joinToString("\n\n"),
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
                memoriesUsed = retrieved?.memories.orEmpty().map {
                    UsedMemory(it.memory.id, it.memory.memoryType.name, it.memory.content, it.memory.confidence, it.why)
                },
                transcriptLines = transcriptLines.size,
                retrievalMillis = retrieved?.latencyMillis,
                semanticRetrieval = retrieved?.semanticUsed ?: false,
                retrievalNote = retrieved?.note,
            ),
        )
    }

    get("/usage") { call.respond(usage.report()) }
}

/**
 * Caps on the transcript a client may attach. The phone already bounds its own window
 * (Milestone 11), but the backend must not depend on a well-behaved client to keep prompts
 * finite — or costs predictable.
 */
const val MAX_TRANSCRIPT_LINES = 80
const val MAX_TRANSCRIPT_LINE_CHARS = 500
