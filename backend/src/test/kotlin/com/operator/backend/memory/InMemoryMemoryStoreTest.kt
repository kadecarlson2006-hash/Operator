package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryMemoryStoreTest {
    private val user = DEFAULT_USER_ID

    @Test
    fun `create get update delete lifecycle with events`() = runTest {
        val store = InMemoryMemoryStore()
        val m = store.create(user, NewMemory(MemoryType.WORK_FACT, "  Chris handles the west.  ", importance = 0.7f, privacyScope = PrivacyScope.WORK))
        assertEquals("Chris handles the west.", m.content)
        assertTrue(m.isActive)
        assertEquals(m, store.get(user, java.util.UUID.fromString(m.id)))

        val updated = store.update(user, java.util.UUID.fromString(m.id), MemoryUpdate(importance = 0.9f, metadata = mapOf("k" to "v")))
        assertEquals(0.9f, updated.importance)
        assertEquals("v", updated.metadata["k"])

        val disabled = store.update(user, java.util.UUID.fromString(m.id), MemoryUpdate(isActive = false))
        assertFalse(disabled.isActive)
        assertTrue(store.search(user, MemorySearch()).isEmpty(), "inactive memories are hidden by default")
        assertEquals(1, store.search(user, MemorySearch(includeInactive = true)).size)

        store.delete(user, java.util.UUID.fromString(m.id))
        assertFailsWith<MemoryNotFoundException> { store.get(user, java.util.UUID.fromString(m.id)) }
        assertEquals(listOf(MemoryEventType.CREATED, MemoryEventType.UPDATED, MemoryEventType.DISABLED, MemoryEventType.DELETED), store.events(user, java.util.UUID.fromString(m.id)).map { it.eventType })
    }

    @Test
    fun `active duplicates are refused but an inactive twin is allowed`() = runTest {
        val store = InMemoryMemoryStore()
        val a = store.create(user, NewMemory(MemoryType.PREFERENCE, "Prefers decisions in writing"))
        val dup = assertFailsWith<DuplicateMemoryException> { store.create(user, NewMemory(MemoryType.PREFERENCE, "prefers  decisions in WRITING ")) }
        assertEquals(a.id, dup.existingId)
        store.markIncorrect(user, java.util.UUID.fromString(a.id), "was wrong")
        val b = store.create(user, NewMemory(MemoryType.PREFERENCE, "Prefers decisions in writing"))
        assertTrue(b.id != a.id)
        val old = store.get(user, java.util.UUID.fromString(a.id))
        assertEquals(0f, old.confidence)
        assertFalse(old.isActive)
        assertEquals("was wrong", store.events(user, java.util.UUID.fromString(a.id)).last().details["reason"])
    }

    @Test
    fun `search filters by text type scope person and expiry and orders by importance`() = runTest {
        val store = InMemoryMemoryStore()
        val chris = store.createPerson(user, NewPerson("Chris"))
        store.create(user, NewMemory(MemoryType.PERSON, "Chris handles the west", importance = 0.7f, personId = chris.id, privacyScope = PrivacyScope.WORK))
        store.create(user, NewMemory(MemoryType.COMMITMENT, "Send Dana the quote by Friday", importance = 0.9f, privacyScope = PrivacyScope.WORK))
        store.create(user, NewMemory(MemoryType.PREFERENCE, "Likes the west coast", importance = 0.3f, privacyScope = PrivacyScope.PERSONAL))
        store.create(user, NewMemory(MemoryType.SESSION_MEMORY, "Expired session note", expiresAt = "2000-01-01T00:00:00Z"))

        assertEquals(listOf(0.9f, 0.7f, 0.3f), store.search(user, MemorySearch()).map { it.importance })
        assertEquals(2, store.search(user, MemorySearch(text = "west")).size)
        assertEquals(1, store.search(user, MemorySearch(memoryType = MemoryType.PERSON)).size)
        assertEquals(2, store.search(user, MemorySearch(privacyScopes = setOf(PrivacyScope.WORK))).size)
        assertEquals(1, store.search(user, MemorySearch(personId = chris.id)).size)
        assertEquals(4, store.search(user, MemorySearch(includeExpired = true)).size)
        assertEquals(1, store.search(user, MemorySearch(limit = 1)).size)
        assertFailsWith<MemoryValidationException> { store.search(user, MemorySearch(limit = 0)) }
    }

    @Test
    fun `validation and missing links are rejected`() = runTest {
        val store = InMemoryMemoryStore()
        assertFailsWith<MemoryValidationException> { store.create(user, NewMemory(MemoryType.GOAL, "   ")) }
        assertFailsWith<MemoryValidationException> { store.create(user, NewMemory(MemoryType.GOAL, "x", importance = 1.5f)) }
        assertFailsWith<MemoryValidationException> { store.create(user, NewMemory(MemoryType.GOAL, "x", personId = "not-a-uuid")) }
        assertFailsWith<EntityNotFoundException> { store.create(user, NewMemory(MemoryType.GOAL, "x", personId = java.util.UUID.randomUUID().toString())) }
        val m = store.create(user, NewMemory(MemoryType.GOAL, "ship it"))
        assertFailsWith<MemoryValidationException> { store.update(user, java.util.UUID.fromString(m.id), MemoryUpdate()) }
    }

    @Test
    fun `embeddings enable similarity search ordered by cosine distance`() = runTest {
        val store = InMemoryMemoryStore()
        val a = store.create(user, NewMemory(MemoryType.WORK_FACT, "dashboard shows late orders first"))
        val b = store.create(user, NewMemory(MemoryType.WORK_FACT, "the truck got stuck at the dock"))
        val c = store.create(user, NewMemory(MemoryType.WORK_FACT, "no embedding here"))
        store.putEmbedding(user, java.util.UUID.fromString(a.id), EmbeddingInput("fake", listOf(1f, 0f, 0f)))
        store.putEmbedding(user, java.util.UUID.fromString(b.id), EmbeddingInput("fake", listOf(0f, 1f, 0f)))
        assertTrue(store.get(user, java.util.UUID.fromString(a.id)).hasEmbedding)
        assertFalse(store.get(user, java.util.UUID.fromString(c.id)).hasEmbedding)

        val hits = store.searchSimilar(user, SimilaritySearch(vector = listOf(0.9f, 0.1f, 0f), limit = 5))
        assertEquals(listOf(a.id, b.id), hits.map { it.id })
        assertNotNull(hits[0].distance)
        assertTrue(hits[0].distance!! < hits[1].distance!!)
        assertTrue(store.searchSimilar(user, SimilaritySearch(vector = listOf(1f, 0f), limit = 5)).isEmpty(), "dimension mismatch yields nothing")
        assertTrue(store.searchSimilar(user, SimilaritySearch(vector = listOf(1f, 0f, 0f), model = "other")).isEmpty())
        assertNull(store.get(user, java.util.UUID.fromString(a.id)).distance)
    }

    @Test
    fun `touch records usage`() = runTest {
        val store = InMemoryMemoryStore()
        val m = store.create(user, NewMemory(MemoryType.GOAL, "ship it"))
        assertNull(m.lastUsedAt)
        val touched = store.touch(user, java.util.UUID.fromString(m.id))
        assertNotNull(touched.lastUsedAt)
        assertEquals(MemoryEventType.USED, store.events(user, java.util.UUID.fromString(m.id)).last().eventType)
    }

    @Test
    fun `conversation session CRUD is validated and user scoped`() = runTest {
        val store = InMemoryMemoryStore()
        val created = store.createSession(
            user,
            NewConversationSession(operatorMode = "WORK", witLevel = "DRY", promptVersion = "operator-v1"),
        )
        assertEquals(created, store.getSession(user, java.util.UUID.fromString(created.id)))
        assertEquals(listOf(created.id), store.listSessions(user).map { it.id })
        assertTrue(store.listSessions(java.util.UUID.randomUUID()).isEmpty())

        val ended = store.updateSession(
            user,
            java.util.UUID.fromString(created.id),
            ConversationSessionUpdate(endedAt = "2999-01-01T00:00:00Z", summary = "Finished the task"),
        )
        assertEquals("Finished the task", ended.summary)
        assertEquals("2999-01-01T00:00:00Z", ended.endedAt)
        val reopened = store.updateSession(
            user,
            java.util.UUID.fromString(created.id),
            ConversationSessionUpdate(clearEndedAt = true, clearSummary = true),
        )
        assertNull(reopened.endedAt)
        assertNull(reopened.summary)

        assertFailsWith<MemoryValidationException> {
            store.updateSession(user, java.util.UUID.fromString(created.id), ConversationSessionUpdate())
        }
        assertFailsWith<MemoryValidationException> {
            store.updateSession(
                user,
                java.util.UUID.fromString(created.id),
                ConversationSessionUpdate(endedAt = "2000-01-01T00:00:00Z"),
            )
        }
        store.deleteSession(user, java.util.UUID.fromString(created.id))
        assertFailsWith<EntityNotFoundException> { store.getSession(user, java.util.UUID.fromString(created.id)) }
    }
}
