package com.operator.backend.db

import com.operator.backend.config.JdbcTarget
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

/**
 * HikariCP pool + Flyway migrations for PostgreSQL. Milestone 4 only proves connectivity and
 * that pgvector is installed; Milestone 5 adds the memory schema as further migrations.
 */
class PostgresGateway private constructor(private val dataSource: HikariDataSource, private val migrationsApplied: Int) : DatabaseGateway {

    override suspend fun health(): DatabaseHealth = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { c ->
                val serverVersion = c.metaData.databaseProductVersion
                val vector = c.prepareStatement("SELECT extversion FROM pg_extension WHERE extname = 'vector'").use { st ->
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
                DatabaseHealth(
                    configured = true,
                    reachable = true,
                    pgvector = vector,
                    migrationsApplied = migrationsApplied,
                    serverVersion = serverVersion,
                )
            }
        } catch (e: Exception) {
            DatabaseHealth(configured = true, reachable = false, error = e.message ?: e::class.simpleName)
        }
    }

    override fun close() = dataSource.close()

    companion object {
        private val log = LoggerFactory.getLogger(PostgresGateway::class.java)

        /** Opens the pool and runs migrations. Throws if the database cannot be reached at startup. */
        fun open(target: JdbcTarget, migrate: Boolean = true): PostgresGateway {
            val config = HikariConfig().apply {
                jdbcUrl = target.url
                username = target.user
                password = target.password
                maximumPoolSize = 8
                minimumIdle = 1
                connectionTimeout = 5_000
                poolName = "operator-pg"
            }
            val ds = HikariDataSource(config)
            val applied = if (migrate) {
                val result = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
                log.info("Flyway: {} migration(s) applied, schema at {}", result.migrationsExecuted, result.targetSchemaVersion)
                result.migrationsExecuted
            } else 0
            return PostgresGateway(ds, applied)
        }
    }
}
