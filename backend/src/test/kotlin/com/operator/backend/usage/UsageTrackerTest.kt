package com.operator.backend.usage

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageTrackerTest {
    private fun at(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test
    fun `totals accumulate per day, per month, per model`() {
        val tracker = UsageTracker(clock = at("2026-09-05T10:00:00Z"))
        tracker.record("model", "openrouter", "v/fast", "FAST", inputTokens = 100, outputTokens = 20, latencyMillis = 400, costUsd = 0.001)
        tracker.record("model", "openrouter", "v/fast", "FAST", inputTokens = 50, outputTokens = 10, latencyMillis = 600, costUsd = 0.002)
        tracker.record("model", "openrouter", "v/deep", "DEEP", inputTokens = 200, outputTokens = 80, latencyMillis = 2000, costUsd = 0.01)

        val r = tracker.report()
        assertEquals(3, r.allTime.calls)
        assertEquals(350, r.allTime.inputTokens)
        assertEquals(110, r.allTime.outputTokens)
        assertEquals(0.013, r.allTime.costUsd!!, 1e-9)
        assertEquals(1000, r.allTime.averageLatencyMillis)
        assertEquals(3, r.today.calls)
        assertEquals(3, r.month.calls)
        assertEquals(2, r.byModel["v/fast"]!!.calls)
        assertEquals(1, r.byModel["v/deep"]!!.calls)
        assertEquals("v/deep", r.recent.first().model, "recent is newest first")
    }

    @Test
    fun `failures are counted separately and cost stays null when nobody reports it`() {
        val tracker = UsageTracker(clock = at("2026-09-05T10:00:00Z"))
        tracker.record("model", "openrouter", "v/fast", failed = true, latencyMillis = 30)
        tracker.record("model", "openrouter", "v/fast", inputTokens = 5, outputTokens = 1, latencyMillis = 70)
        val r = tracker.report()
        assertEquals(2, r.allTime.calls)
        assertEquals(1, r.allTime.failures)
        assertEquals(50, r.allTime.averageLatencyMillis)
        assertNull(r.allTime.costUsd, "no provider reported a cost, so do not invent 0.00")
    }

    @Test
    fun `tts characters accumulate with the rest of usage`() {
        val tracker = UsageTracker(clock = at("2026-09-05T10:00:00Z"))
        tracker.record("tts", "elevenlabs", "eleven_flash_v2_5", characters = 12, latencyMillis = 90)
        tracker.record("tts", "elevenlabs", "eleven_flash_v2_5", characters = 8, latencyMillis = 110)

        val report = tracker.report()
        assertEquals(20L, report.allTime.characters)
        assertEquals(20L, report.today.characters)
        assertEquals(20L, report.byModel["eleven_flash_v2_5"]!!.characters)
    }

    @Test
    fun `yesterday's calls stay out of today but inside the month`() {
        val tracker = UsageTracker(clock = at("2026-09-04T23:59:00Z"))
        tracker.record("model", "openrouter", "v/fast", inputTokens = 10)
        val laterTracker = UsageTracker(clock = at("2026-09-05T00:01:00Z"))
        laterTracker.record("model", "openrouter", "v/fast", inputTokens = 7)
        assertEquals(1, laterTracker.report().today.calls)
        assertEquals(1, tracker.report().today.calls)
    }

    @Test
    fun `the ring buffer is bounded but all-time totals are not`() {
        val tracker = UsageTracker(capacity = 3, clock = at("2026-09-05T10:00:00Z"))
        repeat(10) { tracker.record("model", "openrouter", "v/fast", inputTokens = 1) }
        val r = tracker.report()
        assertEquals(10, r.allTime.calls)
        assertEquals(10, r.allTime.inputTokens)
        assertEquals(3, r.recent.size)
        assertTrue(r.note.contains("3"))
    }
}
