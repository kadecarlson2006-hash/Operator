package com.operator.core.config

import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorConfigTest {

    @Test
    fun `defaults are sensible and secret-free`() {
        val c = OperatorConfig()
        assertEquals(OperatorMode.STANDBY, c.defaultMode)
        assertEquals(WitLevel.NORMAL, c.defaultWit)
        assertNull(c.fastModelId)
        assertNull(c.elevenLabsVoiceId)
    }

    @Test
    fun `fromMap parses known keys case-insensitively and ignores unknown ones`() {
        val c = OperatorConfig.fromMap(
            mapOf(
                OperatorConfig.Keys.DEFAULT_MODE to "work",
                OperatorConfig.Keys.DEFAULT_WIT to "SHARP",
                OperatorConfig.Keys.FAST_MODEL_ID to "  some/model  ",
                OperatorConfig.Keys.ROLLING_CONTEXT_SECONDS to "120",
                OperatorConfig.Keys.RECORD_TEST_DURATION_MILLIS to "5000",
                "SOMETHING_ELSE" to "x",
            ),
        )
        assertEquals(OperatorMode.WORK, c.defaultMode)
        assertEquals(WitLevel.SHARP, c.defaultWit)
        assertEquals("some/model", c.fastModelId)
        assertEquals(120, c.rollingContextSeconds)
        assertEquals(5_000, c.recordTestDurationMillis)
    }

    @Test
    fun `malformed or empty values fall back to defaults`() {
        val c = OperatorConfig.fromMap(
            mapOf(
                OperatorConfig.Keys.DEFAULT_MODE to "banana",
                OperatorConfig.Keys.ROLLING_CONTEXT_SECONDS to "lots",
                OperatorConfig.Keys.FAST_MODEL_ID to "",
            ),
        )
        assertEquals(OperatorMode.STANDBY, c.defaultMode)
        assertEquals(60, c.rollingContextSeconds)
        assertNull(c.fastModelId)
    }

    @Test
    fun `record test duration is bounded`() {
        assertFailsWith<IllegalArgumentException> { OperatorConfig(recordTestDurationMillis = 100) }
        assertFailsWith<IllegalArgumentException> { OperatorConfig(recordTestDurationMillis = 60_000) }
    }

    @Test
    fun `web search is on unless it is switched off`() {
        // Pinned because it is a default that spends money and changes answers, and because
        // nothing caught the reversal when it was made. Off, Operator answers from training data
        // and sounds exactly as sure as when it has looked.
        assertTrue(OperatorConfig().webSearchEnabled)
        assertFalse(OperatorConfig.fromMap(mapOf(OperatorConfig.Keys.WEB_SEARCH_ENABLED to "false")).webSearchEnabled)
        assertTrue(OperatorConfig.fromMap(mapOf(OperatorConfig.Keys.WEB_SEARCH_ENABLED to "true")).webSearchEnabled)
    }

    @Test
    fun `decision reasoning is low unless told otherwise`() {
        fun effort(v: String?) = OperatorConfig.fromMap(mapOf(OperatorConfig.Keys.DECISION_REASONING_EFFORT to v)).decisionReasoningEffort
        assertEquals("low", OperatorConfig().decisionReasoningEffort)
        assertEquals("low", effort(null))
        assertEquals("minimal", effort("Minimal"))
        assertNull(effort("default"), "default leaves it to the model")
        assertEquals("low", effort("turbo"), "an unknown value falls back rather than being sent")
    }

    @Test
    fun `searched answers think minimally unless told otherwise`() {
        fun effort(v: String?) = OperatorConfig.fromMap(mapOf(OperatorConfig.Keys.SEARCH_REASONING_EFFORT to v)).searchReasoningEffort
        assertEquals("minimal", OperatorConfig().searchReasoningEffort)
        assertEquals("low", effort("low"))
        assertNull(effort("default"))
        assertEquals("minimal", effort("fast please"))
    }

    @Test
    fun `the rewrite gets long enough to finish`() {
        // Live it took 1-3s; a 2s cap cut off 7 of 8.
        fun timeout(v: String?) = OperatorConfig.fromMap(mapOf(OperatorConfig.Keys.SEARCH_REWRITE_TIMEOUT_MS to v)).searchRewriteTimeoutMillis
        assertEquals(3_500L, OperatorConfig().searchRewriteTimeoutMillis)
        assertEquals(5_000L, timeout("5000"))
        assertEquals(500L, timeout("10"), "clamped: zero would never let it run")
        assertEquals(3_500L, timeout("soon"))
    }
}
