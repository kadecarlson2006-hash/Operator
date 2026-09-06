package com.operator.backend.feedback

import com.operator.backend.memory.DEFAULT_USER_ID
import com.operator.core.decision.ConversationPolicy
import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.VerdictKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class FeedbackRequest(
    /** What Operator said, verbatim. */
    val comment: String,
    /** HELPFUL, UNWANTED, WRONG or TOO_LATE. */
    val verdict: String,
    val trigger: String = "AMBIENT",
    val confidence: Float = 0f,
    val relevance: Float = 0f,
    val category: String? = null,
    val note: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class FeedbackResponse(
    val id: String,
    val verdict: String,
    /** How much the floors are now raised. 0 when nothing recent applies. */
    val penalty: Float,
    val note: String,
)

@Serializable
data class FeedbackSummaryResponse(
    val total: Int,
    val complaints: Int,
    val byVerdict: Map<String, Int>,
    val meanConfidenceOfComplaints: Float? = null,
    val meanRelevanceOfComplaints: Float? = null,
    val penalty: Float,
    val windowHours: Int,
)

/**
 * Milestone 14 feedback.
 *
 *   POST /feedback          { comment, verdict, trigger?, confidence?, relevance?, category?, note? }
 *   GET  /feedback/recent   what was said and what the user thought of it
 *   GET  /feedback/summary  counts, and the scores the complained-about comments carried
 *
 * The summary is the point of the whole milestone. Until now the confidence and relevance floors
 * have been guesses nothing could test (risk 44), because the floors only judge a model that wants
 * to speak and it rarely does. `meanRelevanceOfComplaints` is the first number that says what the
 * floor *should* have been: the comments the user did not want, and what they scored.
 */
fun Route.feedbackRoutes(
    store: FeedbackStore,
    policy: ConversationPolicy,
    /** Called after a verdict lands, so the decision prompt sees the same list the gate does. */
    onRecorded: (CommentFeedback) -> Unit = {},
) {
    val log = LoggerFactory.getLogger("operator-feedback")

    route("/feedback") {
        post {
            val body = call.receive<FeedbackRequest>()
            if (body.comment.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "comment must not be blank"))
                return@post
            }
            val verdict = verdictOrNull(body.verdict) ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "verdict must be one of ${VerdictKind.entries.joinToString { it.name }}"),
                )
                return@post
            }
            val sessionId = body.sessionId?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId must be a UUID"))
                    return@post
                }
            }

            val feedback = CommentFeedback(
                comment = body.comment.trim(),
                verdict = verdict,
                trigger = body.trigger,
                confidence = body.confidence,
                relevance = body.relevance,
                category = body.category,
                note = body.note?.takeIf { it.isNotBlank() },
                atMillis = System.currentTimeMillis(),
            )
            val stored = store.record(DEFAULT_USER_ID, feedback, sessionId)

            // The gate is consulted before the model on every ambient moment, so it holds its own
            // copy rather than reading the store each time. Recording here keeps the two in step
            // without putting a query on the cheapest part of the pipeline.
            policy.recordFeedback(stored.feedback)
            onRecorded(stored.feedback)

            // The verdict and the scores, never the comment: the log should not accumulate a
            // transcript of everything Operator has ever said.
            log.info(
                "Feedback {} on a {} comment (confidence {}, relevance {}); penalty now {}",
                verdict.name, body.trigger, body.confidence, body.relevance, policy.feedbackPenalty(),
            )

            call.respond(
                FeedbackResponse(
                    id = stored.id.toString(),
                    verdict = verdict.name,
                    penalty = policy.feedbackPenalty(),
                    note = if (verdict.isNegative) {
                        "Recorded. Operator will be harder to trigger for a while."
                    } else {
                        // Said plainly because the asymmetry surprises people who expect a thumbs
                        // up to make an assistant chattier (ADR-045).
                        "Recorded. Approval does not make Operator speak more; it only stops it speaking less."
                    },
                ),
            )
        }

        get("/recent") {
            call.respond(
                store.recent(DEFAULT_USER_ID).map {
                    mapOf(
                        "id" to it.id.toString(),
                        "comment" to it.feedback.comment,
                        "verdict" to it.feedback.verdict.name,
                        "trigger" to it.feedback.trigger,
                        "confidence" to it.feedback.confidence.toString(),
                        "relevance" to it.feedback.relevance.toString(),
                        "atMillis" to it.feedback.atMillis.toString(),
                    )
                },
            )
        }

        get("/summary") {
            val since = System.currentTimeMillis() - SUMMARY_WINDOW_MILLIS
            val s = store.summary(DEFAULT_USER_ID, since)
            call.respond(
                FeedbackSummaryResponse(
                    total = s.total,
                    complaints = s.complaints,
                    byVerdict = s.byVerdict.entries.associate { (k, v) -> k.name to v },
                    meanConfidenceOfComplaints = s.meanConfidenceOfComplaints,
                    meanRelevanceOfComplaints = s.meanRelevanceOfComplaints,
                    penalty = policy.feedbackPenalty(),
                    windowHours = (SUMMARY_WINDOW_MILLIS / 3_600_000L).toInt(),
                ),
            )
        }
    }
}

/** A day. Long enough to cover a working session, short enough to describe recent behaviour. */
private const val SUMMARY_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
