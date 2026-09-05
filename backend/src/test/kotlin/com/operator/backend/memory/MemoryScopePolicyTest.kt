package com.operator.backend.memory

import com.operator.core.memory.PrivacyScope
import com.operator.core.model.OperatorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryScopePolicyTest {

    @Test
    fun `only work mode reads work memories`() {
        assertTrue(PrivacyScope.WORK in MemoryScopePolicy.allowedScopes(OperatorMode.WORK, RetrievalTrigger.DIRECT_REQUEST))
        listOf(OperatorMode.ACTIVE, OperatorMode.SOCIAL, OperatorMode.CHAOS, OperatorMode.QUIET, OperatorMode.STANDBY).forEach { mode ->
            assertFalse(PrivacyScope.WORK in MemoryScopePolicy.allowedScopes(mode, RetrievalTrigger.DIRECT_REQUEST), "$mode must not read work memories")
        }
    }

    @Test
    fun `private memories answer a direct question but are never volunteered`() {
        assertTrue(PrivacyScope.PRIVATE in MemoryScopePolicy.allowedScopes(OperatorMode.ACTIVE, RetrievalTrigger.DIRECT_REQUEST))
        assertFalse(PrivacyScope.PRIVATE in MemoryScopePolicy.allowedScopes(OperatorMode.ACTIVE, RetrievalTrigger.AMBIENT))
        assertFalse(PrivacyScope.PRIVATE in MemoryScopePolicy.allowedScopes(OperatorMode.CHAOS, RetrievalTrigger.COMMENT_NOW))
    }

    @Test
    fun `restricted is never retrieved and OFF reads nothing`() {
        OperatorMode.entries.forEach { mode ->
            RetrievalTrigger.entries.forEach { trigger ->
                assertFalse(PrivacyScope.RESTRICTED in MemoryScopePolicy.allowedScopes(mode, trigger), "$mode/$trigger leaked RESTRICTED")
            }
        }
        assertTrue(MemoryScopePolicy.allowedScopes(OperatorMode.OFF, RetrievalTrigger.DIRECT_REQUEST).isEmpty())
    }

    @Test
    fun `writes default to the scope of the mode they were spoken in`() {
        assertEquals(PrivacyScope.WORK, MemoryScopePolicy.defaultWriteScope(OperatorMode.WORK))
        assertEquals(PrivacyScope.PERSONAL, MemoryScopePolicy.defaultWriteScope(OperatorMode.SOCIAL))
        assertFalse(MemoryScopePolicy.mayWrite(OperatorMode.OFF, PrivacyScope.PERSONAL))
        assertFalse(MemoryScopePolicy.mayWrite(OperatorMode.WORK, PrivacyScope.RESTRICTED))
    }
}
