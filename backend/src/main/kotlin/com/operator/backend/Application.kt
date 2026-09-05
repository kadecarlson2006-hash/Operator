package com.operator.backend

import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.NoDatabase
import com.operator.backend.db.PostgresGateway
import com.operator.backend.health.HealthReporter
import com.operator.backend.health.healthRoutes
import com.operator.backend.providers.ProviderRegistry
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
import org.slf4j.LoggerFactory

const val BACKEND_VERSION = "0.4.0-m4"

/** Everything the server needs, built once at startup and replaceable with fakes in tests. */
class BackendDependencies(
    val config: BackendConfig,
    val database: DatabaseGateway,
    val providers: ProviderRegistry,
) {
    val health = HealthReporter(
        version = BACKEND_VERSION,
        promptVersion = config.promptVersion,
        database = database,
        providers = providers.status,
        redactedConfig = config.redacted(),
    )

    fun close() = database.close()

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
            return BackendDependencies(config, database, ProviderRegistry(config))
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
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("operator-backend").error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }
    routing {
        get("/") { call.respond(mapOf("service" to "operator-backend", "version" to BACKEND_VERSION, "health" to "/health")) }
        healthRoutes(deps.health)
    }
}
