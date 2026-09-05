package com.operator.backend.ai

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Loads versioned Operator system prompts from `operator-prompts/system/<version>.txt`
 * (ADR-023: prompts are versioned configuration, never Kotlin string literals).
 *
 * Search order: an explicit directory, then `operator-prompts/system` relative to the working
 * directory or its parents (so `./gradlew :backend:run` from the repo root works), then the
 * classpath. Missing prompt files are reported, never silently replaced by an invented prompt.
 */
class PromptLibrary(private val directory: File? = null) {

    private val cache = HashMap<String, String>()

    /** Returns the prompt text for [version], or null when that version cannot be found. */
    @Synchronized
    fun load(version: String): String? {
        if (!VERSION_PATTERN.matches(version)) {
            log.warn("Refusing to load prompt version with unexpected characters: {}", version)
            return null
        }
        cache[version]?.let { return it }
        val text = readFile(version) ?: readClasspath(version)
        if (text == null) {
            log.error("System prompt '{}' not found; requests will run without a personality prompt", version)
            return null
        }
        cache[version] = text
        log.info("Loaded system prompt {} ({} characters)", version, text.length)
        return text
    }

    fun available(): List<String> = searchRoots().firstOrNull { it.isDirectory }
        ?.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
        ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    private fun readFile(version: String): String? = searchRoots()
        .map { File(it, "$version.txt") }
        .firstOrNull { it.isFile }
        ?.readText()

    private fun searchRoots(): List<File> {
        directory?.let { return listOf(it) }
        val roots = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.normalize()
        repeat(4) {
            dir?.let { roots += File(it, RELATIVE_DIR); dir = it.parentFile }
        }
        return roots
    }

    private fun readClasspath(version: String): String? =
        javaClass.classLoader.getResourceAsStream("$RELATIVE_DIR/$version.txt")?.bufferedReader()?.use { it.readText() }

    private companion object {
        val log = LoggerFactory.getLogger(PromptLibrary::class.java)
        val VERSION_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        const val RELATIVE_DIR = "operator-prompts/system"
    }
}
