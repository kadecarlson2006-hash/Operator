package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoMemoriesTest {
    @Test
    fun `seeding is idempotent and links people and projects`() = runTest {
        val store = InMemoryMemoryStore()
        val first = DemoMemories.seed(store)
        assertEquals(12, first.created)
        assertEquals(0, first.skipped)
        assertEquals(3, first.people)
        assertEquals(1, first.projects)
        assertEquals(1, first.organizations)

        val second = DemoMemories.seed(store)
        assertEquals(0, second.created)
        assertEquals(12, second.skipped)
        assertEquals(0, second.people)
        assertEquals(12, store.count(DEFAULT_USER_ID))

        val chris = store.listPeople(DEFAULT_USER_ID).first { it.name == "Chris" }
        val aboutChris = store.search(DEFAULT_USER_ID, MemorySearch(personId = chris.id))
        assertEquals(1, aboutChris.size)
        assertTrue(aboutChris[0].content.contains("west"))
        assertEquals(MemoryType.PERSON, aboutChris[0].memoryType)

        val west = store.search(DEFAULT_USER_ID, MemorySearch(text = "handles the west"))
        assertEquals("Chris handles the west region for sales.", west.single().content)
    }
}
