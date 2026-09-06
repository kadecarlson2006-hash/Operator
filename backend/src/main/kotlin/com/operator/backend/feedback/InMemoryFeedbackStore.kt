package com.operator.backend.feedback

import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.VerdictKind
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Feedback without a database. Bounded, because this is the fallback the server runs on when no
 * database is reachable and it must not grow for the life of the process.
 */
class InMemoryFeedbackStore(private val maxPerUser: Int = 500) : FeedbackStore {
    override val backendName: String = "in-memory"

    private val lock = Mutex()
    private val rows = LinkedHashMap<UUID, ArrayDeque<StoredFeedback>>()

    override suspend fun record(userId: UUID, feedback: CommentFeedback, sessionId: UUID?): StoredFeedback =
        lock.withLock {
            val stored = StoredFeedback(UUID.randomUUID(), feedback, sessionId)
            val queue = rows.getOrPut(userId) { ArrayDeque() }
            queue.addLast(stored)
            while (queue.size > maxPerUser) queue.removeFirst()
            stored
        }

    override suspend fun recent(userId: UUID, limit: Int): List<StoredFeedback> = lock.withLock {
        rows[userId].orEmpty().toList().takeLast(limit)
    }

    override suspend fun summary(userId: UUID, sinceMillis: Long): FeedbackSummary = lock.withLock {
        summarize(rows[userId].orEmpty().filter { it.feedback.atMillis >= sinceMillis })
    }
}

/** Shared by both stores so the two cannot disagree about what a summary means. */
internal fun summarize(items: List<StoredFeedback>): FeedbackSummary {
    if (items.isEmpty()) return FeedbackSummary()
    val complaints = items.map { it.feedback }.filter { it.verdict.isNegative }
    return FeedbackSummary(
        total = items.size,
        byVerdict = items.groupingBy { it.feedback.verdict }.eachCount(),
        meanConfidenceOfComplaints = complaints.takeIf { it.isNotEmpty() }?.map { it.confidence }?.average()?.toFloat(),
        meanRelevanceOfComplaints = complaints.takeIf { it.isNotEmpty() }?.map { it.relevance }?.average()?.toFloat(),
    )
}

/** Parses a verdict from the wire, or null. Shared so the route and the store agree. */
fun verdictOrNull(raw: String?): VerdictKind? =
    VerdictKind.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
