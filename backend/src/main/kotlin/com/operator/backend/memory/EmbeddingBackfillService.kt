package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.min

@Serializable
data class EmbeddingBackfillResponse(
    val embedded: Int,
    val model: String,
    val hasMore: Boolean,
)

/** Adds embeddings to active, unexpired memories created before embeddings were configured. */
class EmbeddingBackfillService(
    private val store: MemoryStore,
    private val embeddings: EmbeddingProvider,
) {
    val available: Boolean
        get() = embeddings.available && !embeddings.modelId.isNullOrBlank()

    suspend fun backfill(
        userId: UUID = DEFAULT_USER_ID,
        limit: Int = DEFAULT_LIMIT,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): EmbeddingBackfillResponse {
        if (limit !in 1..MAX_LIMIT) throw MemoryValidationException("limit must be within 1..$MAX_LIMIT")
        if (batchSize !in 1..MAX_BATCH_SIZE) {
            throw MemoryValidationException("batchSize must be within 1..$MAX_BATCH_SIZE")
        }
        val model = embeddings.modelId?.takeIf { embeddings.available && it.isNotBlank() }
            ?: throw IllegalStateException("no embedding model is configured")

        var embedded = 0
        while (embedded < limit) {
            val requested = min(batchSize, limit - embedded)
            val memories = store.listWithoutEmbeddings(userId, requested)
            if (memories.isEmpty()) break

            val vectors = embeddings.embed(memories.map { it.content })
            if (vectors.size != memories.size) {
                throw AIProviderException("Embeddings returned ${vectors.size} vectors for ${memories.size} memories")
            }
            memories.zip(vectors).forEach { (memory, vector) ->
                store.putEmbedding(userId, UUID.fromString(memory.id), EmbeddingInput(model, vector))
            }
            embedded += memories.size
        }

        return EmbeddingBackfillResponse(
            embedded = embedded,
            model = model,
            hasMore = store.listWithoutEmbeddings(userId, 1).isNotEmpty(),
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 500
        const val DEFAULT_BATCH_SIZE = 50
        const val MAX_LIMIT = 5_000
        const val MAX_BATCH_SIZE = 100
    }
}
