package com.operator.backend.memory

import com.operator.backend.BackendDependencies
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationSessionRoutesTest {
    private object OkDb : DatabaseGateway {
        override suspend fun health() = DatabaseHealth(configured = true, reachable = true)
    }

    @Test
    fun `session CRUD round trip over HTTP`() = testApplication {
        val config = BackendConfig()
        application {
            operatorModule(BackendDependencies(config, OkDb, ProviderRegistry(config), InMemoryMemoryStore()))
        }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val createdResponse = client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"operatorMode":"WORK","witLevel":"DRY","promptVersion":"operator-v1"}""")
        }
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        val created = createdResponse.body<ConversationSession>()
        assertEquals(created, client.get("/sessions/${created.id}").body<ConversationSession>())
        assertEquals(listOf(created.id), client.get("/sessions").body<List<ConversationSession>>().map { it.id })

        val patched = client.patch("/sessions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"endedAt":"2999-01-01T00:00:00Z","summary":"Finished the task"}""")
        }.body<ConversationSession>()
        assertEquals("Finished the task", patched.summary)
        val reopened = client.patch("/sessions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"clearEndedAt":true,"clearSummary":true}""")
        }.body<ConversationSession>()
        assertNull(reopened.endedAt)
        assertNull(reopened.summary)

        assertEquals(HttpStatusCode.BadRequest, client.patch("/sessions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"operatorMode":"banana"}""")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.patch("/sessions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"witLevel":"banana"}""")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/sessions/not-a-uuid").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/sessions/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/sessions/${created.id}").status)
    }
}
