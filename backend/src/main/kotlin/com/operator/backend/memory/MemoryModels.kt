package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** The single user until authentication exists (Milestone 4 migration seeds it). */
val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

enum class SourceType { EXPLICIT_USER, AMBIENT, IMPORT, DEMO, SYSTEM }

enum class MemoryEventType { CREATED, UPDATED, USED, DISABLED, ENABLED, MARKED_INCORRECT, DELETED, EMBEDDED }

/** A stored memory. Timestamps are ISO-8601 strings on the wire. */
@Serializable
data class Memory(
    val id: String,
    val userId: String,
    val memoryType: MemoryType,
    val content: String,
    val sourceType: SourceType,
    val sourceReference: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val lastUsedAt: String? = null,
    val importance: Float,
    val confidence: Float,
    val expiresAt: String? = null,
    val personId: String? = null,
    val organizationId: String? = null,
    val projectId: String? = null,
    val privacyScope: PrivacyScope,
    val isActive: Boolean,
    val metadata: Map<String, String> = emptyMap(),
    val hasEmbedding: Boolean = false,
    /** Cosine distance to the query vector; only set by similarity search. */
    val distance: Float? = null,
)

@Serializable
data class NewMemory(
    val memoryType: MemoryType,
    val content: String,
    val sourceType: SourceType = SourceType.EXPLICIT_USER,
    val sourceReference: String? = null,
    val importance: Float = 0.5f,
    val confidence: Float = 0.5f,
    val expiresAt: String? = null,
    val personId: String? = null,
    val organizationId: String? = null,
    val projectId: String? = null,
    val privacyScope: PrivacyScope = PrivacyScope.PERSONAL,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun validate() {
        if (content.isBlank()) throw MemoryValidationException("content must not be blank")
        if (importance !in 0f..1f) throw MemoryValidationException("importance must be within 0..1")
        if (confidence !in 0f..1f) throw MemoryValidationException("confidence must be within 0..1")
        expiresAt?.let { parseInstant(it, "expiresAt") }
        listOf("personId" to personId, "organizationId" to organizationId, "projectId" to projectId).forEach { (name, v) ->
            v?.let { parseUuid(it, name) }
        }
    }
}

/** Partial update. Absent fields are untouched. `clearExpiry` removes an expiry. */
@Serializable
data class MemoryUpdate(
    val content: String? = null,
    val importance: Float? = null,
    val confidence: Float? = null,
    val expiresAt: String? = null,
    val clearExpiry: Boolean = false,
    val personId: String? = null,
    val organizationId: String? = null,
    val projectId: String? = null,
    val privacyScope: PrivacyScope? = null,
    val isActive: Boolean? = null,
    val metadata: Map<String, String>? = null,
) {
    fun validate() {
        content?.let { if (it.isBlank()) throw MemoryValidationException("content must not be blank") }
        importance?.let { if (it !in 0f..1f) throw MemoryValidationException("importance must be within 0..1") }
        confidence?.let { if (it !in 0f..1f) throw MemoryValidationException("confidence must be within 0..1") }
        expiresAt?.let { parseInstant(it, "expiresAt") }
    }

    val isEmpty: Boolean
        get() = content == null && importance == null && confidence == null && expiresAt == null && !clearExpiry &&
            personId == null && organizationId == null && projectId == null && privacyScope == null && isActive == null && metadata == null
}

@Serializable
data class MemorySearch(
    val text: String? = null,
    val memoryType: MemoryType? = null,
    val privacyScopes: Set<PrivacyScope>? = null,
    val personId: String? = null,
    val organizationId: String? = null,
    val projectId: String? = null,
    val includeInactive: Boolean = false,
    val includeExpired: Boolean = false,
    val limit: Int = 20,
) {
    fun validate() {
        if (limit !in 1..200) throw MemoryValidationException("limit must be within 1..200")
    }
}

@Serializable
data class SimilaritySearch(
    val vector: List<Float>,
    val model: String? = null,
    val limit: Int = 5,
    val memoryType: MemoryType? = null,
    val privacyScopes: Set<PrivacyScope>? = null,
    val personId: String? = null,
    val projectId: String? = null,
) {
    fun validate() {
        if (vector.isEmpty()) throw MemoryValidationException("vector must not be empty")
        if (limit !in 1..50) throw MemoryValidationException("limit must be within 1..50")
    }
}

@Serializable
data class EmbeddingInput(val model: String, val vector: List<Float>) {
    fun validate() {
        if (model.isBlank()) throw MemoryValidationException("model must not be blank")
        if (vector.isEmpty()) throw MemoryValidationException("vector must not be empty")
    }
}

@Serializable
data class MemoryEvent(val id: Long, val memoryId: String, val eventType: MemoryEventType, val at: String, val details: Map<String, String> = emptyMap())

@Serializable
data class Person(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val relationship: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
)

