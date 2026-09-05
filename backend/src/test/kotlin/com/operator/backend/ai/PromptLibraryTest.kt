package com.operator.backend.ai

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptLibraryTest {
    private fun tempDir(): File = Files.createTempDirectory("prompts").toFile().also { it.deleteOnExit() }

    @Test
    fun `loads a version, caches it, and lists what is available`() {
        val dir = tempDir()
        File(dir, "operator-system-v1.txt").writeText("You are Operator.")
        File(dir, "operator-system-v2.txt").writeText("You are Operator, mark two.")
        val library = PromptLibrary(dir)
        assertEquals("You are Operator.", library.load("operator-system-v1"))
        assertEquals("You are Operator, mark two.", library.load("operator-system-v2"))
        assertEquals(listOf("operator-system-v1", "operator-system-v2"), library.available())
    }

    @Test
    fun `a missing version returns null rather than an invented prompt`() {
        assertNull(PromptLibrary(tempDir()).load("operator-system-v9"))
    }

    @Test
    fun `path traversal in the version is refused`() {
        val dir = tempDir()
        File(dir, "secret.txt").writeText("nope")
        assertNull(PromptLibrary(dir).load("../secret"))
        assertNull(PromptLibrary(dir).load("/etc/passwd"))
    }

    @Test
    fun `the shipped v1 prompt exists and states the silence rule`() {
        val library = PromptLibrary()
        val text = library.load("operator-system-v1")
        assertNotNull(text, "operator-prompts/system/operator-system-v1.txt must ship with the repo")
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("SILENCE"), "the silence rule is the product's first rule")
        assertTrue(text.contains("Never invent facts"))
    }
}
