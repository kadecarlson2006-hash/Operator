package com.operator.backend.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

/**
 * Reference implementation with the same semantics as the SQL store. Used by unit tests and by
 * the backend when no DATABASE_URL is configured (health reports it as ephemeral).
 */
class InMemoryMemoryStore(private val clock: Clock = Clock.systemUTC()) : MemoryStore {
    override val backendName = "in-memory (ephemeral)"

    private val lock = Mutex()
    private val memories = LinkedHashMap<UUID, Memory>()
    private val embeddings = HashMap<UUID, Pair<String, List<Float>>>()
    private val events = ArrayList<MemoryEvent>()
    private val people = LinkedHashMap<UUID, Person>()
    private val projects = LinkedHashMap<UUID, Project>()
    private val organizations = LinkedHashMap<UUID, Organization>()
    private var eventSeq = 0L

    private fun now(): String = Instant.now(clock).toString()

    override suspend fun create(userId: UUID, memory: NewMemory): Memory = lock.withLock {
        memory.validate()
        memory.personId?.let { if (!people.containsKey(UUID.fromString(it))) throw EntityNotFoundException("person", it) }
        memory.projectId?.let { if (!projects.containsKey(UUID.fromString(it))) throw EntityNotFoundException("project", it) }
        memory.organizationId?.let { if (!organizations.containsKey(UUID.fromString(it))) throw EntityNotFoundException("organization", it) }
        val key = contentKey(memory.content)
        memories.values.firstOrNull { it.userId == userId.toString() && it.isActive && it.memoryType == memory.memoryType && contentKey(it.content) == key }
            ?.let { throw DuplicateMemoryException(it.id) }
        val id = UUID.randomUUID()
        val ts = now()
        val stored = Memory(
            id = id.toString(), userId = userId.toString(), memoryType = memory.memoryType, content = memory.content.trim(),
            sourceType = memory.sourceType, sourceReference = memory.sourceReference, createdAt = ts, updatedAt = ts,
            importance = memory.importance, confidence = memory.confidence, expiresAt = memory.expiresAt,
            personId = memory.personId, organizationId = memory.organizationId, projectId = memory.projectId,
            privacyScope = memory.privacyScope, isActive = true, metadata = memory.metadata,
        )
        memories[id] = stored
        log(id, MemoryEventType.CREATED, mapOf("sourceType" to memory.sourceType.name))
        stored
    }

    override suspend fun get(userId: UUID, id: UUID): Memory = lock.withLock { find(userId, id) }

