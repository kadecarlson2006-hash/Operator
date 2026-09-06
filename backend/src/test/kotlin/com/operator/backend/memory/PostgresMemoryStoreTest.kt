package com.operator.backend.memory

import com.operator.core.memory.MemoryType
import com.operator.core.memory.PrivacyScope
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class PostgresMemoryStoreTest {
    private val store = PostgresMemoryStore(dataSource)

    @BeforeTest
    fun clearMemoryTables() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE memory_events, memory_embeddings, memories, people, projects, organizations RESTART IDENTITY CASCADE",
                )
            }
        }
    }

    @Test
    fun `jdbc lifecycle persists links metadata and audit events`() = runTest {
        val organization = store.createOrganization(
            DEFAULT_USER_ID,
            NewOrganization("Acme Logistics", aliases = listOf("Acme"), notes = "Customer"),
        )
        val person = store.createPerson(
            DEFAULT_USER_ID,
            NewPerson(
                name = "Chris",
                aliases = listOf("C"),
                relationship = "customer",
                organizationId = organization.id,
                role = "dispatcher",
            ),
        )
        val project = store.createProject(
            DEFAULT_USER_ID,
            NewProject("West rollout", description = "Late-order dashboard", organizationId = organization.id),
        )

        val created = store.create(
            DEFAULT_USER_ID,
            NewMemory(
                memoryType = MemoryType.WORK_FACT,
                content = "  Chris handles the west.  ",
                sourceType = SourceType.IMPORT,
                sourceReference = "crm-42",
                importance = 0.7f,
                confidence = 0.8f,
                personId = person.id,
                organizationId = organization.id,
                projectId = project.id,
                privacyScope = PrivacyScope.WORK,
                metadata = mapOf("source" to "crm", "priority" to "high"),
            ),
        )

        assertEquals("Chris handles the west.", created.content)
        assertEquals(person.id, created.personId)
        assertEquals(organization.id, created.organizationId)
        assertEquals(project.id, created.projectId)
        assertEquals(mapOf("source" to "crm", "priority" to "high"), created.metadata)
        assertEquals(created, store.get(DEFAULT_USER_ID, UUID.fromString(created.id)))
        assertEquals(
            listOf(created.id),
            store.search(
                DEFAULT_USER_ID,
                MemorySearch(
                    text = "handles the west",
                    memoryType = MemoryType.WORK_FACT,
                    privacyScopes = setOf(PrivacyScope.WORK),
                    personId = person.id,
                    organizationId = organization.id,
                    projectId = project.id,
                ),
            ).map { it.id },
        )

        val updated = store.update(
            DEFAULT_USER_ID,
            UUID.fromString(created.id),
            MemoryUpdate(importance = 0.95f, metadata = mapOf("reviewed" to "true")),
        )
        assertEquals(0.95f, updated.importance)
        assertEquals(mapOf("reviewed" to "true"), updated.metadata)
        assertNull(updated.lastUsedAt)

        val touched = store.touch(DEFAULT_USER_ID, UUID.fromString(created.id))
        assertNotNull(touched.lastUsedAt)
        store.markIncorrect(DEFAULT_USER_ID, UUID.fromString(created.id), "superseded")
        assertTrue(store.search(DEFAULT_USER_ID, MemorySearch()).isEmpty())
        assertEquals(1, store.search(DEFAULT_USER_ID, MemorySearch(includeInactive = true)).size)

        store.delete(DEFAULT_USER_ID, UUID.fromString(created.id))
        assertFailsWith<MemoryNotFoundException> {
            store.get(DEFAULT_USER_ID, UUID.fromString(created.id))
        }
        assertEquals(
            listOf(
                MemoryEventType.CREATED,
                MemoryEventType.UPDATED,
                MemoryEventType.USED,
                MemoryEventType.MARKED_INCORRECT,
                MemoryEventType.DELETED,
            ),
            store.events(DEFAULT_USER_ID, UUID.fromString(created.id)).map { it.eventType },
        )
    }

    @Test
    fun `duplicate rejection rolls back without losing the original memory`() = runTest {
        val original = store.create(
            DEFAULT_USER_ID,
            NewMemory(MemoryType.PREFERENCE, "Prefers decisions in writing"),
        )

        val duplicate = assertFailsWith<DuplicateMemoryException> {
            store.create(
                DEFAULT_USER_ID,
                NewMemory(MemoryType.PREFERENCE, "  PREFERS  decisions in WRITING "),
            )
        }

        assertEquals(original.id, duplicate.existingId)
        assertEquals(listOf(original.id), store.search(DEFAULT_USER_ID, MemorySearch()).map { it.id })
        assertEquals(listOf(MemoryEventType.CREATED), store.events(DEFAULT_USER_ID, UUID.fromString(original.id)).map { it.eventType })
    }

    @Test
    fun `pgvector similarity search is correct without an ANN index`() = runTest {
        assertFalse(hasApproximateNearestNeighborIndex(), "Risk 25 fixture must exercise the exact-scan path")

        val closest = store.create(
            DEFAULT_USER_ID,
            NewMemory(MemoryType.WORK_FACT, "dashboard shows late orders first", privacyScope = PrivacyScope.WORK),
        )
        val farther = store.create(
            DEFAULT_USER_ID,
            NewMemory(MemoryType.WORK_FACT, "truck is waiting at the dock", privacyScope = PrivacyScope.WORK),
        )
        val noEmbedding = store.create(
            DEFAULT_USER_ID,
            NewMemory(MemoryType.WORK_FACT, "this row has no vector", privacyScope = PrivacyScope.WORK),
        )

        store.putEmbedding(DEFAULT_USER_ID, UUID.fromString(closest.id), EmbeddingInput("fake-v1", listOf(1f, 0f, 0f)))
        store.putEmbedding(DEFAULT_USER_ID, UUID.fromString(farther.id), EmbeddingInput("fake-v1", listOf(0f, 1f, 0f)))

        assertTrue(store.get(DEFAULT_USER_ID, UUID.fromString(closest.id)).hasEmbedding)
        assertFalse(store.get(DEFAULT_USER_ID, UUID.fromString(noEmbedding.id)).hasEmbedding)

        val hits = store.searchSimilar(
            DEFAULT_USER_ID,
            SimilaritySearch(
                vector = listOf(0.9f, 0.1f, 0f),
                model = "fake-v1",
                memoryType = MemoryType.WORK_FACT,
                privacyScopes = setOf(PrivacyScope.WORK),
                limit = 5,
            ),
        )
        assertEquals(listOf(closest.id, farther.id), hits.map { it.id })
        val closestDistance = assertNotNull(hits[0].distance)
        val fartherDistance = assertNotNull(hits[1].distance)
        assertTrue(closestDistance < fartherDistance)
        assertNull(store.get(DEFAULT_USER_ID, UUID.fromString(closest.id)).distance)
        assertTrue(
            store.searchSimilar(DEFAULT_USER_ID, SimilaritySearch(vector = listOf(1f, 0f), limit = 5)).isEmpty(),
            "dimension mismatch must not compare incompatible vectors",
        )
        assertTrue(
            store.searchSimilar(
                DEFAULT_USER_ID,
                SimilaritySearch(vector = listOf(1f, 0f, 0f), model = "other-model"),
            ).isEmpty(),
        )
    }

    private fun hasApproximateNearestNeighborIndex(): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'memory_embeddings' AND indexdef ~* '(hnsw|ivfflat)')",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
        }

    companion object {
        private val pgvectorImage = DockerImageName.parse("pgvector/pgvector:0.8.6-pg17")
            .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer(pgvectorImage)
            .withDatabaseName("operator_test")
            .withUsername("operator")
            .withPassword("operator_test")

        @JvmField
        val dataSource = PGSimpleDataSource()

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun migrateSchema() {
            dataSource.setURL(postgres.jdbcUrl)
            dataSource.user = postgres.username
            dataSource.password = postgres.password
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}
