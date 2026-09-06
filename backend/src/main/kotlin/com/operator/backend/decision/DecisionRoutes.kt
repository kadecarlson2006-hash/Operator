package com.operator.backend.decision

import com.operator.backend.ai.MAX_TRANSCRIPT_LINES
import com.operator.backend.ai.MAX_TRANSCRIPT_LINE_CHARS
import com.operator.backend.usage.UsageTracker
import com.operator.core.decision.DecisionRequest
import com.operator.core.decision.DecisionTrigger
import com.operator.core.decision.ResponseDecisionEngine
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class DecideRequest(
    /** AMBIENT, DIRECT_ADDRESS or COMMENT_NOW. */
    val trigger: String = "AMBIENT",
    /** The phone's rolling window, oldest line first. */
    val transcript: List<String> = emptyList(),
    val mode: String? = null,
    val wit: String? = null,
    /** Operator's own recent comments, so it does not repeat itself. */
    val recentComments: List<String> = emptyList(),
    val muted: Boolean = false,
    val sessionId: String? = null,
)

@Serializable
data class DecideResponse(
    val shouldSpeak: Boolean,
    val category: String,
    /** A short diagnostic label. Never the model's reasoning (ADR-009). */
    val reasonCode: String?,
    val response: String? = null,
    val confidence: Float = 0f,
    val urgency: Float = 0f,
    val relevance: Float = 0f,
    /** True when the local rules refused before any model was consulted. */
    val gatedLocally: Boolean = false,
    val modelCalled: Boolean = false,
    val model: String? = null,
    /** True when the model wanted to speak and the local rules overruled it. */
    val suppressedAfterModel: Boolean = false,
    val latencyMillis: Long = 0,
)

/**
 * Milestone 12 decision stage.
 *
 *   POST /decide  { trigger, transcript[], mode?, wit?, recentComments[], muted?, sessionId? }
 *
 * Answers whether Operator should speak, and what. Silence is the ordinary outcome and is
 * returned as a normal 200: it is a decision, not a failure.
 *
 * This does not speak. Turning a decision into audio is the caller's job, which keeps the
 * judgement testable on its own and lets the phone decide how to deliver it.
 */
fun Route.decisionRoutes(engine: ResponseDecisionEngine, usage: UsageTracker) {
    post("/decide") {
        val body = call.receive<DecideRequest>()

        val trigger = DecisionTrigger.entries.firstOrNull { it.name.equals(body.trigger, ignoreCase = true) }
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "trigger must be one of ${DecisionTrigger.entries.joinToString { it.name }}"),
            )
        val mode = body.mode?.let { raw ->
            OperatorMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "mode must be one of ${OperatorMode.entries.joinToString { it.name }}"),
                )
        } ?: OperatorMode.ACTIVE
        val wit = body.wit?.let { raw ->
            WitLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "wit must be one of ${WitLevel.entries.joinToString { it.name }}"),
                )
        } ?: WitLevel.NORMAL

        // Same caps as the answer path: a client must not be able to make the prompt unbounded.
        val transcript = body.transcript
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_TRANSCRIPT_LINES)
            .map { it.take(MAX_TRANSCRIPT_LINE_CHARS) }

        val decision = try {
            engine.decide(
                DecisionRequest(
                    trigger = trigger,
                    recentTranscript = transcript.joinToString("\n"),
                    mode = mode,
                    wit = wit,
                    recentComments = body.recentComments.map { it.take(MAX_TRANSCRIPT_LINE_CHARS) },
                    muted = body.muted,
                ),
            )
        } catch (e: DecisionModelNotConfiguredException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
        }

        val outcome = (engine as? ModelDecisionEngine)?.lastOutcome ?: DecisionOutcome()
        if (outcome.modelCalled) {
            usage.record(
                kind = "decision",
                provider = "openrouter",
                model = outcome.modelId ?: "unknown",
                latencyMillis = outcome.latencyMillis,
                sessionId = body.sessionId,
                failed = outcome.reasonCode == "MODEL_UNAVAILABLE",
            )
        }

        call.respond(
            DecideResponse(
                shouldSpeak = decision.shouldSpeak,
                category = decision.category.name,
                reasonCode = decision.reasonCode,
                response = decision.response,
                confidence = decision.confidence,
                urgency = decision.urgency,
                relevance = decision.relevance,
                gatedLocally = outcome.gatedLocally,
                modelCalled = outcome.modelCalled,
                model = outcome.modelId,
                suppressedAfterModel = outcome.suppressedAfterModel,
                latencyMillis = outcome.latencyMillis,
            ),
        )
    }
}
