package com.operator.backend.feedback

import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.VerdictKind
import java.util.UUID

/** One stored verdict, as it comes back out. */
data class StoredFeedback(
    val id: UUID,
    val feedback: CommentFeedback,
    val sessionId: UUID? = null,
)

/** Counts over a window, for the diagnostics panel and for deciding whether a threshold is wrong. */
data class FeedbackSummary(
    val total: Int = 0,
    val byVerdict: Map<VerdictKind, Int> = emptyMap(),
    /** Mean confidence of the comments the user complained about, or null if there were none. */
    val meanConfidenceOfComplaints: Float? = null,
    /** Mean relevance of the same, or null. This is the number risk 44 has been waiting for. */
    val meanRelevanceOfComplaints: Float? = null,
) {
    val complaints: Int get() = byVerdict.filterKeys { it.isNegative }.values.sum()
}

/**
 * Storage for what the user thought of Operator's comments (Milestone 14).
 *
 * Narrower than [com.operator.backend.memory.MemoryStore] on purpose: feedback is appended and
 * read back in recency order, and nothing edits it. A verdict records what somebody thought at a
 * moment, so revising one later would be rewriting history rather than correcting data.
 */
interface FeedbackStore {
    val backendName: String

    suspend fun record(userId: UUID, feedback: CommentFeedback, sessionId: UUID? = null): StoredFeedback

    /** Newest last, so the list reads in the order things were said. */
    suspend fun recent(userId: UUID, limit: Int = DEFAULT_RECENT_LIMIT): List<StoredFeedback>

    suspend fun summary(userId: UUID, sinceMillis: Long): FeedbackSummary

    companion object {
        const val DEFAULT_RECENT_LIMIT = 20
    }
}
