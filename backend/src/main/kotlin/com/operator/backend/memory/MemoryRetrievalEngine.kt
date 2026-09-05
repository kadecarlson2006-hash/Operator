package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider
import com.operator.core.model.OperatorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One retrieved memory with the reason it was chosen, so a human can audit the selection. */
data class ScoredMemory(
    val memory: Memory,
    val score: Float,
    val semanticDistance: Float? = null,
    val lexicalHit: Boolean = false,
    val linkedEntity: Boolean = false,
) {
    val why: String
        get() = buildList {
            semanticDistance?.let { add("semantic %.2f".format(1f - it)) }
            if (lexicalHit) add("wording")
            if (linkedEntity) add("linked person or project")
            add("importance %.1f".format(memory.importance))
        }.joinToString(", ")
}

data class RetrievalResult(
    val memories: List<ScoredMemory>,
    val people: List<Person>,
    val semanticUsed: Boolean,
    val candidatesConsidered: Int,
    val latencyMillis: Long,
    val note: String? = null,
) {
    val isEmpty: Boolean get() = memories.isEmpty() && people.isEmpty()
}

/**
 * Chooses the few memories worth putting in front of the model (ADR-025).
 *
 * The brief is explicit that the whole database must never be dumped into a prompt, so this
 * gathers candidates from three cheap sources and then ranks them:
 *
 *   semantic   pgvector nearest neighbours of the query embedding, when an embedding model is
 *              configured and the memory has a stored vector
 *   lexical    substring matching on the significant words of the query
 *   structured memories linked to a person or project whose name appears in the query
 *
 * Ranking blends similarity, importance, confidence, recency of use, and whether the memory is
 * linked to an entity the query named. Only the top [limit] survive, and the reason each one was
 * chosen travels with it so the selection can be inspected rather than trusted.
 */
