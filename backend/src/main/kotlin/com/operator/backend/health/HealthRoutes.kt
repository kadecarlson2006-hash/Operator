package com.operator.backend.health

import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.providers.ProviderStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "operator-backend",
    val version: String,
    val uptimeSeconds: Long,
    val promptVersion: String,
    val database: DatabaseHealth,
    val providers: ProviderStatus,
    val memoryBackend: String,
    val config: Map<String, String?>,
)

class HealthReporter(
    private val version: String,
    private val promptVersion: String,
    private val database: DatabaseGateway,
    private val providers: ProviderStatus,
    private val redactedConfig: Map<String, String?>,
    private val memoryBackend: String = "none",
    private val startedAtMillis: Long = System.currentTimeMillis(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun report(): HealthResponse {
        val db = database.health()
        return HealthResponse(
            status = if (db.reachable) "ok" else "degraded",
            version = version,
            uptimeSeconds = (clock() - startedAtMillis) / 1000,
            promptVersion = promptVersion,
            database = db,
            providers = providers,
            memoryBackend = memoryBackend,
            config = redactedConfig,
        )
    }
}

/** GET /health → 200 when the database answers, 503 (still with the full body) when it does not. */
fun Route.healthRoutes(reporter: HealthReporter) {
    get("/health") {
        val report = reporter.report()
        val code = if (report.status == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(code, report)
    }
}
