package com.operator.backend.feedback

import com.operator.core.feedback.CommentFeedback
import com.operator.core.feedback.VerdictKind
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plain JDBC, one transaction per call on Dispatchers.IO, matching PostgresMemoryStore (ADR-020). */
class PostgresFeedbackStore(private val dataSource: DataSource) : FeedbackStore {
    override val backendName = "postgresql"

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val result = block(c)
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    override suspend fun record(userId: UUID, feedback: CommentFeedback, sessionId: UUID?): StoredFeedback = tx { c ->
        val id = UUID.randomUUID()
        val at = if (feedback.atMillis > 0) feedback.atMillis else System.currentTimeMillis()
        c.prepareStatement(
            """
            INSERT INTO comment_feedback
                (id, user_id, session_id, comment, verdict, trigger, confidence, relevance, category, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, id)
            st.setObject(2, userId)
            st.setObject(3, sessionId)
            st.setString(4, feedback.comment)
            st.setString(5, feedback.verdict.name)
            st.setString(6, feedback.trigger)
            st.setFloat(7, feedback.confidence)
            st.setFloat(8, feedback.relevance)
            st.setString(9, feedback.category)
            st.setString(10, feedback.note)
            st.setTimestamp(11, Timestamp.from(Instant.ofEpochMilli(at)))
            st.executeUpdate()
        }
        StoredFeedback(id, feedback.copy(atMillis = at), sessionId)
    }

    override suspend fun recent(userId: UUID, limit: Int): List<StoredFeedback> = tx { c ->
        c.prepareStatement(
            """
            SELECT id, session_id, comment, verdict, trigger, confidence, relevance, category, note, created_at
            FROM comment_feedback WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, userId)
            st.setInt(2, limit.coerceIn(1, 200))
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toStored()) }
                    // The query takes the newest N, then this puts them back in spoken order.
                    .reversed()
            }
        }
    }

    override suspend fun summary(userId: UUID, sinceMillis: Long): FeedbackSummary = tx { c ->
        c.prepareStatement(
            """
            SELECT id, session_id, comment, verdict, trigger, confidence, relevance, category, note, created_at
            FROM comment_feedback WHERE user_id = ? AND created_at >= ?
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, userId)
            st.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(sinceMillis)))
            st.executeQuery().use { rs ->
                summarize(buildList { while (rs.next()) add(rs.toStored()) })
            }
        }
    }

    private fun ResultSet.toStored(): StoredFeedback = StoredFeedback(
        id = getObject("id", UUID::class.java),
        sessionId = getObject("session_id", UUID::class.java),
        feedback = CommentFeedback(
            comment = getString("comment"),
            // A row whose verdict the code no longer knows is dropped to UNWANTED rather than
            // crashing the read: an unreadable verdict on an old row should not take out the
            // whole summary, and treating it as a complaint is the cautious direction.
            verdict = verdictOrNull(getString("verdict")) ?: VerdictKind.UNWANTED,
            trigger = getString("trigger"),
            confidence = getFloat("confidence"),
            relevance = getFloat("relevance"),
            category = getString("category"),
            note = getString("note"),
            atMillis = getTimestamp("created_at").toInstant().toEpochMilli(),
        ),
    )
}