class MemoryRetrievalEngine(
    private val store: MemoryStore,
    private val embeddings: EmbeddingProvider,
    private val clock: Clock = Clock.systemUTC(),
    /** Marking retrieved memories as used is a write; it must never sit on the answer's latency path. */
    private val touchScope: CoroutineScope? = null,
) {

    suspend fun retrieve(
        query: String,
        userId: UUID = DEFAULT_USER_ID,
        mode: OperatorMode = OperatorMode.ACTIVE,
        trigger: RetrievalTrigger = RetrievalTrigger.DIRECT_REQUEST,
        limit: Int = DEFAULT_LIMIT,
    ): RetrievalResult {
        val startedAt = System.nanoTime()
        val scopes = MemoryScopePolicy.allowedScopes(mode, trigger)
        if (scopes.isEmpty() || query.isBlank()) {
            return RetrievalResult(emptyList(), emptyList(), false, 0, elapsed(startedAt), "no readable scopes in $mode")
        }

        val people = store.listPeople(userId).filter { it.mentionedIn(query) }
        val projects = store.listProjects(userId).filter { query.contains(it.name, ignoreCase = true) }

        val candidates = LinkedHashMap<String, ScoredMemory>()
        var note: String? = null

        // 1. Semantic neighbours, when embeddings are configured and the query embeds successfully.
        var semanticUsed = false
        if (embeddings.available) {
            try {
                val vector = embeddings.embed(listOf(query)).firstOrNull()
                if (vector != null) {
                    semanticUsed = true
                    store.searchSimilar(userId, SimilaritySearch(vector = vector, model = embeddings.modelId, limit = limit * SEMANTIC_OVERFETCH, privacyScopes = scopes))
                        // Nearest neighbours are returned however far away they are. Without a
                        // distance ceiling an unrelated question still drags in the closest
                        // memory, which is how a model ends up inventing a callback.
                        .filter { (it.distance ?: Float.MAX_VALUE) <= MAX_SEMANTIC_DISTANCE }
                        .forEach { m -> candidates[m.id] = ScoredMemory(m, 0f, semanticDistance = m.distance) }
                }
            } catch (e: AIProviderException) {
                note = "semantic retrieval unavailable: ${e.message}"
                log.warn("Falling back to lexical retrieval: {}", e.message)
            }
        } else {
            note = "no embedding model configured; lexical and structured retrieval only"
        }

        // 2. Lexical candidates, one pass per significant word so short questions still match.
        significantWords(query).take(MAX_LEXICAL_TERMS).forEach { word ->
            store.search(userId, MemorySearch(text = word, privacyScopes = scopes, limit = limit * 2)).forEach { m ->
                candidates[m.id] = candidates[m.id]?.copy(lexicalHit = true) ?: ScoredMemory(m, 0f, lexicalHit = true)
            }
        }

        // 3. Structured candidates: anything tied to a person or project the query named.
        (people.map { it.id to MemorySearch(personId = it.id, privacyScopes = scopes, limit = limit) } +
            projects.map { it.id to MemorySearch(projectId = it.id, privacyScopes = scopes, limit = limit) })
            .forEach { (_, search) ->
                store.search(userId, search).forEach { m ->
                    candidates[m.id] = candidates[m.id]?.copy(linkedEntity = true) ?: ScoredMemory(m, 0f, linkedEntity = true)
                }
            }

        val ranked = candidates.values
            .map { it.copy(score = score(it)) }
            .filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .take(limit)

        touchScope?.launch {
            ranked.forEach { scored ->
                runCatching { store.touch(userId, UUID.fromString(scored.memory.id)) }
                    .onFailure { log.debug("Could not mark memory {} as used: {}", scored.memory.id, it.message) }
            }
        }

        return RetrievalResult(
            memories = ranked,
            people = people,
            semanticUsed = semanticUsed,
            candidatesConsidered = candidates.size,
            latencyMillis = elapsed(startedAt),
            note = note,
        )
    }

    /**
     * Blended relevance. Semantic similarity dominates when available because it is the only
     * signal that understands paraphrase; the rest keep confident, important, recently useful
     * memories ahead of stale ones that merely share a word.
     */
    private fun score(candidate: ScoredMemory): Float {
        val semantic = candidate.semanticDistance?.let { (1f - it).coerceIn(0f, 1f) } ?: 0f
        val lexical = if (candidate.lexicalHit) 1f else 0f
        val linked = if (candidate.linkedEntity) 1f else 0f
        val importance = candidate.memory.importance
        val confidence = candidate.memory.confidence
        val recency = recencyScore(candidate.memory)
        return SEMANTIC_WEIGHT * semantic +
            LEXICAL_WEIGHT * lexical +
            LINKED_WEIGHT * linked +
            IMPORTANCE_WEIGHT * importance +
            CONFIDENCE_WEIGHT * confidence +
            RECENCY_WEIGHT * recency
    }

    /** 1.0 for something used or created today, decaying to 0 over about three months. */
    private fun recencyScore(memory: Memory): Float {
        val reference = memory.lastUsedAt ?: memory.createdAt
        val instant = runCatching { Instant.parse(reference) }.getOrNull() ?: return 0f
        val days = Duration.between(instant, Instant.now(clock)).toDays().coerceAtLeast(0)
        return (1f - days / RECENCY_HORIZON_DAYS.toFloat()).coerceIn(0f, 1f)
    }

    private fun Person.mentionedIn(text: String): Boolean =
        (listOf(name) + aliases).any { candidate ->
            candidate.isNotBlank() && Regex("\\b${Regex.escape(candidate)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

    private fun elapsed(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        val log = LoggerFactory.getLogger(MemoryRetrievalEngine::class.java)
        const val DEFAULT_LIMIT = 5
        const val SEMANTIC_OVERFETCH = 2
        const val MAX_LEXICAL_TERMS = 4
        const val RECENCY_HORIZON_DAYS = 90

        /**
         * Cosine distance beyond which a neighbour is not actually about the query. Cosine
         * distance runs 0 (identical) to 2 (opposite), so this keeps roughly the top quarter of
         * the similarity range. Tune once real embeddings and real questions exist.
         */
        const val MAX_SEMANTIC_DISTANCE = 0.75f

        /** Blended score below which a candidate is noise and is better left out entirely. */
        const val MIN_SCORE = 0.12f
        const val SEMANTIC_WEIGHT = 0.40f
        const val LEXICAL_WEIGHT = 0.20f
        const val LINKED_WEIGHT = 0.15f
        const val IMPORTANCE_WEIGHT = 0.15f
        const val CONFIDENCE_WEIGHT = 0.05f
        const val RECENCY_WEIGHT = 0.05f

        /** Words worth searching on: drops stopwords and one- or two-letter noise. */
        fun significantWords(query: String): List<String> =
            query.lowercase().split(Regex("[^\\p{L}\\p{N}']+"))
                .filter { it.length > 2 && it !in STOPWORDS }
                .distinct()

        val STOPWORDS = setOf(
            "the", "and", "for", "was", "were", "what", "who", "whom", "whose", "when", "where", "why", "how",
            "did", "does", "do", "you", "your", "our", "her", "his", "their", "they", "them", "this", "that",
            "these", "those", "with", "from", "about", "into", "over", "than", "then", "there", "here", "can",
            "could", "would", "should", "will", "shall", "may", "might", "must", "have", "has", "had", "been",
            "are", "not", "but", "any", "all", "get", "got", "say", "said", "tell", "told", "operator",
        )
    }
}
