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
import com.operator.backend.ai.PromptLibrary
import com.operator.backend.ai.aiRoutes
import com.operator.backend.health.healthRoutes
import com.operator.backend.memory.DuplicateMemoryException
import com.operator.backend.memory.EntityNotFoundException
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.memory.MemoryNotFoundException
import com.operator.backend.memory.MemoryRetrievalEngine
import com.operator.backend.memory.MemoryStore
import com.operator.backend.memory.MemoryWriteEngine
import com.operator.backend.memory.MemoryValidationException
import com.operator.backend.memory.PostgresMemoryStore
import com.operator.backend.memory.memoryRoutes
import io.ktor.serialization.JsonConvertException
import io.ktor.server.plugins.BadRequestException
import com.operator.backend.providers.ProviderRegistry
import com.operator.backend.providers.close
import com.operator.backend.usage.UsageTracker
import com.operator.core.ai.AIProvider
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
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

const val BACKEND_VERSION = "0.7.0-m7"

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
) {
    val modelRouter = ModelRouter(config.operator)

    /** Retrieval marks memories as used off the answer's latency path (ADR-025). */
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val retrieval = MemoryRetrievalEngine(memory, embeddings, touchScope = memoryScope)
    val writeEngine = MemoryWriteEngine(memory, embeddings)

    val health = HealthReporter(
        version = BACKEND_VERSION,
        promptVersion = config.promptVersion,
        database = database,
        providers = providers.status,
        redactedConfig = config.redacted(),
        memoryBackend = memory.backendName,
    )

    fun close() {
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
            val embeddings: EmbeddingProvider = if (config.openRouterConfigured && !config.operator.embeddingModelId.isNullOrBlank()) {
                OpenRouterEmbeddingProvider(config.openRouterApiKey!!, config.operator.embeddingModelId!!)
            } else {
                log.info("No embedding model configured; memory retrieval will be lexical and structured only")
                NoEmbeddingProvider
            }
            return BackendDependencies(config, database, ProviderRegistry(config), memory, embeddings = embeddings)
        }
    }
}

private class UnreachableDatabase(private val reason: String) : DatabaseGateway {
    override suspend fun health() = com.operator.backend.db.DatabaseHealth(configured = true, reachable = false, error = reason)
}

/** Ktor module: plugins + routes. Kept free of construction so tests can inject [deps]. */
fun Application.operatorModule(deps: BackendDependencies) {
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
        memoryRoutes(deps.memory, deps.config.demoSeedEnabled)
        aiRoutes(deps.ai, deps.modelRouter, deps.prompts, deps.usage, deps.config.promptVersion, deps.retrieval, deps.writeEngine)
    }
}
