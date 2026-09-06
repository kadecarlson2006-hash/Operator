package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider
import com.operator.backend.ai.NoEmbeddingProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MarkIncorrectRequest(val reason: String? = null)

@Serializable
data class SeedResponse(val created: Int, val skipped: Int, val people: Int, val projects: Int, val organizations: Int, val total: Long)

/**
 * Memory REST API (Milestone 5). Single default user until authentication exists.
 *
 *   GET    /memory/search?text=&type=&scope=&personId=&projectId=&organizationId=&includeInactive=&includeExpired=&limit=
 *   POST   /memory/search/similar         { vector, model?, limit?, filters… }
 *   POST   /memory/backfill-embeddings?limit=&batchSize=
 *   POST   /memory                        NewMemory → 201 (409 on active duplicate)
 *   GET    /memory/{id}
 *   PATCH  /memory/{id}                   MemoryUpdate (isActive=false disables, true re-enables)
 *   DELETE /memory/{id}                   hard delete → 204
 *   POST   /memory/{id}/mark-incorrect    { reason? }
 *   POST   /memory/{id}/touch
 *   GET    /memory/{id}/events
 *   PUT    /memory/{id}/embedding         { model, vector }
 *   POST   /memory/demo-seed              only when OPERATOR_DEMO_SEED_ENABLED=true
 *   GET/POST /people, /projects, /organizations
 */
fun Route.memoryRoutes(
    store: MemoryStore,
    demoSeedEnabled: Boolean,
    embeddings: EmbeddingProvider = NoEmbeddingProvider,
) {
    val backfill = EmbeddingBackfillService(store, embeddings)
    route("/memory") {
        get("/search") {
            val q = call.request.queryParameters
            val query = MemorySearch(
                text = q["text"] ?: q["q"],
                memoryType = q["type"]?.let { enumOr400<MemoryType>(it, "type") },
                privacyScopes = q.getAll("scope")?.map { enumOr400<PrivacyScope>(it, "scope") }?.toSet(),
                personId = q["personId"], organizationId = q["organizationId"], projectId = q["projectId"],
                includeInactive = q["includeInactive"]?.toBoolean() ?: false,
                includeExpired = q["includeExpired"]?.toBoolean() ?: false,
                limit = q["limit"]?.toIntOrNull() ?: 20,
            )
            call.respond(store.search(DEFAULT_USER_ID, query))
        }
        post("/search/similar") {
            call.respond(store.searchSimilar(DEFAULT_USER_ID, call.receive<SimilaritySearch>()))
        }
        post("/backfill-embeddings") {
            if (!backfill.available) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "no embedding model is configured"))
                return@post
            }
            val limit = call.queryInt("limit", EmbeddingBackfillService.DEFAULT_LIMIT)
            val batchSize = call.queryInt("batchSize", EmbeddingBackfillService.DEFAULT_BATCH_SIZE)
            try {
                call.respond(backfill.backfill(DEFAULT_USER_ID, limit, batchSize))
            } catch (e: AIProviderException) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "embedding backfill failed")))
            }
        }
        post {
            val created = store.create(DEFAULT_USER_ID, call.receive<NewMemory>())
            call.respond(HttpStatusCode.Created, created)
        }
        post("/demo-seed") {
            if (!demoSeedEnabled) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "demo seeding is disabled (set OPERATOR_DEMO_SEED_ENABLED=true)"))
                return@post
            }
            val r = DemoMemories.seed(store, DEFAULT_USER_ID)
            call.respond(SeedResponse(r.created, r.skipped, r.people, r.projects, r.organizations, store.count(DEFAULT_USER_ID)))
        }
        get("/{id}") { call.respond(store.get(DEFAULT_USER_ID, call.memoryId())) }
        patch("/{id}") { call.respond(store.update(DEFAULT_USER_ID, call.memoryId(), call.receive<MemoryUpdate>())) }
        delete("/{id}") {
            store.delete(DEFAULT_USER_ID, call.memoryId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/mark-incorrect") {
            val body = runCatching { call.receive<MarkIncorrectRequest>() }.getOrDefault(MarkIncorrectRequest())
            call.respond(store.markIncorrect(DEFAULT_USER_ID, call.memoryId(), body.reason))
        }
        post("/{id}/touch") { call.respond(store.touch(DEFAULT_USER_ID, call.memoryId())) }
        get("/{id}/events") { call.respond(store.events(DEFAULT_USER_ID, call.memoryId())) }
        put("/{id}/embedding") { call.respond(store.putEmbedding(DEFAULT_USER_ID, call.memoryId(), call.receive<EmbeddingInput>())) }
    }
    route("/people") {
        get { call.respond(store.listPeople(DEFAULT_USER_ID, call.request.queryParameters["includeInactive"]?.toBoolean() ?: false)) }
        post { call.respond(HttpStatusCode.Created, store.createPerson(DEFAULT_USER_ID, call.receive<NewPerson>())) }
    }
    route("/projects") {
        get { call.respond(store.listProjects(DEFAULT_USER_ID, call.request.queryParameters["includeInactive"]?.toBoolean() ?: false)) }
        post { call.respond(HttpStatusCode.Created, store.createProject(DEFAULT_USER_ID, call.receive<NewProject>())) }
    }
    route("/organizations") {
        get { call.respond(store.listOrganizations(DEFAULT_USER_ID, call.request.queryParameters["includeInactive"]?.toBoolean() ?: false)) }
        post { call.respond(HttpStatusCode.Created, store.createOrganization(DEFAULT_USER_ID, call.receive<NewOrganization>())) }
    }
}

private fun RoutingCall.memoryId(): UUID = parseUuid(parameters["id"] ?: throw MemoryValidationException("id missing"), "id")

private fun RoutingCall.queryInt(name: String, default: Int): Int =
    request.queryParameters[name]?.let { value ->
        value.toIntOrNull() ?: throw MemoryValidationException("$name must be an integer")
    } ?: default

private inline fun <reified E : Enum<E>> enumOr400(value: String, field: String): E =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw MemoryValidationException("$field must be one of ${enumValues<E>().joinToString { it.name }}")
