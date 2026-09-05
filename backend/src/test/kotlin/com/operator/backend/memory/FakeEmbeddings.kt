package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.EmbeddingProvider

/**
 * Deterministic bag-of-words vectors over a fixed vocabulary, so semantic retrieval can be tested
 * without a network call: texts sharing words end up near each other.
 */
class FakeEmbeddings(
    override val modelId: String? = "fake/embed-1",
    override val available: Boolean = true,
    private val failWith: AIProviderException? = null,
) : EmbeddingProvider {
    var calls = 0

    override suspend fun embed(texts: List<String>): List<List<Float>> {
        calls++
        failWith?.let { throw it }
        return texts.map { text ->
            val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
            VOCAB.map { if (it in words) 1f else 0f }
        }
    }

    private companion object {
        val VOCAB = listOf("west", "chris", "region", "sales", "dashboard", "orders", "truck", "dock", "quote", "friday", "plasma", "cutter")
    }
}
