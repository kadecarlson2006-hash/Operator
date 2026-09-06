package com.operator.backend.config

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendConfigTest {
    @Test
    fun `defaults are safe`() {
        val c = BackendConfig.fromMap(emptyMap())
        assertEquals(8080, c.port)
        assertFalse(c.databaseConfigured)
        assertFalse(c.openRouterConfigured)
        assertEquals("operator-system-v1", c.promptVersion)
        assertNull(c.redacted()["openRouterApiKey"])
    }

    @Test
    fun `secrets never appear in the redacted view`() {
        val c = BackendConfig.fromMap(
            mapOf(
                BackendConfig.Keys.OPENROUTER_API_KEY to "sk-or-v1-0123456789abcdef",
                BackendConfig.Keys.ELEVENLABS_API_KEY to "short",
                BackendConfig.Keys.DATABASE_URL to "postgresql://operator:hunter2@db.internal:5432/operator",
                "OPERATOR_FAST_MODEL_ID" to "vendor/fast",
            ),
        )
        val view = c.redacted()
        assertEquals("set (…cdef)", view["openRouterApiKey"])
        assertEquals("set", view["elevenLabsApiKey"])
        assertEquals("jdbc:postgresql://db.internal:5432/operator", view["databaseUrl"])
        assertEquals("vendor/fast", view["fastModelId"])
        assertFalse(view.values.any { it?.contains("hunter2") == true })
        assertFalse(view.values.any { it?.contains("0123456789") == true })
    }

    @Test
    fun `libpq style DATABASE_URL becomes a JDBC target with credentials`() {
        val t = JdbcTarget.from("postgres://operator:pw@localhost:5432/operator?sslmode=disable", null, null)
        assertEquals("jdbc:postgresql://localhost:5432/operator?sslmode=disable", t.url)
        assertEquals("operator", t.user)
        assertEquals("pw", t.password)
    }

    @Test
    fun `explicit user and password override the URL and jdbc URLs pass through`() {
        val t = JdbcTarget.from("postgresql://host/db", "u2", "p2")
        assertEquals("jdbc:postgresql://host/db", t.url)
        assertEquals("u2", t.user)
        assertEquals("p2", t.password)
        val j = JdbcTarget.from("jdbc:postgresql://h:5433/x", "a", null)
        assertEquals("jdbc:postgresql://h:5433/x", j.url)
        assertEquals("a", j.user)
        assertNull(j.password)
    }

    @Test
    fun `garbage DATABASE_URL is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> { JdbcTarget.from("mysql://nope", null, null) }
    }

    @Test
    fun `environment beats env file`() {
        val c = BackendConfig.fromMap(mapOf(BackendConfig.Keys.PORT to "9090", BackendConfig.Keys.PROMPT_VERSION to "operator-system-v3"))
        assertEquals(9090, c.port)
        assertEquals("operator-system-v3", c.promptVersion)
        assertTrue(BackendConfig.fromMap(mapOf(BackendConfig.Keys.PORT to "99999")).port == 8080)
    }
    @Test
    fun `the env file is found from a subdirectory, not only the working directory`() {
        val root = Files.createTempDirectory("envsearch").toFile()
        val nested = File(root, "backend/build/run").apply { mkdirs() }
        File(root, ".env").writeText("OPENROUTER_API_KEY=from-the-root\n")

        // `gradle :backend:run` starts the JVM in backend/, while .env belongs in the repo root.
        val found = BackendConfig.findEnvFile(nested)
        assertNotNull(found)
        assertEquals(File(root, ".env").canonicalPath, found.canonicalPath)
    }

    @Test
    fun `the nearest env file wins`() {
        val root = Files.createTempDirectory("envsearch").toFile()
        val nested = File(root, "backend").apply { mkdirs() }
        File(root, ".env").writeText("OPENROUTER_API_KEY=outer\n")
        File(nested, ".env").writeText("OPENROUTER_API_KEY=inner\n")

        val found = assertNotNull(BackendConfig.findEnvFile(nested))
        assertEquals(File(nested, ".env").canonicalPath, found.canonicalPath)
    }

    @Test
    fun `no env file anywhere is not an error`() {
        val empty = Files.createTempDirectory("envsearch-empty").toFile()
        assertNull(BackendConfig.findEnvFile(empty))
    }

}
