package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.NoEmbeddingProvider
import com.operator.core.memory.MemoryType
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddingBackfillServiceTest {
    @Test
    fun `backfills only eligible missing embeddings in bounded batches`() = runTest {
        val store = InMemoryMemoryStore()
        val alreadyEmbedded = store.create(DEFAULT_USER_ID, memory("already embedded"))
        val firstMissing = store.create(DEFAULT_USER_ID, memory("first missing"))
        val secondMissing = store.create(DEFAULT_USER_ID, memory("second missing"))
        val inactive = store.create(DEFAULT_USER_ID, memory("inactive"))
        store.create(
            DEFAULT_USER_ID,
            memory("expired", expiresAt = "2000-01-01T00:00:00Z"),
        )
        store.putEmbedding(
            DEFAULT_USER_ID,
            UUID.fromString(alreadyEmbedded.id),
            EmbeddingInput("fake/embed-1", listOf(1f, 0f)),
        )
        store.update(DEFAULT_USER_ID, UUID.fromString(inactive.id), MemoryUpdate(isActive = false))
        val embeddings = FakeEmbeddings()
        val service = EmbeddingBackfillService(store, embeddings)

        val firstRun = service.backfill(limit = 1, batchSize = 1)
        assertEquals(1, firstRun.embedded)
        assertTrue(firstRun.hasMore)
        assertEquals(1, embeddings.calls)
        assertTrue(store.get(DEFAULT_USER_ID, UUID.fromString(firstMissing.id)).hasEmbedding)
        assertFalse(store.get(DEFAULT_USER_ID, UUID.fromString(secondMissing.id)).hasEmbedding)

        val secondRun = service.backfill(limit = 10, batchSize = 2)
        assertEquals(1, secondRun.embedded)
        assertFalse(secondRun.hasMore)
        assertEquals("fake/embed-1", secondRun.model)
        assertEquals(2, embeddings.calls)
        assertFalse(store.get(DEFAULT_USER_ID, UUID.fromString(inactive.id)).hasEmbedding)
    }

    @Test
    fun `unavailable and failed providers do not alter memories`() = runTest {
        val store = InMemoryMemoryStore()
        val memory = store.create(DEFAULT_USER_ID, memory("needs a vector"))

        val unavailable = EmbeddingBackfillService(store, NoEmbeddingProvider)
        assertFalse(unavailable.available)
        assertFailsWith<IllegalStateException> { unavailable.backfill() }

        val failed = EmbeddingBackfillService(
            store,
            FakeEmbeddings(failWith = AIProviderException("embedding service down", retryable = true)),
        )
        assertFailsWith<AIProviderException> { failed.backfill() }
        assertFalse(store.get(DEFAULT_USER_ID, UUID.fromString(memory.id)).hasEmbedding)
    }

    @Test
    fun `rejects unsafe limits before calling the provider`() = runTest {
        val embeddings = FakeEmbeddings()
        val service = EmbeddingBackfillService(InMemoryMemoryStore(), embeddings)

        assertFailsWith<MemoryValidationException> { service.backfill(limit = 0) }
        assertFailsWith<MemoryValidationException> { service.backfill(batchSize = 101) }
        assertEquals(0, embeddings.calls)
    }

    private fun memory(content: String, expiresAt: String? = null) = NewMemory(
        memoryType = MemoryType.LONG_TERM_MEMORY,
        content = content,
        expiresAt = expiresAt,
    )
}