@Serializable
data class NewPerson(
    val name: String,
    val aliases: List<String> = emptyList(),
    val relationship: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val notes: String? = null,
) {
    fun validate() {
        if (name.isBlank()) throw MemoryValidationException("name must not be blank")
        organizationId?.let { parseUuid(it, "organizationId") }
    }
}

@Serializable
data class PersonUpdate(
    val name: String? = null,
    val aliases: List<String>? = null,
    val relationship: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val notes: String? = null,
    val isActive: Boolean? = null,
    val clearRelationship: Boolean = false,
    val clearOrganization: Boolean = false,
    val clearRole: Boolean = false,
    val clearNotes: Boolean = false,
) {
    fun validate() {
        name?.let { if (it.isBlank()) throw MemoryValidationException("name must not be blank") }
        organizationId?.let { parseUuid(it, "organizationId") }
        if (organizationId != null && clearOrganization) throw MemoryValidationException("organizationId and clearOrganization cannot both be set")
        if (relationship != null && clearRelationship) throw MemoryValidationException("relationship and clearRelationship cannot both be set")
        if (role != null && clearRole) throw MemoryValidationException("role and clearRole cannot both be set")
        if (notes != null && clearNotes) throw MemoryValidationException("notes and clearNotes cannot both be set")
        if (isEmpty) throw MemoryValidationException("person update must contain at least one field")
    }

    private val isEmpty: Boolean
        get() = name == null && aliases == null && relationship == null && organizationId == null && role == null &&
            notes == null && isActive == null && !clearRelationship && !clearOrganization && !clearRole && !clearNotes
}

@Serializable
data class Project(val id: String, val name: String, val description: String? = null, val organizationId: String? = null, val status: String = "ACTIVE", val isActive: Boolean = true)

@Serializable
data class NewProject(val name: String, val description: String? = null, val organizationId: String? = null) {
    fun validate() {
        if (name.isBlank()) throw MemoryValidationException("name must not be blank")
        organizationId?.let { parseUuid(it, "organizationId") }
    }
}

@Serializable
data class ProjectUpdate(
    val name: String? = null,
    val description: String? = null,
    val organizationId: String? = null,
    val status: String? = null,
    val isActive: Boolean? = null,
    val clearDescription: Boolean = false,
    val clearOrganization: Boolean = false,
) {
    fun validate() {
        name?.let { if (it.isBlank()) throw MemoryValidationException("name must not be blank") }
        status?.let { if (it.isBlank()) throw MemoryValidationException("status must not be blank") }
        organizationId?.let { parseUuid(it, "organizationId") }
        if (organizationId != null && clearOrganization) throw MemoryValidationException("organizationId and clearOrganization cannot both be set")
        if (description != null && clearDescription) throw MemoryValidationException("description and clearDescription cannot both be set")
        if (isEmpty) throw MemoryValidationException("project update must contain at least one field")
    }

    private val isEmpty: Boolean
        get() = name == null && description == null && organizationId == null && status == null && isActive == null &&
            !clearDescription && !clearOrganization
}

@Serializable
data class Organization(val id: String, val name: String, val aliases: List<String> = emptyList(), val notes: String? = null, val isActive: Boolean = true)

@Serializable
data class NewOrganization(val name: String, val aliases: List<String> = emptyList(), val notes: String? = null) {
    fun validate() {
        if (name.isBlank()) throw MemoryValidationException("name must not be blank")
    }
}

@Serializable
data class OrganizationUpdate(
    val name: String? = null,
    val aliases: List<String>? = null,
    val notes: String? = null,
    val isActive: Boolean? = null,
    val clearNotes: Boolean = false,
) {
    fun validate() {
        name?.let { if (it.isBlank()) throw MemoryValidationException("name must not be blank") }
        if (notes != null && clearNotes) throw MemoryValidationException("notes and clearNotes cannot both be set")
        if (name == null && aliases == null && notes == null && isActive == null && !clearNotes) {
            throw MemoryValidationException("organization update must contain at least one field")
        }
    }
}

// ---- errors (mapped to HTTP status codes in Application.kt) ----
open class MemoryException(message: String) : RuntimeException(message)
class MemoryValidationException(message: String) : MemoryException(message)
class MemoryNotFoundException(id: String) : MemoryException("memory $id not found")
class EntityNotFoundException(kind: String, id: String) : MemoryException("$kind $id not found")
class DuplicateMemoryException(val existingId: String) : MemoryException("an active memory with the same type and content already exists: $existingId")

internal fun parseUuid(value: String, field: String): UUID =
    try { UUID.fromString(value) } catch (e: IllegalArgumentException) { throw MemoryValidationException("$field is not a UUID") }

internal fun parseInstant(value: String, field: String): Instant =
    try { Instant.parse(value) } catch (e: Exception) { throw MemoryValidationException("$field is not an ISO-8601 instant") }

/** Case-insensitive, whitespace-collapsed content key used for duplicate detection. */
internal fun contentKey(content: String): String = content.trim().lowercase().replace(Regex("\\s+"), " ")
