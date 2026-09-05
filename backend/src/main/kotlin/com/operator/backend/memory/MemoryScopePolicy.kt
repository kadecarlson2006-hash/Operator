package com.operator.backend.memory

import com.operator.core.memory.PrivacyScope
import com.operator.core.model.OperatorMode

/** Why a retrieval is happening. Ambient retrieval is held to a stricter standard than a question. */
enum class RetrievalTrigger { DIRECT_REQUEST, AMBIENT, COMMENT_NOW }

/**
 * Which privacy scopes a retrieval may read (ADR-026).
 *
 * Two rules drive this. Work facts should not surface in social settings, so only WORK mode reads
 * the WORK scope. And anything the user marked PRIVATE is answered only when they actually asked;
 * it is never volunteered from ambient conversation. RESTRICTED is never retrieved automatically
 * at all and is reserved for an explicit unlock that does not exist yet.
 */
object MemoryScopePolicy {

    fun allowedScopes(mode: OperatorMode, trigger: RetrievalTrigger): Set<PrivacyScope> {
        if (mode == OperatorMode.OFF) return emptySet()
        val base = mutableSetOf(PrivacyScope.PERSONAL, PrivacyScope.SESSION)
        if (mode == OperatorMode.WORK) base += PrivacyScope.WORK
        if (trigger == RetrievalTrigger.DIRECT_REQUEST) base += PrivacyScope.PRIVATE
        return base
    }

    /** True when a memory may be written with this scope in this mode. Writing is stricter than reading. */
    fun mayWrite(mode: OperatorMode, scope: PrivacyScope): Boolean = when {
        mode == OperatorMode.OFF -> false
        scope == PrivacyScope.RESTRICTED -> false
        else -> true
    }

    /** The scope an explicit "remember that…" defaults to, given the mode it was spoken in. */
    fun defaultWriteScope(mode: OperatorMode): PrivacyScope =
        if (mode == OperatorMode.WORK) PrivacyScope.WORK else PrivacyScope.PERSONAL
}
