package com.operator.core.decision

import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel

/**
 * Contract for the decision stage (Milestone 12). Given conversation context, decide whether
 * Operator should speak at all, and if so what. Implementations must treat NO_RESPONSE as the
 * default and must be safe to call frequently.
 *
 * The request type is intentionally minimal for now; it will grow a rolling transcript,
 * retrieved memories, and trigger information when those subsystems exist.
 */
interface ResponseDecisionEngine {
    suspend fun decide(request: DecisionRequest): ResponseDecision
}

data class DecisionRequest(
    val trigger: DecisionTrigger,
    val recentTranscript: String = "",
    /** Decides whether Operator may volunteer at all (Milestone 12). */
    val mode: OperatorMode = OperatorMode.ACTIVE,
    val wit: WitLevel = WitLevel.NORMAL,
    /** Operator's own recent comments, so it can avoid repeating itself. */
    val recentComments: List<String> = emptyList(),
    /** Set when the user has muted; nothing may be said regardless of anything else. */
    val muted: Boolean = false,
)

enum class DecisionTrigger { AMBIENT, DIRECT_ADDRESS, COMMENT_NOW }

/** Always silent. Kept as the honest default when no decision model is configured. */
object SilentDecisionEngine : ResponseDecisionEngine {
    override suspend fun decide(request: DecisionRequest): ResponseDecision =
        ResponseDecision.silence(reasonCode = "ENGINE_NOT_IMPLEMENTED")
}
