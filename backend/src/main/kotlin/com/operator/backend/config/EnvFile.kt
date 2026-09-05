package com.operator.backend.config

import java.io.File

/**
 * Minimal `.env` reader: `KEY=value`, optional `export ` prefix, `#` comments, single or double
 * quotes stripped. Real environment variables take precedence over the file. No dependency,
 * no interpolation, no surprises.
 */
object EnvFile {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val body = line.removePrefix("export ").trim()
            val eq = body.indexOf('=')
            if (eq <= 0) return@forEach
            val key = body.substring(0, eq).trim()
            var value = body.substring(eq + 1).trim()
            value = when {
                value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.length - 1)
                value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.length - 1)
                else -> value.substringBefore(" #").trim()
            }
            out[key] = value
        }
        return out
    }

    fun load(file: File): Map<String, String> = if (file.isFile) parse(file.readText()) else emptyMap()
}
