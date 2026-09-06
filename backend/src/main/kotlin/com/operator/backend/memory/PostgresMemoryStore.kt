package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL + pgvector implementation over plain JDBC (ADR-020: no ORM). Every public call is
 * one transaction on Dispatchers.IO. Vectors travel as pgvector text literals (`[0.1,0.2]`).
 */
class PostgresMemoryStore(private val dataSource: DataSource) : MemoryStore {
    override val backendName = "postgresql+pgvector"

    private val json = Json

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val result = block(c)
                c.commit()
                result
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    // ------------------------------------------------------------ memories

    override suspend fun create(userId: UUID, memory: NewMemory): Memory = tx { c ->
        memory.validate()
        memory.personId?.let { requireEntity(c, "people", "person", it) }
        memory.projectId?.let { requireEntity(c, "projects", "project", it) }
        memory.organizationId?.let { requireEntity(c, "organizations", "organization", it) }
        c.prepareStatement(
            "SELECT id FROM memories WHERE user_id = ? AND is_active AND memory_type = ? AND lower(regexp_replace(btrim(content), '\\s+', ' ', 'g')) = ? LIMIT 1",
        ).use { st ->
            st.setObject(1, userId); st.setString(2, memory.memoryType.name); st.setString(3, contentKey(memory.content))
            st.executeQuery().use { rs -> if (rs.next()) throw DuplicateMemoryException(rs.getObject(1, UUID::class.java).toString()) }
        }
        val id = UUID.randomUUID()
        c.prepareStatement(
            """INSERT INTO memories (id, user_id, memory_type, content, source_type, source_reference, importance, confidence,
               expires_at, person_id, organization_id, project_id, privacy_scope, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
        ).use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, memory.memoryType.name); st.setString(4, memory.content.trim())
            st.setString(5, memory.sourceType.name); st.setString(6, memory.sourceReference)
            st.setFloat(7, memory.importance); st.setFloat(8, memory.confidence)
            st.setTimestamp(9, memory.expiresAt?.let { Timestamp.from(Instant.parse(it)) })
            st.setObject(10, memory.personId?.let(UUID::fromString)); st.setObject(11, memory.organizationId?.let(UUID::fromString)); st.setObject(12, memory.projectId?.let(UUID::fromString))
            st.setString(13, memory.privacyScope.name); st.setString(14, toJson(memory.metadata))
            st.executeUpdate()
        }
        logEvent(c, id, userId, MemoryEventType.CREATED, mapOf("sourceType" to memory.sourceType.name))
        load(c, userId, id)
    }

    override suspend fun get(userId: UUID, id: UUID): Memory = tx { c -> load(c, userId, id) }

    override suspend fun search(userId: UUID, query: MemorySearch): List<Memory> = tx { c ->
        query.validate()
        val sql = StringBuilder(SELECT_MEMORY).append(" WHERE m.user_id = ?")
        val args = mutableListOf<Any?>(userId)
        if (!query.includeInactive) sql.append(" AND m.is_active")
        if (!query.includeExpired) sql.append(" AND (m.expires_at IS NULL OR m.expires_at > now())")
        query.memoryType?.let { sql.append(" AND m.memory_type = ?"); args += it.name }
        query.privacyScopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
            sql.append(" AND m.privacy_scope = ANY(?)"); args.add(scopes.map { it.name }.toTypedArray())
        }
        query.personId?.let { sql.append(" AND m.person_id = ?"); args += parseUuid(it, "personId") }
        query.organizationId?.let { sql.append(" AND m.organization_id = ?"); args += parseUuid(it, "organizationId") }
        query.projectId?.let { sql.append(" AND m.project_id = ?"); args += parseUuid(it, "projectId") }
        query.text?.takeIf { it.isNotBlank() }?.let { sql.append(" AND m.content ILIKE ?"); args += "%${it.trim()}%" }
        sql.append(" ORDER BY m.importance DESC, m.last_used_at DESC NULLS LAST, m.created_at DESC LIMIT ?")
        args += query.limit
        c.prepareStatement(sql.toString()).use { st ->
            bind(c, st, args)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rowToMemory(rs) else null }.toList() }
        }
    }

    override suspend fun update(userId: UUID, id: UUID, update: MemoryUpdate): Memory = tx { c ->
        update.validate()
        if (update.isEmpty) throw MemoryValidationException("update contains no fields")
        val current = load(c, userId, id)
        val sets = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        update.content?.let { sets += "content = ?"; args += it.trim() }
        update.importance?.let { sets += "importance = ?"; args += it }
        update.confidence?.let { sets += "confidence = ?"; args += it }
        if (update.clearExpiry) sets += "expires_at = NULL" else update.expiresAt?.let { sets += "expires_at = ?"; args += Timestamp.from(Instant.parse(it)) }
        update.personId?.let { requireEntity(c, "people", "person", it); sets += "person_id = ?"; args += UUID.fromString(it) }
        update.organizationId?.let { requireEntity(c, "organizations", "organization", it); sets += "organization_id = ?"; args += UUID.fromString(it) }
        update.projectId?.let { requireEntity(c, "projects", "project", it); sets += "project_id = ?"; args += UUID.fromString(it) }
        update.privacyScope?.let { sets += "privacy_scope = ?"; args += it.name }
        update.isActive?.let { sets += "is_active = ?"; args += it }
        update.metadata?.let { sets += "metadata = ?::jsonb"; args += toJson(it) }
        sets += "updated_at = now()"
        args += id; args += userId
        c.prepareStatement("UPDATE memories SET ${sets.joinToString(", ")} WHERE id = ? AND user_id = ?").use { st ->
            bind(c, st, args); st.executeUpdate()
        }
        val eventType = when {
            update.isActive == false && current.isActive -> MemoryEventType.DISABLED
            update.isActive == true && !current.isActive -> MemoryEventType.ENABLED
            else -> MemoryEventType.UPDATED
        }
        logEvent(c, id, userId, eventType, emptyMap())
        load(c, userId, id)
    }

    override suspend fun delete(userId: UUID, id: UUID) = tx { c ->
        load(c, userId, id)
        c.prepareStatement("DELETE FROM memories WHERE id = ? AND user_id = ?").use { st -> st.setObject(1, id); st.setObject(2, userId); st.executeUpdate() }
        logEvent(c, id, userId, MemoryEventType.DELETED, emptyMap())
    }

    override suspend fun markIncorrect(userId: UUID, id: UUID, reason: String?): Memory = tx { c ->
        load(c, userId, id)
        c.prepareStatement("UPDATE memories SET confidence = 0, is_active = FALSE, updated_at = now() WHERE id = ? AND user_id = ?").use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.executeUpdate()
        }
        logEvent(c, id, userId, MemoryEventType.MARKED_INCORRECT, reason?.let { mapOf("reason" to it) } ?: emptyMap())
        load(c, userId, id)
    }

    override suspend fun touch(userId: UUID, id: UUID): Memory = tx { c ->
        load(c, userId, id)
        c.prepareStatement("UPDATE memories SET last_used_at = now() WHERE id = ? AND user_id = ?").use { st -> st.setObject(1, id); st.setObject(2, userId); st.executeUpdate() }
        logEvent(c, id, userId, MemoryEventType.USED, emptyMap())
        load(c, userId, id)
    }

    override suspend fun events(userId: UUID, id: UUID): List<MemoryEvent> = tx { c ->
        c.prepareStatement("SELECT id, memory_id, event_type, at, details::text FROM memory_events WHERE memory_id = ? AND user_id = ? ORDER BY at, id").use { st ->
            st.setObject(1, id); st.setObject(2, userId)
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else MemoryEvent(
                        id = rs.getLong(1), memoryId = rs.getObject(2, UUID::class.java).toString(),
                        eventType = MemoryEventType.valueOf(rs.getString(3)), at = rs.getTimestamp(4).toInstant().toString(),
                        details = fromJson(rs.getString(5)),
                    )
                }.toList()
            }
        }
    }

    // ------------------------------------------------------------ embeddings

    override suspend fun putEmbedding(userId: UUID, id: UUID, embedding: EmbeddingInput): Memory = tx { c ->
        embedding.validate()
        load(c, userId, id)
        c.prepareStatement(
            """INSERT INTO memory_embeddings (memory_id, model, dimensions, embedding) VALUES (?, ?, ?, ?::vector)
               ON CONFLICT (memory_id) DO UPDATE SET model = EXCLUDED.model, dimensions = EXCLUDED.dimensions, embedding = EXCLUDED.embedding, created_at = now()""",
        ).use { st ->
            st.setObject(1, id); st.setString(2, embedding.model); st.setInt(3, embedding.vector.size); st.setString(4, vectorLiteral(embedding.vector))
            st.executeUpdate()
        }
        logEvent(c, id, userId, MemoryEventType.EMBEDDED, mapOf("model" to embedding.model, "dimensions" to embedding.vector.size.toString()))
        load(c, userId, id)
    }

    override suspend fun searchSimilar(userId: UUID, query: SimilaritySearch): List<Memory> = tx { c ->
        query.validate()
        val sql = StringBuilder(SELECT_MEMORY.replace("SELECT m.*", "SELECT m.*, (e.embedding <=> ?::vector) AS distance"))
            .append(" JOIN memory_embeddings e ON e.memory_id = m.id AND e.dimensions = ?")
        val args = mutableListOf<Any?>(vectorLiteral(query.vector), query.vector.size)
        sql.append(" WHERE m.user_id = ? AND m.is_active AND (m.expires_at IS NULL OR m.expires_at > now())"); args += userId
        query.model?.let { sql.append(" AND e.model = ?"); args += it }
        query.memoryType?.let { sql.append(" AND m.memory_type = ?"); args += it.name }
        query.privacyScopes?.takeIf { it.isNotEmpty() }?.let { scopes -> sql.append(" AND m.privacy_scope = ANY(?)"); args.add(scopes.map { it.name }.toTypedArray()) }
        query.personId?.let { sql.append(" AND m.person_id = ?"); args += parseUuid(it, "personId") }
        query.projectId?.let { sql.append(" AND m.project_id = ?"); args += parseUuid(it, "projectId") }
        sql.append(" ORDER BY distance ASC LIMIT ?"); args += query.limit
        c.prepareStatement(sql.toString()).use { st ->
            bind(c, st, args)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rowToMemory(rs, withDistance = true) else null }.toList() }
        }
    }

    override suspend fun listWithoutEmbeddings(userId: UUID, limit: Int): List<Memory> = tx { c ->
        require(limit > 0) { "limit must be positive" }
        c.prepareStatement(
            """$SELECT_MEMORY
               WHERE m.user_id = ? AND m.is_active
                 AND (m.expires_at IS NULL OR m.expires_at > now())
                 AND NOT EXISTS (SELECT 1 FROM memory_embeddings e WHERE e.memory_id = m.id)
               ORDER BY m.created_at, m.id
               LIMIT ?""",
        ).use { st ->
            st.setObject(1, userId)
            st.setInt(2, limit)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rowToMemory(rs) else null }.toList() }
        }
    }

    // ------------------------------------------------------------ people / projects / organizations

    override suspend fun createPerson(userId: UUID, person: NewPerson): Person = tx { c ->
        person.validate()
        person.organizationId?.let { requireEntity(c, "organizations", "organization", it) }
        val id = UUID.randomUUID()
        c.prepareStatement("INSERT INTO people (id, user_id, name, aliases, relationship, organization_id, role, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, person.name.trim())
            st.setArray(4, c.createArrayOf("text", person.aliases.toTypedArray())); st.setString(5, person.relationship)
            st.setObject(6, person.organizationId?.let(UUID::fromString)); st.setString(7, person.role); st.setString(8, person.notes)
            st.executeUpdate()
        }
        listPeopleIn(c, userId, true).first { it.id == id.toString() }
    }

    override suspend fun listPeople(userId: UUID, includeInactive: Boolean): List<Person> = tx { c -> listPeopleIn(c, userId, includeInactive) }

    private fun listPeopleIn(c: Connection, userId: UUID, includeInactive: Boolean): List<Person> =
        c.prepareStatement("SELECT id, name, aliases, relationship, organization_id, role, notes, is_active FROM people WHERE user_id = ?${if (includeInactive) "" else " AND is_active"} ORDER BY lower(name)").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else Person(
                        id = rs.getObject(1, UUID::class.java).toString(), name = rs.getString(2),
                        aliases = (rs.getArray(3)?.array as? Array<*>)?.map { it.toString() } ?: emptyList(),
                        relationship = rs.getString(4), organizationId = rs.getObject(5, UUID::class.java)?.toString(),
                        role = rs.getString(6), notes = rs.getString(7), isActive = rs.getBoolean(8),
                    )
                }.toList()
            }
        }

    override suspend fun createProject(userId: UUID, project: NewProject): Project = tx { c ->
        project.validate()
        project.organizationId?.let { requireEntity(c, "organizations", "organization", it) }
        val id = UUID.randomUUID()
        c.prepareStatement("INSERT INTO projects (id, user_id, name, description, organization_id) VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, project.name.trim()); st.setString(4, project.description)
            st.setObject(5, project.organizationId?.let(UUID::fromString)); st.executeUpdate()
        }
        listProjectsIn(c, userId, true).first { it.id == id.toString() }
    }

    override suspend fun listProjects(userId: UUID, includeInactive: Boolean): List<Project> = tx { c -> listProjectsIn(c, userId, includeInactive) }

    private fun listProjectsIn(c: Connection, userId: UUID, includeInactive: Boolean): List<Project> =
        c.prepareStatement("SELECT id, name, description, organization_id, status, is_active FROM projects WHERE user_id = ?${if (includeInactive) "" else " AND is_active"} ORDER BY lower(name)").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else Project(
                        id = rs.getObject(1, UUID::class.java).toString(), name = rs.getString(2), description = rs.getString(3),
                        organizationId = rs.getObject(4, UUID::class.java)?.toString(), status = rs.getString(5), isActive = rs.getBoolean(6),
                    )
                }.toList()
            }
        }

    override suspend fun createOrganization(userId: UUID, organization: NewOrganization): Organization = tx { c ->
        organization.validate()
        val id = UUID.randomUUID()
        c.prepareStatement("INSERT INTO organizations (id, user_id, name, aliases, notes) VALUES (?, ?, ?, ?, ?)").use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, organization.name.trim())
            st.setArray(4, c.createArrayOf("text", organization.aliases.toTypedArray())); st.setString(5, organization.notes); st.executeUpdate()
        }
        listOrganizationsIn(c, userId, true).first { it.id == id.toString() }
    }

    override suspend fun listOrganizations(userId: UUID, includeInactive: Boolean): List<Organization> = tx { c -> listOrganizationsIn(c, userId, includeInactive) }

    private fun listOrganizationsIn(c: Connection, userId: UUID, includeInactive: Boolean): List<Organization> =
        c.prepareStatement("SELECT id, name, aliases, notes, is_active FROM organizations WHERE user_id = ?${if (includeInactive) "" else " AND is_active"} ORDER BY lower(name)").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else Organization(
                        id = rs.getObject(1, UUID::class.java).toString(), name = rs.getString(2),
                        aliases = (rs.getArray(3)?.array as? Array<*>)?.map { it.toString() } ?: emptyList(),
                        notes = rs.getString(4), isActive = rs.getBoolean(5),
                    )
                }.toList()
            }
        }

    // ------------------------------------------------------------ conversation sessions

    override suspend fun createSession(userId: UUID, session: NewConversationSession): ConversationSession = tx { c ->
        session.validate()
        val id = UUID.randomUUID()
        c.prepareStatement(
            "INSERT INTO conversation_sessions (id, user_id, operator_mode, wit_level, prompt_version, summary) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.setString(3, session.operatorMode)
            st.setString(4, session.witLevel); st.setString(5, session.promptVersion); st.setString(6, session.summary)
            st.executeUpdate()
        }
        loadSession(c, userId, id)
    }

    override suspend fun getSession(userId: UUID, id: UUID): ConversationSession = tx { c -> loadSession(c, userId, id) }

    override suspend fun listSessions(userId: UUID): List<ConversationSession> = tx { c ->
        c.prepareStatement("$SELECT_SESSION WHERE user_id = ? ORDER BY started_at DESC, id DESC").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rowToSession(rs) else null }.toList() }
        }
    }

    override suspend fun updateSession(
        userId: UUID,
        id: UUID,
        update: ConversationSessionUpdate,
    ): ConversationSession = tx { c ->
        update.validate()
        val current = loadSession(c, userId, id)
        val changed = current.copy(
            endedAt = if (update.clearEndedAt) null else update.endedAt ?: current.endedAt,
            operatorMode = if (update.clearOperatorMode) null else update.operatorMode ?: current.operatorMode,
            witLevel = if (update.clearWitLevel) null else update.witLevel ?: current.witLevel,
            promptVersion = if (update.clearPromptVersion) null else update.promptVersion ?: current.promptVersion,
            summary = if (update.clearSummary) null else update.summary ?: current.summary,
        )
        changed.endedAt?.let {
            if (parseInstant(it, "endedAt").isBefore(parseInstant(changed.startedAt, "startedAt"))) {
                throw MemoryValidationException("endedAt must not be before startedAt")
            }
        }
        c.prepareStatement(
            "UPDATE conversation_sessions SET ended_at = ?, operator_mode = ?, wit_level = ?, prompt_version = ?, summary = ? WHERE id = ? AND user_id = ?",
        ).use { st ->
            st.setTimestamp(1, changed.endedAt?.let { Timestamp.from(Instant.parse(it)) })
            st.setString(2, changed.operatorMode); st.setString(3, changed.witLevel)
            st.setString(4, changed.promptVersion); st.setString(5, changed.summary)
            st.setObject(6, id); st.setObject(7, userId); st.executeUpdate()
        }
        changed
    }

    override suspend fun deleteSession(userId: UUID, id: UUID) = tx { c ->
        val deleted = c.prepareStatement("DELETE FROM conversation_sessions WHERE id = ? AND user_id = ?").use { st ->
            st.setObject(1, id); st.setObject(2, userId); st.executeUpdate()
        }
        if (deleted == 0) throw EntityNotFoundException("conversation session", id.toString())
    }

    override suspend fun count(userId: UUID, includeInactive: Boolean): Long = tx { c ->
        c.prepareStatement("SELECT count(*) FROM memories WHERE user_id = ?${if (includeInactive) "" else " AND is_active"}").use { st ->
            st.setObject(1, userId); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun load(c: Connection, userId: UUID, id: UUID): Memory =
        c.prepareStatement("$SELECT_MEMORY WHERE m.id = ? AND m.user_id = ?").use { st ->
            st.setObject(1, id); st.setObject(2, userId)
            st.executeQuery().use { rs -> if (rs.next()) rowToMemory(rs) else throw MemoryNotFoundException(id.toString()) }
        }

    private fun loadSession(c: Connection, userId: UUID, id: UUID): ConversationSession =
        c.prepareStatement("$SELECT_SESSION WHERE id = ? AND user_id = ?").use { st ->
            st.setObject(1, id); st.setObject(2, userId)
            st.executeQuery().use { rs ->
                if (rs.next()) rowToSession(rs) else throw EntityNotFoundException("conversation session", id.toString())
            }
        }

    private fun requireEntity(c: Connection, table: String, kind: String, id: String) {
        val uuid = parseUuid(id, "${kind}Id")
        c.prepareStatement("SELECT 1 FROM $table WHERE id = ?").use { st ->
            st.setObject(1, uuid); st.executeQuery().use { rs -> if (!rs.next()) throw EntityNotFoundException(kind, id) }
        }
    }

    private fun logEvent(c: Connection, memoryId: UUID, userId: UUID, type: MemoryEventType, details: Map<String, String>) {
        c.prepareStatement("INSERT INTO memory_events (memory_id, user_id, event_type, details) VALUES (?, ?, ?, ?::jsonb)").use { st ->
            st.setObject(1, memoryId); st.setObject(2, userId); st.setString(3, type.name); st.setString(4, toJson(details)); st.executeUpdate()
        }
    }

    private fun bind(c: Connection, st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                is Array<*> -> st.setArray(i + 1, c.createArrayOf("text", a))
                is Timestamp -> st.setTimestamp(i + 1, a)
                is Int -> st.setInt(i + 1, a)
                is Float -> st.setFloat(i + 1, a)
                is Boolean -> st.setBoolean(i + 1, a)
                is UUID -> st.setObject(i + 1, a)
                is String -> st.setString(i + 1, a)
                null -> st.setObject(i + 1, null)
                else -> st.setObject(i + 1, a)
            }
        }
    }

    private fun rowToMemory(rs: ResultSet, withDistance: Boolean = false): Memory = Memory(
        id = rs.getObject("id", UUID::class.java).toString(),
        userId = rs.getObject("user_id", UUID::class.java).toString(),
        memoryType = MemoryType.valueOf(rs.getString("memory_type")),
        content = rs.getString("content"),
        sourceType = SourceType.valueOf(rs.getString("source_type")),
        sourceReference = rs.getString("source_reference"),
        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant()?.toString(),
        importance = rs.getFloat("importance"),
        confidence = rs.getFloat("confidence"),
        expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
        personId = rs.getObject("person_id", UUID::class.java)?.toString(),
        organizationId = rs.getObject("organization_id", UUID::class.java)?.toString(),
        projectId = rs.getObject("project_id", UUID::class.java)?.toString(),
        privacyScope = PrivacyScope.valueOf(rs.getString("privacy_scope")),
        isActive = rs.getBoolean("is_active"),
        metadata = fromJson(rs.getString("metadata_text")),
        hasEmbedding = rs.getBoolean("has_embedding"),
        distance = if (withDistance) rs.getFloat("distance") else null,
    )

    private fun rowToSession(rs: ResultSet): ConversationSession = ConversationSession(
        id = rs.getObject("id", UUID::class.java).toString(),
        userId = rs.getObject("user_id", UUID::class.java).toString(),
        startedAt = rs.getTimestamp("started_at").toInstant().toString(),
        endedAt = rs.getTimestamp("ended_at")?.toInstant()?.toString(),
        operatorMode = rs.getString("operator_mode"),
        witLevel = rs.getString("wit_level"),
        promptVersion = rs.getString("prompt_version"),
        summary = rs.getString("summary"),
    )

    private fun toJson(map: Map<String, String>): String = json.encodeToString(map)

    private fun fromJson(text: String?): Map<String, String> =
        if (text.isNullOrBlank()) emptyMap() else json.parseToJsonElement(text).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }

    private companion object {
        const val SELECT_MEMORY = """SELECT m.*, m.metadata::text AS metadata_text,
            EXISTS (SELECT 1 FROM memory_embeddings x WHERE x.memory_id = m.id) AS has_embedding FROM memories m"""
        const val SELECT_SESSION =
            "SELECT id, user_id, started_at, ended_at, operator_mode, wit_level, prompt_version, summary FROM conversation_sessions"

        fun vectorLiteral(vector: List<Float>): String = vector.joinToString(",", "[", "]")
    }
}
