package com.operator.backend.memory

import com.operator.backend.ai.AIProviderException
import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import com.operator.core.model.OperatorMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryWriteEngineTest {

    private fun engine(store: MemoryStore = InMemoryMemoryStore(), embeddings: FakeEmbeddings = FakeEmbeddings()) =
        MemoryWriteEngine(store, embeddings) to store

    @Test
    fun `recognises the phrasings from the brief and extracts the fact`() {
        val (e, _) = engine()
        val cases = mapOf(
            "Remember that Chris handles the west." to "Chris handles the west",
            "Operator, remember that Chris handles the west" to "Chris handles the west",
            "remember this: the quote is due Friday" to "The quote is due Friday",
            "Make a note that Dana prefers email." to "Dana prefers email",
            "Don't let me forget the truck is in the shop" to "The truck is in the shop",
            "Store this: four plasma cutters" to "Four plasma cutters",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, e.detect(input)?.content, "input: $input") }
    }

    @Test
    fun `ordinary questions are not memory commands`() {
        val (e, _) = engine()
        listOf("Who handles the west?", "What did Chris say?", "Do you remember the truck?", "", "   ", "remember").forEach {
            assertNull(e.detect(it), "should not be a command: '$it'")
        }
    }

    @Test
    fun `an explicit memory is stored with high confidence, embedded, and confirmed briefly`() = runTest {
        val (e, store) = engine()
        val command = e.detect("Remember that Chris handles the west.")!!
        val result = e.write(command)

        assertEquals("Chris handles the west", result.memory.content)
        assertEquals(SourceType.EXPLICIT_USER, result.memory.sourceType)
        assertEquals(0.95f, result.memory.confidence)
        assertTrue(result.memory.importance >= 0.8f)
        assertEquals(PrivacyScope.PERSONAL, result.memory.privacyScope)
        assertTrue(result.embedded)
        assertFalse(result.updatedExisting)
        assertTrue(result.confirmation.length < 30, "confirmations stay short: '${result.confirmation}'")
        assertEquals(1, store.count(DEFAULT_USER_ID))
        assertTrue(store.get(DEFAULT_USER_ID, java.util.UUID.fromString(result.memory.id)).hasEmbedding)
    }

    @Test
    fun `saying it again reaffirms instead of failing or duplicating`() = runTest {
        val (e, store) = engine()
        val first = e.write(e.detect("Remember that Chris handles the west")!!)
        store.update(DEFAULT_USER_ID, java.util.UUID.fromString(first.memory.id), MemoryUpdate(confidence = 0.2f))

        val second = e.write(e.detect("remember that chris handles the west.")!!)
        assertTrue(second.updatedExisting)
        assertEquals(first.memory.id, second.memory.id)
        assertEquals(0.95f, second.memory.confidence, "reaffirmation restores confidence")
        assertEquals(1, store.count(DEFAULT_USER_ID), "no duplicate row")
        assertEquals("Already noted, sir.", second.confirmation)
    }

    @Test
    fun `links to a known person and picks a plausible type`() = runTest {
        val store = InMemoryMemoryStore()
        val chris = store.createPerson(DEFAULT_USER_ID, NewPerson("Chris", listOf("Christopher")))
        val e = MemoryWriteEngine(store, FakeEmbeddings())

        val linked = e.write(e.detect("Remember that Christopher handles the west")!!)
        assertEquals(chris.id, linked.memory.personId)
        assertEquals(MemoryType.PERSON, linked.memory.memoryType)

        assertEquals(MemoryType.COMMITMENT, e.write(e.detect("Remember that I promised Dana a quote by Friday")!!).memory.memoryType)
        assertEquals(MemoryType.DECISION, e.write(e.detect("Remember that we decided against the second press")!!).memory.memoryType)
        assertEquals(MemoryType.LONG_TERM_MEMORY, e.write(e.detect("Remember that the shop closes at six")!!).memory.memoryType)
    }

    @Test
    fun `work mode stores into the work scope`() = runTest {
        val (e, _) = engine()
        val result = e.write(e.detect("Remember that the line runs Tuesdays")!!, mode = OperatorMode.WORK)
        assertEquals(PrivacyScope.WORK, result.memory.privacyScope)
    }

    @Test
    fun `a failing embedding provider never loses the memory`() = runTest {
        val store = InMemoryMemoryStore()
        val e = MemoryWriteEngine(store, FakeEmbeddings(failWith = AIProviderException("embeddings down", retryable = true)))
        val result = e.write(e.detect("Remember that the truck is in the shop")!!)
        assertFalse(result.embedded)
        assertNotNull(store.get(DEFAULT_USER_ID, java.util.UUID.fromString(result.memory.id)))
        assertEquals(1, store.count(DEFAULT_USER_ID))
    }
}
