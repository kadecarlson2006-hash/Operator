package com.operator.backend.memory

import com.operator.backend.BackendDependencies
import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider
import com.operator.backend.ai.NoEmbeddingProvider
import com.operator.backend.config.BackendConfig
import com.operator.backend.db.DatabaseGateway
import com.operator.backend.db.DatabaseHealth
import com.operator.backend.operatorModule
import com.operator.backend.providers.ProviderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRoutesTest {
    private object OkDb : DatabaseGateway { override suspend fun health() = DatabaseHealth(configured = true, reachable = true) }

    private fun ApplicationTestBuilder.setup(
        demoSeed: Boolean = false,
        store: MemoryStore = InMemoryMemoryStore(),
        embeddings: EmbeddingProvider = NoEmbeddingProvider,
    ): HttpClient {
        val config = BackendConfig(demoSeedEnabled = demoSeed)
        application { operatorModule(BackendDependencies(config, OkDb, ProviderRegistry(config), store, embeddings = embeddings)) }
        return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Test
    fun `full CRUD round trip over HTTP`() = testApplication {
        val client = setup()
        val created = client.post("/memory") {
            contentType(ContentType.Application.Json)
            setBody("""{"memoryType":"WORK_FACT","content":"Chris handles the west","importance":0.7,"privacyScope":"WORK"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        assertEquals("Chris handles the west", client.get("/memory/$id").body<Memory>().content)

        val search = client.get("/memory/search?text=west&type=work_fact&scope=WORK")
        assertEquals(HttpStatusCode.OK, search.status)
        assertEquals(1, Json.parseToJsonElement(search.bodyAsText()).jsonArray.size)
        assertEquals(0, Json.parseToJsonElement(client.get("/memory/search?text=east").bodyAsText()).jsonArray.size)

        val patched = client.patch("/memory/$id") { contentType(ContentType.Application.Json); setBody("""{"importance":0.95}""") }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(0.95f, patched.body<Memory>().importance)

        val events = client.get("/memory/$id/events")
        assertEquals(2, Json.parseToJsonElement(events.bodyAsText()).jsonArray.size)

        assertEquals(HttpStatusCode.NoContent, client.delete("/memory/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/memory/$id").status)
    }

    @Test
    fun `error mapping 400 404 409`() = testApplication {
        val client = setup()
        assertEquals(HttpStatusCode.BadRequest, client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"NOT_A_TYPE","content":"x"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"GOAL","content":"   "}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/memory/not-a-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/memory/search?type=bogus").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/memory/${java.util.UUID.randomUUID()}").status)

        val first = client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"GOAL","content":"Ship it"}""") }
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val dup = client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"GOAL","content":"ship it"}""") }
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals(firstId, Json.parseToJsonElement(dup.bodyAsText()).jsonObject["existingId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `embedding and similarity search over HTTP`() = testApplication {
        val client = setup()
        val a = client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"WORK_FACT","content":"late orders first"}""") }.body<Memory>()
        val b = client.post("/memory") { contentType(ContentType.Application.Json); setBody("""{"memoryType":"WORK_FACT","content":"truck stuck at the dock"}""") }.body<Memory>()
        assertEquals(HttpStatusCode.OK, client.put("/memory/${a.id}/embedding") { contentType(ContentType.Application.Json); setBody("""{"model":"fake","vector":[1,0,0]}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/memory/${b.id}/embedding") { contentType(ContentType.Application.Json); setBody("""{"model":"fake","vector":[0,1,0]}""") }.status)
        val hits = client.post("/memory/search/similar") { contentType(ContentType.Application.Json); setBody("""{"vector":[0,0.9,0.1],"limit":2}""") }
        assertEquals(HttpStatusCode.OK, hits.status)
        val arr = Json.parseToJsonElement(hits.bodyAsText()).jsonArray
        assertEquals(b.id, arr[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(arr[0].jsonObject["distance"]!!.jsonPrimitive.content.toFloat() < arr[1].jsonObject["distance"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun `embedding backfill is bounded repeatable and reports remaining work`() = testApplication {
        val store = InMemoryMemoryStore()
        val embeddings = FakeEmbeddings()
        val client = setup(store = store, embeddings = embeddings)
        repeat(3) { index ->
            client.post("/memory") {
                contentType(ContentType.Application.Json)
                setBody("""{"memoryType":"LONG_TERM_MEMORY","content":"memory $index needs embedding"}""")
            }
        }

        val first = client.post("/memory/backfill-embeddings?limit=2&batchSize=1")
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.body<EmbeddingBackfillResponse>()
        assertEquals(2, firstBody.embedded)
        assertTrue(firstBody.hasMore)

        val second = client.post("/memory/backfill-embeddings")
        val secondBody = second.body<EmbeddingBackfillResponse>()
        assertEquals(1, secondBody.embedded)
        assertEquals(false, secondBody.hasMore)
        assertEquals(3, store.search(DEFAULT_USER_ID, MemorySearch()).count { it.hasEmbedding })
    }

    @Test
    fun `embedding backfill reports missing configuration`() = testApplication {
        val unavailable = setup()
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.post("/memory/backfill-embeddings").status)
    }

    @Test
    fun `embedding backfill reports validation and provider failures`() = testApplication {
        val store = InMemoryMemoryStore()
        store.create(DEFAULT_USER_ID, NewMemory(com.operator.core.memory.MemoryType.LONG_TERM_MEMORY, "needs embedding"))
        val failing = setup(
            store = store,
            embeddings = FakeEmbeddings(failWith = AIProviderException("embedding service down", retryable = true)),
        )
        assertEquals(HttpStatusCode.BadGateway, failing.post("/memory/backfill-embeddings").status)
        assertEquals(HttpStatusCode.BadRequest, failing.post("/memory/backfill-embeddings?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, failing.post("/memory/backfill-embeddings?batchSize=abc").status)
    }

    @Test
    fun `demo seed is forbidden unless enabled and mark-incorrect works`() = testApplication {
        val client = setup(demoSeed = false)
        assertEquals(HttpStatusCode.Forbidden, client.post("/memory/demo-seed").status)
    }

    @Test
    fun `demo seed populates people projects and memories`() = testApplication {
        val client = setup(demoSeed = true)
        val seeded = client.post("/memory/demo-seed")
        assertEquals(HttpStatusCode.OK, seeded.status)
        val body = Json.parseToJsonElement(seeded.bodyAsText()).jsonObject
        assertEquals(12, body["created"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, Json.parseToJsonElement(client.get("/people").bodyAsText()).jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(client.get("/projects").bodyAsText()).jsonArray.size)
        val west = Json.parseToJsonElement(client.get("/memory/search?text=handles%20the%20west").bodyAsText()).jsonArray
        assertEquals(1, west.size)
        val id = west[0].jsonObject["id"]!!.jsonPrimitive.content
        val marked = client.post("/memory/$id/mark-incorrect") { contentType(ContentType.Application.Json); setBody("""{"reason":"Chris moved to the east"}""") }
        assertEquals(HttpStatusCode.OK, marked.status)
        assertEquals(0f, marked.body<Memory>().confidence)
        assertEquals(0, Json.parseToJsonElement(client.get("/memory/search?text=handles%20the%20west").bodyAsText()).jsonArray.size)
    }
}
