package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider
import com.operator.core.memory.MemoryType
import com.operator.core.model.OperatorMode
import org.slf4j.LoggerFactory
import java.util.UUID

/** What an explicit memory command turned into. */
data class MemoryWriteResult(
    val memory: Memory,
    val updatedExisting: Boolean,
    val embedded: Boolean,
    val confirmation: String,
)

/** A recognised "remember that…" command and the fact it carries. */
data class ExplicitMemoryCommand(val content: String, val phrase: String)

/**
 * The explicit-memory path: the user says "remember that X" and X is stored (ADR-027).
 *
 * Command detection is deterministic string matching, not a model call. Two reasons: the brief
 * requires explicit user commands to be treated as high confidence, and a phrase the user
 * deliberately spoke should never be lost to a model's judgement, or cost a round trip. Detection
 * happens before any model call, so storing a memory is fast and free.
 *
 * The flow follows the brief: extract, normalise, look for a duplicate, create or update, embed,
 * confirm briefly.
 */
class MemoryWriteEngine(
    private val store: MemoryStore,
    private val embeddings: EmbeddingProvider,
) {

    /** Returns the fact to store, or null when this is an ordinary question. */
    fun detect(input: String): ExplicitMemoryCommand? {
        val trimmed = input.trim().removePrefix("Operator,").removePrefix("operator,").trim()
        val lower = trimmed.lowercase()
        val phrase = TRIGGERS.firstOrNull { lower.startsWith(it) } ?: return null
        val content = normalise(trimmed.substring(phrase.length))
        if (content.isBlank()) return null
        return ExplicitMemoryCommand(content, phrase)
    }

    suspend fun write(
        command: ExplicitMemoryCommand,
        userId: UUID = DEFAULT_USER_ID,
        mode: OperatorMode = OperatorMode.ACTIVE,
        sourceReference: String? = null,
    ): MemoryWriteResult {
        val scope = MemoryScopePolicy.defaultWriteScope(mode)
        require(MemoryScopePolicy.mayWrite(mode, scope)) { "writing is not permitted in $mode" }

        val person = store.listPeople(userId).firstOrNull { p ->
            (listOf(p.name) + p.aliases).any { it.isNotBlank() && Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(command.content) }
        }
        val project = store.listProjects(userId).firstOrNull { command.content.contains(it.name, ignoreCase = true) }

        val candidate = NewMemory(
            memoryType = classify(command.content, person != null),
            content = command.content,
            sourceType = SourceType.EXPLICIT_USER,
            sourceReference = sourceReference,
            importance = EXPLICIT_IMPORTANCE,
            confidence = EXPLICIT_CONFIDENCE,
            personId = person?.id,
            projectId = project?.id,
            privacyScope = scope,
        )

        var updatedExisting = false
        val stored = try {
            store.create(userId, candidate)
        } catch (e: DuplicateMemoryException) {
            // Saying it again is a reaffirmation, not an error: refresh confidence and importance.
            updatedExisting = true
            store.update(
                userId, UUID.fromString(e.existingId),
                MemoryUpdate(confidence = EXPLICIT_CONFIDENCE, importance = EXPLICIT_IMPORTANCE, isActive = true),
            )
        }

        val embedded = embed(userId, stored)
        return MemoryWriteResult(stored, updatedExisting, embedded, confirmation(updatedExisting, stored.id))
    }

    private suspend fun embed(userId: UUID, memory: Memory): Boolean {
        if (!embeddings.available) return false
        val model = embeddings.modelId ?: return false
        return try {
            val vector = embeddings.embed(listOf(memory.content)).firstOrNull() ?: return false
            store.putEmbedding(userId, UUID.fromString(memory.id), EmbeddingInput(model, vector))
            true
        } catch (e: AIProviderException) {
            // A stored memory without a vector is still findable lexically; never lose the write.
            log.warn("Stored memory {} without an embedding: {}", memory.id, e.message)
            false
        }
    }

    /**
     * Deliberately shallow typing. A wrong guess here is visible and correctable in the memory
     * screen, whereas a model call would add latency and a new way to be wrong.
     */
    private fun classify(content: String, mentionsPerson: Boolean): MemoryType {
        val lower = content.lowercase()
        return when {
            COMMITMENT_CUES.any { it in lower } -> MemoryType.COMMITMENT
            DECISION_CUES.any { it in lower } -> MemoryType.DECISION
            GOAL_CUES.any { it in lower } -> MemoryType.GOAL
            PREFERENCE_CUES.any { it in lower } -> MemoryType.PREFERENCE
            mentionsPerson -> MemoryType.PERSON
            else -> MemoryType.LONG_TERM_MEMORY
        }
    }

    /** Short, in character, and never chatty. */
    private fun confirmation(updated: Boolean, id: String): String {
        if (updated) return "Already noted, sir."
        val index = (id.hashCode().toLong() and 0xFFFF).toInt() % CONFIRMATIONS.size
        return CONFIRMATIONS[index]
    }

    private fun normalise(raw: String): String =
        raw.trim()
            .removePrefix(":").trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', ',', ';')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private companion object {
        val log = LoggerFactory.getLogger(MemoryWriteEngine::class.java)
        const val EXPLICIT_IMPORTANCE = 0.8f
        const val EXPLICIT_CONFIDENCE = 0.95f

        /** Longest first, so "remember that" wins over "remember". */
        val TRIGGERS = listOf(
            "don't let me forget that", "dont let me forget that", "don't let me forget", "dont let me forget",
            "remember that", "remember this", "remember", "make a note that", "make a note of", "make a note",
            "note that", "store this", "store that", "keep in mind that", "keep in mind",
        ).sortedByDescending { it.length }

        val CONFIRMATIONS = listOf("Stored.", "Understood, sir.", "Consider it remembered.", "Noted.")
        val COMMITMENT_CUES = listOf("promised", "i will", "i'll", "by friday", "by monday", "deadline", "owe", "due")
        val DECISION_CUES = listOf("decided", "we are not", "i am not", "not buying", "chose", "going with")
        val GOAL_CUES = listOf("goal", "aim to", "want to ship", "target")
        val PREFERENCE_CUES = listOf("prefer", "likes", "dislikes", "hates", "favourite", "favorite")
    }
}
