package com.operator.backend.db

import kotlinx.serialization.Serializable

@Serializable
data class DatabaseHealth(
    val configured: Boolean,
    val reachable: Boolean,
    val pgvector: String? = null,
    val migrationsApplied: Int? = null,
    val serverVersion: String? = null,
    val error: String? = null,
)

/** What the rest of the backend needs from the database layer. Fakeable in tests. */
interface DatabaseGateway {
    suspend fun health(): DatabaseHealth
    fun close() {}
}

/** Used when DATABASE_URL is absent: honest, never throws. */
object NoDatabase : DatabaseGateway {
    override suspend fun health() = DatabaseHealth(configured = false, reachable = false, error = "DATABASE_URL not set")
}
