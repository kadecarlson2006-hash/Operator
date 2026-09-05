package com.operator.backend.ai

import com.operator.backend.memory.DEFAULT_USER_ID
import com.operator.backend.memory.InMemoryMemoryStore
import com.operator.backend.memory.MemoryRetrievalEngine
import com.operator.backend.memory.NewMemory
import com.operator.backend.memory.NewPerson
import com.operator.backend.memory.RetrievalResult
import com.operator.backend.memory.RetrievalTrigger
import com.operator.core.memory.MemoryType
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextAssemblerTest {

    @Test
    fun `state, memories and people are rendered and labelled as memory`() = runTest {
        val store = InMemoryMemoryStore()
        val chris = store.createPerson(DEFAULT_USER_ID, NewPerson("Chris", relationship = "colleague", role = "west region"))
        store.create(DEFAULT_USER_ID, NewMemory(MemoryType.PERSON, "Chris handles the west", importance = 0.8f, personId = chris.id))
        val retrieval = MemoryRetrievalEngine(store, NoEmbeddingProvider).retrieve("What does Chris handle?")

        val block = ContextAssembler.build(OperatorMode.WORK, WitLevel.DRY, retrieval, RetrievalTrigger.DIRECT_REQUEST)

        assertTrue(block.contains("Mode: WORK"))
        assertTrue(block.contains("Wit: DRY"))
        assertTrue(block.contains("Trigger: DIRECT_REQUEST"))
        assertTrue(block.contains("Chris handles the west"))
        assertTrue(block.contains("NOT live data"), "memories must be labelled as memory, not lookups")
        assertTrue(block.contains("Chris (colleague, west region)"))
    }

    @Test
    fun `empty sections are omitted rather than rendered blank`() {
        val block = ContextAssembler.build(OperatorMode.ACTIVE, WitLevel.NORMAL, null, RetrievalTrigger.AMBIENT)
        assertFalse(block.contains("Relevant memories"))
        assertFalse(block.contains("Relevant people"))
        assertFalse(block.contains("Rolling conversation"))
        assertFalse(block.contains("Available tools"))
        assertTrue(block.contains("No stored memories are relevant. Do not invent any."))
    }

    @Test
    fun `transcript, recent comments and tools appear only when supplied`() {
        val block = ContextAssembler.build(
            OperatorMode.ACTIVE, WitLevel.SHARP,
            RetrievalResult(emptyList(), emptyList(), false, 0, 1),
            RetrievalTrigger.COMMENT_NOW,
            rollingTranscript = "A: who was that actor",
            recentOperatorComments = listOf("A bold strategy, sir."),
            availableTools = listOf("memory.search"),
        )
        assertTrue(block.contains("Rolling conversation:"))
        assertTrue(block.contains("A: who was that actor"))
        assertTrue(block.contains("do not repeat yourself"))
        assertTrue(block.contains("Available tools: memory.search"))
    }
}
