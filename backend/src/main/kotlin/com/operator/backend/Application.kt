package com.operator.backend

import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.NoDatabase
import com.operator.backend.db.PostgresGateway
import com.operator.backend.health.HealthReporter
import com.operator.backend.ai.EmbeddingProvider
import com.operator.backend.ai.ModelRouter
import com.operator.backend.ai.NoEmbeddingProvider
import com.operator.backend.ai.OpenRouterEmbeddingProvider
import com.operator.backend.ai.WebSearchOptions
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.ai.aiRoutes
import com.operator.backend.health.healthRoutes
import com.operator.backend.memory.DuplicateMemoryException
import com.operator.backend.memory.EntityNotFoundException
import com.operator.backend.camera.NoVisionProvider
import com.operator.backend.camera.OpenRouterVisionProvider
import com.operator.backend.camera.VisionProvider
import com.operator.backend.camera.visionRoutes
import com.operator.backend.feedback.FeedbackStore
import com.operator.backend.feedback.InMemoryFeedbackStore
import com.operator.backend.feedback.PostgresFeedbackStore
import com.operator.backend.feedback.feedbackRoutes
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.memory.MemoryNotFoundException
import com.operator.backend.memory.MemoryRetrievalEngine
import com.operator.backend.memory.DEFAULT_USER_ID
import com.operator.backend.memory.MemoryStore
import com.operator.backend.memory.MemoryWriteEngine
import com.operator.backend.memory.MemoryValidationException
import com.operator.backend.memory.PostgresMemoryStore
import com.operator.backend.memory.memoryRoutes
import io.ktor.serialization.JsonConvertException
import io.ktor.server.plugins.BadRequestException
import com.operator.backend.decision.ModelDecisionEngine
import com.operator.backend.decision.decisionRoutes
import com.operator.backend.providers.ProviderRegistry
import com.operator.backend.transcription.transcriptionRoutes
import com.operator.backend.providers.close
import com.operator.backend.usage.UsageTracker
import com.operator.backend.tts.ttsRoutes
import com.operator.core.ai.AIProvider
import com.operator.core.decision.ConversationPolicy
import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.FeedbackAdjustment
import com.operator.core.feedback.VerdictKind
import com.operator.core.transcription.TranscriptionProvider
import com.operator.core.tts.TTSProvider
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

const val BACKEND_VERSION = "0.16.0-m16"

/**
 * How many rejected remarks go into the decision prompt. Enough to show a pattern, few enough
 * that they do not crowd out the conversation the model is meant to be judging.
 */
private const val MAX_UNWANTED_IN_PROMPT = 5