    override suspend fun search(userId: UUID, query: MemorySearch): List<Memory> = lock.withLock {
        query.validate()
        val nowInstant = Instant.now(clock)
        memories.values.asSequence()
            .filter { it.userId == userId.toString() }
            .filter { query.includeInactive || it.isActive }
            .filter { query.includeExpired || it.expiresAt == null || Instant.parse(it.expiresAt).isAfter(nowInstant) }
            .filter { query.memoryType == null || it.memoryType == query.memoryType }
            .filter { query.privacyScopes == null || it.privacyScope in query.privacyScopes }
            .filter { query.personId == null || it.personId == query.personId }
            .filter { query.organizationId == null || it.organizationId == query.organizationId }
            .filter { query.projectId == null || it.projectId == query.projectId }
            .filter { query.text.isNullOrBlank() || it.content.contains(query.text, ignoreCase = true) }
            .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.lastUsedAt ?: "" }.thenByDescending { it.createdAt })
            .take(query.limit)
            .toList()
    }

    override suspend fun update(userId: UUID, id: UUID, update: MemoryUpdate): Memory = lock.withLock {
        update.validate()
        if (update.isEmpty) throw MemoryValidationException("update contains no fields")
        val current = find(userId, id)
        val changed = current.copy(
            content = update.content?.trim() ?: current.content,
            importance = update.importance ?: current.importance,
            confidence = update.confidence ?: current.confidence,
            expiresAt = if (update.clearExpiry) null else update.expiresAt ?: current.expiresAt,
            personId = update.personId ?: current.personId,
            organizationId = update.organizationId ?: current.organizationId,
            projectId = update.projectId ?: current.projectId,
            privacyScope = update.privacyScope ?: current.privacyScope,
            isActive = update.isActive ?: current.isActive,
            metadata = update.metadata ?: current.metadata,
            updatedAt = now(),
        )
        memories[id] = changed
        when {
            update.isActive == false && current.isActive -> log(id, MemoryEventType.DISABLED, emptyMap())
            update.isActive == true && !current.isActive -> log(id, MemoryEventType.ENABLED, emptyMap())
            else -> log(id, MemoryEventType.UPDATED, emptyMap())
        }
        changed
    }

    override suspend fun delete(userId: UUID, id: UUID) = lock.withLock {
        find(userId, id)
        memories.remove(id)
        embeddings.remove(id)
        log(id, MemoryEventType.DELETED, emptyMap())
    }

    override suspend fun markIncorrect(userId: UUID, id: UUID, reason: String?): Memory = lock.withLock {
        val current = find(userId, id)
        val changed = current.copy(confidence = 0f, isActive = false, updatedAt = now())
        memories[id] = changed
        log(id, MemoryEventType.MARKED_INCORRECT, reason?.let { mapOf("reason" to it) } ?: emptyMap())
        changed
    }

    override suspend fun touch(userId: UUID, id: UUID): Memory = lock.withLock {
        val changed = find(userId, id).copy(lastUsedAt = now())
        memories[id] = changed
        log(id, MemoryEventType.USED, emptyMap())
        changed
    }

    override suspend fun events(userId: UUID, id: UUID): List<MemoryEvent> = lock.withLock {
        events.filter { it.memoryId == id.toString() }
    }

    override suspend fun putEmbedding(userId: UUID, id: UUID, embedding: EmbeddingInput): Memory = lock.withLock {
        embedding.validate()
        val current = find(userId, id)
        embeddings[id] = embedding.model to embedding.vector
        val changed = current.copy(hasEmbedding = true)
        memories[id] = changed
        log(id, MemoryEventType.EMBEDDED, mapOf("model" to embedding.model, "dimensions" to embedding.vector.size.toString()))
        changed
    }

    override suspend fun searchSimilar(userId: UUID, query: SimilaritySearch): List<Memory> = lock.withLock {
        query.validate()
        memories.values.asSequence()
            .filter { it.userId == userId.toString() && it.isActive }
            .filter { query.memoryType == null || it.memoryType == query.memoryType }
            .filter { query.privacyScopes == null || it.privacyScope in query.privacyScopes }
            .filter { query.personId == null || it.personId == query.personId }
            .filter { query.projectId == null || it.projectId == query.projectId }
            .mapNotNull { m ->
                val (model, vec) = embeddings[UUID.fromString(m.id)] ?: return@mapNotNull null
                if (query.model != null && model != query.model) return@mapNotNull null
                if (vec.size != query.vector.size) return@mapNotNull null
                m.copy(distance = cosineDistance(vec, query.vector))
            }
            .sortedBy { it.distance }
            .take(query.limit)
            .toList()
    }

    override suspend fun createPerson(userId: UUID, person: NewPerson): Person = lock.withLock {
        person.validate()
        val p = Person(UUID.randomUUID().toString(), person.name.trim(), person.aliases, person.relationship, person.organizationId, person.role, person.notes)
        people[UUID.fromString(p.id)] = p
        p
    }

    override suspend fun listPeople(userId: UUID, includeInactive: Boolean): List<Person> = lock.withLock { people.values.filter { includeInactive || it.isActive } }

    override suspend fun createProject(userId: UUID, project: NewProject): Project = lock.withLock {
        project.validate()
        val p = Project(UUID.randomUUID().toString(), project.name.trim(), project.description, project.organizationId)
        projects[UUID.fromString(p.id)] = p
        p
    }

    override suspend fun listProjects(userId: UUID, includeInactive: Boolean): List<Project> = lock.withLock { projects.values.filter { includeInactive || it.isActive } }

    override suspend fun createOrganization(userId: UUID, organization: NewOrganization): Organization = lock.withLock {
        organization.validate()
        val o = Organization(UUID.randomUUID().toString(), organization.name.trim(), organization.aliases, organization.notes)
        organizations[UUID.fromString(o.id)] = o
        o
    }

    override suspend fun listOrganizations(userId: UUID, includeInactive: Boolean): List<Organization> = lock.withLock { organizations.values.filter { includeInactive || it.isActive } }

    override suspend fun count(userId: UUID, includeInactive: Boolean): Long = lock.withLock {
        memories.values.count { it.userId == userId.toString() && (includeInactive || it.isActive) }.toLong()
    }

    private fun find(userId: UUID, id: UUID): Memory =
        memories[id]?.takeIf { it.userId == userId.toString() } ?: throw MemoryNotFoundException(id.toString())

    private fun log(memoryId: UUID, type: MemoryEventType, details: Map<String, String>) {
        events += MemoryEvent(++eventSeq, memoryId.toString(), type, now(), details)
    }

    companion object {
        fun cosineDistance(a: List<Float>, b: List<Float>): Float {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            if (na == 0.0 || nb == 0.0) return 1f
            return (1.0 - dot / (sqrt(na) * sqrt(nb))).toFloat()
        }
    }
}
