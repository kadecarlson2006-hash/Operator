package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import java.util.UUID

/**
 * Fake/demo memories for development and for exercising retrieval (brief, Milestone 5).
 * Idempotent: keyed by `sourceReference = demo:<key>`, so seeding twice adds nothing.
 * All content is fictional.
 */
object DemoMemories {
    data class SeedResult(val created: Int, val skipped: Int, val people: Int, val projects: Int, val organizations: Int)

    suspend fun seed(store: MemoryStore, userId: UUID = DEFAULT_USER_ID): SeedResult {
        val existingRefs = store.search(userId, MemorySearch(includeInactive = true, includeExpired = true, limit = 200))
            .mapNotNull { it.sourceReference }.filter { it.startsWith("demo:") }.toSet()

        val orgs = store.listOrganizations(userId, includeInactive = true).associateBy { it.name }
        val westfield = orgs["Westfield Fabrication"] ?: store.createOrganization(userId, NewOrganization("Westfield Fabrication", listOf("Westfield"), "Fictional customer for demos"))
        val orgCreated = if (orgs.containsKey("Westfield Fabrication")) 0 else 1

        val people = store.listPeople(userId, includeInactive = true).associateBy { it.name }
        var peopleCreated = 0
        fun personOrNull(name: String) = people[name]
        val chris = personOrNull("Chris") ?: store.createPerson(userId, NewPerson("Chris", listOf("Christopher"), "colleague", null, "Regional sales, west", "Demo person")).also { peopleCreated++ }
        val colten = personOrNull("Colten") ?: store.createPerson(userId, NewPerson("Colten", emptyList(), "colleague", null, "Operations lead", "Demo person")).also { peopleCreated++ }
        val dana = personOrNull("Dana") ?: store.createPerson(userId, NewPerson("Dana", emptyList(), "customer contact", westfield.id, "Purchasing manager", "Demo person")).also { peopleCreated++ }

        val projects = store.listProjects(userId, includeInactive = true).associateBy { it.name }
        var projectsCreated = 0
        val dashboard = projects["Ops Dashboard"] ?: store.createProject(userId, NewProject("Ops Dashboard", "Internal operations dashboard (demo)")).also { projectsCreated++ }

        data class Seed(val key: String, val type: MemoryType, val content: String, val scope: PrivacyScope, val importance: Float, val confidence: Float, val personId: String? = null, val projectId: String? = null, val organizationId: String? = null)
        val seeds = listOf(
            Seed("profile-1", MemoryType.PERSONAL_PROFILE, "The user prefers to be addressed as 'sir' by Operator.", PrivacyScope.PERSONAL, 0.6f, 0.95f),
            Seed("pref-1", MemoryType.PREFERENCE, "The user dislikes long meetings and prefers decisions in writing.", PrivacyScope.PERSONAL, 0.5f, 0.8f),
            Seed("person-chris", MemoryType.PERSON, "Chris handles the west region for sales.", PrivacyScope.WORK, 0.7f, 0.9f, personId = chris.id),
            Seed("person-colten", MemoryType.PERSON, "Colten leads operations and owns the Ops Dashboard requirements.", PrivacyScope.WORK, 0.7f, 0.9f, personId = colten.id, projectId = dashboard.id),
            Seed("work-1", MemoryType.WORK_FACT, "Colten wants the Ops Dashboard to show late orders first, sorted by promised date.", PrivacyScope.WORK, 0.8f, 0.85f, personId = colten.id, projectId = dashboard.id),
            Seed("work-2", MemoryType.WORK_FACT, "Westfield Fabrication has complained about long lead times twice this year.", PrivacyScope.WORK, 0.7f, 0.8f, organizationId = westfield.id, personId = dana.id),
            Seed("commit-1", MemoryType.COMMITMENT, "The user promised Dana at Westfield a revised quote by Friday.", PrivacyScope.WORK, 0.9f, 0.9f, personId = dana.id, organizationId = westfield.id),
            Seed("decision-1", MemoryType.DECISION, "Decided not to buy more equipment this month; revisit after the quarter closes.", PrivacyScope.PERSONAL, 0.8f, 0.9f),
            Seed("goal-1", MemoryType.GOAL, "Goal: ship the first Ops Dashboard version before the end of the quarter.", PrivacyScope.WORK, 0.8f, 0.85f, projectId = dashboard.id),
            Seed("episodic-1", MemoryType.EPISODIC_EVENT, "The user told the story about the truck that got stuck at the Westfield loading dock.", PrivacyScope.PERSONAL, 0.4f, 0.7f, organizationId = westfield.id),
            Seed("episodic-2", MemoryType.EPISODIC_EVENT, "The user called the 'once-in-a-lifetime' equipment deal 'probably stupid'.", PrivacyScope.PERSONAL, 0.5f, 0.75f),
            Seed("longterm-1", MemoryType.LONG_TERM_MEMORY, "The user already owns a plasma cutter; in fact, four of them.", PrivacyScope.PERSONAL, 0.6f, 0.9f),
        )
        var created = 0; var skipped = 0
        for (s in seeds) {
            val ref = "demo:${s.key}"
            if (ref in existingRefs) { skipped++; continue }
            try {
                store.create(userId, NewMemory(
                    memoryType = s.type, content = s.content, sourceType = SourceType.DEMO, sourceReference = ref,
                    importance = s.importance, confidence = s.confidence, personId = s.personId, projectId = s.projectId,
                    organizationId = s.organizationId, privacyScope = s.scope, metadata = mapOf("demo" to "true"),
                ))
                created++
            } catch (e: DuplicateMemoryException) {
                skipped++
            }
        }
        return SeedResult(created, skipped, peopleCreated, projectsCreated, orgCreated)
    }
}
