package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.backend.ai.NoEmbeddingProvider
import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import com.operator.core.model.OperatorMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRetrievalEngineTest {

    private suspend fun seeded(): Pair<InMemoryMemoryStore, Person> {
        val store = InMemoryMemoryStore()
        val chris = store.createPerson(DEFAULT_USER_ID, NewPerson("Chris", listOf("Christopher")))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.PERSON, "Chris handles the west region for sales", importance = 0.7f, personId = chris.id))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.EPISODIC_EVENT, "The truck got stuck at the dock", importance = 0.3f))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.WORK_FACT, "The dashboard shows late orders first", importance = 0.8f, privacyScope = PrivacyScope.WORK))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.LONG_TERM_MEMORY, "The user owns four plasma cutters", importance = 0.6f))
        return store to chris
    }

    @Test
    fun `the brief's scenario - asking who handles the west retrieves the memory`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, FakeEmbeddings())
        val result = engine.retrieve("Who handles the west?")

        assertTrue(result.memories.isNotEmpty(), "expected at least one memory")
        assertEquals("Chris handles the west region for sales", result.memories.first().memory.content)
        assertTrue(result.semanticUsed)
        assertTrue(result.memories.size <= 5, "retrieval must stay small, got ${result.memories.size}")
        assertTrue(result.memories.first().why.isNotBlank(), "every hit explains itself")
    }

    @Test
    fun `works without embeddings, falling back to lexical and structured matching`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, NoEmbeddingProvider)
        val result = engine.retrieve("Who handles the west?")

        assertFalse(result.semanticUsed)
        assertEquals("Chris handles the west region for sales", result.memories.first().memory.content)
        assertTrue(result.note!!.contains("no embedding model"))
    }

    @Test
    fun `a failing embedding provider degrades instead of throwing`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, FakeEmbeddings(failWith = AIProviderException("embeddings down", retryable = true)))
        val result = engine.retrieve("Who handles the west?")

        assertFalse(result.semanticUsed)
        assertTrue(result.memories.isNotEmpty(), "lexical retrieval still answered")
        assertTrue(result.note!!.contains("semantic retrieval unavailable"))
    }

    @Test
    fun `naming a person pulls in what is linked to them`() = runTest {
        val (store, chris) = seeded()
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.WORK_FACT, "Quarterly numbers are due", importance = 0.5f, personId = chris.id))
        val engine = MemoryRetrievalEngine(store, NoEmbeddingProvider)
        val result = engine.retrieve("What should I ask Chris about?")

        assertEquals(listOf("Chris"), result.people.map { it.name })
        assertTrue(result.memories.any { it.memory.content == "Quarterly numbers are due" }, "linked memory should surface")
        assertTrue(result.memories.all { it.linkedEntity || it.lexicalHit })
    }

    @Test
    fun `work memories stay out of social mode and appear in work mode`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, NoEmbeddingProvider)

        val social = engine.retrieve("What does the dashboard show?", mode = OperatorMode.SOCIAL)
        assertTrue(social.memories.none { it.memory.privacyScope == PrivacyScope.WORK }, "work fact leaked into SOCIAL")

        val work = engine.retrieve("What does the dashboard show?", mode = OperatorMode.WORK)
        assertTrue(work.memories.any { it.memory.content == "The dashboard shows late orders first" })
    }

    @Test
    fun `OFF retrieves nothing and an empty query retrieves nothing`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, FakeEmbeddings())
        assertTrue(engine.retrieve("Who handles the west?", mode = OperatorMode.OFF).isEmpty)
        assertTrue(engine.retrieve("   ").isEmpty)
    }

    @Test
    fun `an unrelated question does not drag in irrelevant memories`() = runTest {
        val (store, _) = seeded()
        val engine = MemoryRetrievalEngine(store, NoEmbeddingProvider)
        val result = engine.retrieve("What is the capital of Portugal?")
        assertTrue(result.memories.isEmpty(), "expected nothing, got ${result.memories.map { it.memory.content }}")
    }

    @Test
    fun `a distant semantic neighbour is dropped rather than injected`() = runTest {
        val (store, _) = seeded()
        // Embeddings are on, so nearest-neighbour search will happily return the closest memory
        // to a question that has nothing to do with any of them. It must still come back empty.
        val engine = MemoryRetrievalEngine(store, FakeEmbeddings())
        val result = engine.retrieve("What is the capital of Portugal?")
        assertTrue(result.semanticUsed, "semantic search did run")
        assertTrue(result.memories.isEmpty(), "expected nothing, got ${result.memories.map { it.memory.content }}")
    }

    @Test
    fun `importance and confidence break ties between equally worded hits`() = runTest {
        val store = InMemoryMemoryStore()
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.WORK_FACT, "The quote is ready", importance = 0.2f, confidence = 0.3f))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.COMMITMENT, "The quote is due Friday", importance = 0.9f, confidence = 0.95f))
        val engine = MemoryRetrievalEngine(store, NoEmbeddingProvider)
        val result = engine.retrieve("What about the quote?")
        assertEquals("The quote is due Friday", result.memories.first().memory.content)
    }
}
