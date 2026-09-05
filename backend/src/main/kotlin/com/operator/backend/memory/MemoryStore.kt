package com.operator.backend.memory

import java.util.UUID

/**
 * Memory persistence contract (Milestone 5). Implemented by [PostgresMemoryStore] for real and
 * [InMemoryMemoryStore] for tests and SDK-free development. All operations are scoped to a user.
 *
 * Duplicate policy: [create] refuses a second *active* memory with the same type and normalised
 * content ([DuplicateMemoryException] carries the existing id). Milestone 7's write engine will
 * add semantic duplicate detection on top.
 */
interface MemoryStore {
    val backendName: String

    suspend fun create(userId: UUID, memory: NewMemory): Memory
    suspend fun get(userId: UUID, id: UUID): Memory
    suspend fun search(userId: UUID, query: MemorySearch): List<Memory>
    suspend fun update(userId: UUID, id: UUID, update: MemoryUpdate): Memory
    /** Hard delete. Leaves a DELETED event behind. */
    suspend fun delete(userId: UUID, id: UUID)
    /** Confidence → 0, inactive, MARKED_INCORRECT event. The record stays for inspection. */
    suspend fun markIncorrect(userId: UUID, id: UUID, reason: String?): Memory
    /** Bumps last_used_at and logs a USED event (called by retrieval in Milestone 7). */
    suspend fun touch(userId: UUID, id: UUID): Memory
    suspend fun events(userId: UUID, id: UUID): List<MemoryEvent>

    suspend fun putEmbedding(userId: UUID, id: UUID, embedding: EmbeddingInput): Memory
    suspend fun searchSimilar(userId: UUID, query: SimilaritySearch): List<Memory>

    suspend fun createPerson(userId: UUID, person: NewPerson): Person
    suspend fun listPeople(userId: UUID, includeInactive: Boolean = false): List<Person>
    suspend fun createProject(userId: UUID, project: NewProject): Project
    suspend fun listProjects(userId: UUID, includeInactive: Boolean = false): List<Project>
    suspend fun createOrganization(userId: UUID, organization: NewOrganization): Organization
    suspend fun listOrganizations(userId: UUID, includeInactive: Boolean = false): List<Organization>

    suspend fun count(userId: UUID, includeInactive: Boolean = false): Long
}
