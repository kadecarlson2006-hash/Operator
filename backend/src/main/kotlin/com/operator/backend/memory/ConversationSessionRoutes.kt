package com.operator.backend.memory

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** CRUD API for the conversation_sessions audit records created by Operator conversations. */
fun Route.conversationSessionRoutes(store: MemoryStore) {
    route("/sessions") {
        get { call.respond(store.listSessions(DEFAULT_USER_ID)) }
        post {
            call.respond(
                HttpStatusCode.Created,
                store.createSession(DEFAULT_USER_ID, call.receive<NewConversationSession>()),
            )
        }
        get("/{id}") { call.respond(store.getSession(DEFAULT_USER_ID, sessionId(call.parameters["id"]))) }
        patch("/{id}") {
            call.respond(
                store.updateSession(
                    DEFAULT_USER_ID,
                    sessionId(call.parameters["id"]),
                    call.receive<ConversationSessionUpdate>(),
                ),
            )
        }
        delete("/{id}") {
            store.deleteSession(DEFAULT_USER_ID, sessionId(call.parameters["id"]))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun sessionId(value: String?) = parseUuid(value ?: throw MemoryValidationException("id missing"), "id")
