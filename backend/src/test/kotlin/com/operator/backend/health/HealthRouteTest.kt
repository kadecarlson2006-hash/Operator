package com.operator.backend.health

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HealthRouteTest {

    private class FakeDatabase(private val result: DatabaseHealth) : DatabaseGateway {
        override suspend fun health() = result
    }

    private fun deps(db: DatabaseHealth, config: BackendConfig = BackendConfig()) =
        BackendDependencies(config, FakeDatabase(db), ProviderRegistry(config))

    @Test
    fun `healthy database reports ok with 200`() = testApplication {
        application { operatorModule(deps(DatabaseHealth(configured = true, reachable = true, pgvector = "0.8.6", migrationsApplied = 1, serverVersion = "17.2"))) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals("0.8.6", json["database"]!!.jsonObject["pgvector"]!!.jsonPrimitive.content)
        assertEquals("operator-system-v1", json["promptVersion"]!!.jsonPrimitive.content)
        assertFalse(json["providers"]!!.jsonObject["ai"]!!.jsonObject["configured"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `missing database degrades with 503 but still describes everything`() = testApplication {
        val config = BackendConfig.fromMap(mapOf(BackendConfig.Keys.OPENROUTER_API_KEY to "sk-or-v1-secretsecretsecret"))
        application { operatorModule(deps(DatabaseHealth(configured = false, reachable = false, error = "DATABASE_URL not set"), config)) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("degraded", json["status"]!!.jsonPrimitive.content)
        assertEquals("DATABASE_URL not set", json["database"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("set (…cret)", json["config"]!!.jsonObject["openRouterApiKey"]!!.jsonPrimitive.content)
        assertFalse(body.contains("secretsecret"), "raw secret must never be in the response")
        assertNull(json["config"]!!.jsonObject["databaseUrl"]!!.jsonPrimitive.contentOrNull())
    }

    @Test
    fun `root lists the health endpoint`() = testApplication {
        application { operatorModule(deps(DatabaseHealth(configured = true, reachable = true))) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("/health", Json.parseToJsonElement(response.bodyAsText()).jsonObject["health"]!!.jsonPrimitive.content)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
