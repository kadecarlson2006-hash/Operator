package com.operator.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EnvFileTest {
    @Test
    fun `parses keys, quotes, exports and comments`() {
        val parsed = EnvFile.parse(
            """
            # comment
            OPENROUTER_API_KEY=sk-or-abc123
            export DATABASE_URL="postgresql://u:p@localhost:5432/operator"
            OPERATOR_PROMPT_VERSION='operator-system-v2'
            OPERATOR_FAST_MODEL_ID=vendor/model # trailing comment
            EMPTY=
            not a pair
            """.trimIndent(),
        )
        assertEquals("sk-or-abc123", parsed["OPENROUTER_API_KEY"])
        assertEquals("postgresql://u:p@localhost:5432/operator", parsed["DATABASE_URL"])
        assertEquals("operator-system-v2", parsed["OPERATOR_PROMPT_VERSION"])
        assertEquals("vendor/model", parsed["OPERATOR_FAST_MODEL_ID"])
        assertEquals("", parsed["EMPTY"])
        assertFalse(parsed.containsKey("not a pair"))
    }
}