/** Everything the server needs, built once at startup and replaceable with fakes in tests. */
class BackendDependencies(
    val config: BackendConfig,
    val database: DatabaseGateway,
    val providers: ProviderRegistry,
    val memory: MemoryStore,
    val usage: UsageTracker = UsageTracker(),
    val prompts: PromptLibrary = PromptLibrary(),
    /** Defaults to the configured provider; tests inject a fake. */
    val ai: AIProvider = providers.ai,
    val embeddings: EmbeddingProvider = NoEmbeddingProvider,
    val transcription: TranscriptionProvider = providers.transcription,
    val tts: TTSProvider = providers.tts,
    /**
     * Milestone 14. Last in the list rather than beside `memory` where it belongs conceptually:
     * several tests construct this positionally, and a parameter inserted mid-list silently
     * reassigns every argument after it.
     */
    val feedback: FeedbackStore = InMemoryFeedbackStore(),
    /** Milestone 16. Off unless a vision model is configured; looking is never the default. */
    val vision: VisionProvider = NoVisionProvider,
) {
    val modelRouter = ModelRouter(config.operator)

    /**
     * Live web search for the answer path (ADR-052). Null when disabled, which is the default:
     * search is billed per call and adds seconds. Never applied to the decision path.
     */
    val webSearch: WebSearchOptions? =
        if (config.operator.webSearchEnabled) WebSearchOptions(maxResults = config.operator.webSearchMaxResults) else null

    /**
     * The anti-annoyance rules (Milestone 12). One instance for the process, because the interval
     * and five-minute cap are only meaningful if every decision is measured against the same
     * history of when Operator actually spoke.
     */
    val conversationPolicy = ConversationPolicy(
        minCommentIntervalSeconds = config.operator.minCommentIntervalSeconds,
        maxCommentsPer5Minutes = config.operator.maxCommentsPer5Minutes,
        minDecisionIntervalSeconds = config.operator.minDecisionIntervalSeconds,
        maxDecisionsPer5Minutes = config.operator.maxDecisionsPer5Minutes,
        // Milestone 14. Present unconditionally: with no feedback recorded the penalty is zero,
        // so this changes nothing until somebody actually complains.
        feedbackAdjustment = FeedbackAdjustment(),
    )

    /** Retrieval marks memories as used off the answer's latency path (ADR-025). */
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val retrieval = MemoryRetrievalEngine(memory, embeddings, touchScope = memoryScope)
    val writeEngine = MemoryWriteEngine(memory, embeddings)

    val decisionEngine = ModelDecisionEngine(
        provider = ai,
        policy = conversationPolicy,
        prompts = prompts,
        config = config.operator,
        retrieval = retrieval,
        unwantedComments = { recentlyUnwanted },
    )

    /**
     * The remarks the user rejected, kept in memory beside the policy's own copy.
     *
     * Read on the decision path, which is why it is not a store query: the same reasoning as the
     * policy's bounded feedback list. Refreshed at startup and on every new verdict.
     */
    @Volatile
    private var recentlyUnwanted: List<String> = emptyList()

    /**
     * Loads what the store already knows, so a restart does not forget yesterday's complaints.
     *
     * Failure here is deliberately not fatal: starting with no feedback means Operator is exactly
     * as talkative as its configured floors allow, which is the documented default rather than a
     * broken state.
     */
    suspend fun primeFeedback() {
        val recent = runCatching { feedback.recent(DEFAULT_USER_ID) }.getOrElse { return }
        conversationPolicy.setFeedback(recent.map { it.feedback })
        recentlyUnwanted = recent.map { it.feedback }
            .filter { it.verdict == VerdictKind.UNWANTED }
            .map { it.comment }
            .takeLast(MAX_UNWANTED_IN_PROMPT)
    }

    /** Adds one rejected remark to what the decision prompt sees. */
    fun noteFeedback(item: CommentFeedback) {
        if (item.verdict != VerdictKind.UNWANTED) return
        recentlyUnwanted = (recentlyUnwanted + item.comment).takeLast(MAX_UNWANTED_IN_PROMPT)
    }

    val health = HealthReporter(
        version = BACKEND_VERSION,
        promptVersion = config.promptVersion,
        database = database,
        providers = providers.status,
        redactedConfig = config.redacted(),
        memoryBackend = memory.backendName,
    )

    fun close() {
        (vision as? OpenRouterVisionProvider)?.close()
        memoryScope.cancel()
        (embeddings as? OpenRouterEmbeddingProvider)?.close()
        database.close()
        providers.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(BackendDependencies::class.java)

        /** Production wiring. A configured-but-unreachable database degrades health instead of crashing startup. */
        fun fromConfig(config: BackendConfig): BackendDependencies {
            val database: DatabaseGateway = config.jdbc?.let { target ->
                try {
                    PostgresGateway.open(target)
                } catch (e: Exception) {
                    log.error("Database unavailable at startup: {}", e.message)
                    UnreachableDatabase(e.message ?: e::class.simpleName ?: "unknown")
                }
            } ?: NoDatabase
            val memory: MemoryStore = (database as? PostgresGateway)?.let { PostgresMemoryStore(it.dataSource) }
                ?: InMemoryMemoryStore().also { log.warn("No reachable database: memory store is IN-MEMORY and will not survive a restart") }
            val feedback: FeedbackStore = (database as? PostgresGateway)?.let { PostgresFeedbackStore(it.dataSource) }
                ?: InMemoryFeedbackStore()
            val vision: VisionProvider = config.openRouterApiKey
                ?.takeIf { it.isNotBlank() && !config.operator.visionModelId.isNullOrBlank() }
                ?.let { OpenRouterVisionProvider(it, config.operator.visionModelId!!) }
                ?: NoVisionProvider
            val embeddings: EmbeddingProvider = if (config.openRouterConfigured && !config.operator.embeddingModelId.isNullOrBlank()) {
                OpenRouterEmbeddingProvider(config.openRouterApiKey!!, config.operator.embeddingModelId!!)
            } else {
                log.info("No embedding model configured; memory retrieval will be lexical and structured only")
                NoEmbeddingProvider
            }
            return BackendDependencies(config, database, ProviderRegistry(config), memory, embeddings = embeddings, feedback = feedback, vision = vision)
        }
    }
}

private class UnreachableDatabase(private val reason: String) : DatabaseGateway {
    override suspend fun health() = com.operator.backend.db.DatabaseHealth(configured = true, reachable = false, error = reason)
}

/** Ktor module: plugins + routes. Kept free of construction so tests can inject [deps]. */
fun Application.operatorModule(deps: BackendDependencies) {
    // Milestone 14: reload recent verdicts before serving, so a restart does not hand the user
    // back an Operator that has forgotten every complaint they made.
    launch { deps.primeFeedback() }

    install(ContentNegotiation) {
        json(Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<MemoryValidationException> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message)) }
        exception<BadRequestException> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.cause?.message ?: cause.message ?: "bad request"))) }
        exception<JsonConvertException> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "malformed JSON"))) }
        exception<MemoryNotFoundException> { call, cause -> call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message)) }
        exception<EntityNotFoundException> { call, cause -> call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message)) }
        exception<DuplicateMemoryException> { call, cause -> call.respond(HttpStatusCode.Conflict, mapOf("error" to cause.message, "existingId" to cause.existingId)) }
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("operator-backend").error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }
    routing {
        get("/") { call.respond(mapOf("service" to "operator-backend", "version" to BACKEND_VERSION, "health" to "/health")) }
        healthRoutes(deps.health)
        memoryRoutes(deps.memory, deps.config.demoSeedEnabled, deps.embeddings)
        aiRoutes(deps.ai, deps.modelRouter, deps.prompts, deps.usage, deps.config.promptVersion, deps.retrieval, deps.writeEngine, deps.webSearch)
        decisionRoutes(deps.decisionEngine, deps.usage)
        feedbackRoutes(deps.feedback, deps.conversationPolicy, deps::noteFeedback)
        visionRoutes(deps.vision, deps.usage)
        transcriptionRoutes(deps.transcription, deps.usage)
        ttsRoutes(
            deps.tts,
            deps.usage,
            deps.config.operator.elevenLabsVoiceId,
            deps.config.operator.elevenLabsModelId,
        )
    }
}
