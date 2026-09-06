package com.operator.core.transcription

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollingTranscriptTest {

    private class FakeClock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun transcript(windowMillis: Long = 60_000, maxEntries: Int = 60, clock: FakeClock = FakeClock()) =
        RollingTranscript(windowMillis, maxEntries, clock) to clock

    @Test
    fun `keeps what was said, oldest first`() {
        val (t, clock) = transcript()
        t.add("are we still on for Thursday")
        clock.now = 1_000
        t.add("Thursday works")
        assertContentEquals(
            listOf("are we still on for Thursday", "Thursday works"),
            t.entries().map { it.text },
        )
    }

    @Test
    fun `lines older than the window fall out`() {
        val (t, clock) = transcript(windowMillis = 10_000)
        t.add("old news")
        clock.now = 5_000
        t.add("still relevant")
        clock.now = 12_000
        t.add("newest")
        assertContentEquals(listOf("still relevant", "newest"), t.entries().map { it.text })
    }

    @Test
    fun `the window is pruned on read, not only on write`() {
        val (t, clock) = transcript(windowMillis = 10_000)
        t.add("said hours ago")
        assertEquals(1, t.size)

        // Nothing new is heard; time simply passes.
        clock.now = 3_600_000
        assertTrue(t.entries().isEmpty(), "a stale line must not survive just because nothing was added")
        assertNull(t.render())
    }

    @Test
    fun `the entry count is capped even inside the window`() {
        val (t, _) = transcript(windowMillis = 10_000_000, maxEntries = 3)
        repeat(10) { t.add("line $it") }
        assertContentEquals(listOf("line 7", "line 8", "line 9"), t.entries().map { it.text })
    }

    @Test
    fun `blank lines are not turns`() {
        val (t, _) = transcript()
        t.add("")
        t.add("   ")
        assertTrue(t.entries().isEmpty())
        assertNull(t.render())
    }

    @Test
    fun `text is trimmed`() {
        val (t, _) = transcript()
        t.add("  spaced out  ")
        assertEquals("spaced out", t.entries().single().text)
    }

    @Test
    fun `render labels speakers and returns null when empty`() {
        val (t, clock) = transcript()
        assertNull(t.render())
        t.add("where did I leave the keys")
        clock.now = 500
        t.add("On the hall table.", Speaker.OPERATOR)
        assertEquals(
            "Someone: where did I leave the keys\nOperator: On the hall table.",
            t.render(),
        )
    }

    @Test
    fun `captured speech is attributed to nobody in particular`() {
        val (t, _) = transcript()
        t.add("some sentence")
        assertEquals(
            Speaker.UNKNOWN,
            t.entries().single().speaker,
            "the provider returns text, not diarisation, so the speaker is not known",
        )
    }

    @Test
    fun `clear drops everything immediately`() {
        val (t, _) = transcript()
        t.add("something private")
        assertEquals(1, t.size)
        t.clear()
        assertTrue(t.entries().isEmpty())
        assertNull(t.render())
        assertTrue(t.state.value.isEmpty())
    }

    @Test
    fun `the state flow tracks the window`() {
        val (t, clock) = transcript(windowMillis = 10_000)
        assertTrue(t.state.value.isEmpty())
        t.add("first")
        assertEquals(listOf("first"), t.state.value.map { it.text })
        clock.now = 20_000
        t.add("second")
        assertEquals(listOf("second"), t.state.value.map { it.text }, "the expired line leaves the flow too")
    }

    @Test
    fun `span reports how much conversation is held`() {
        val (t, clock) = transcript()
        assertEquals(0, t.spanMillis())
        t.add("a")
        assertEquals(0, t.spanMillis(), "one line spans nothing")
        clock.now = 4_000
        t.add("b")
        assertEquals(4_000, t.spanMillis())
    }

    @Test
    fun `an entry does not print what was said`() {
        val rendered = TranscriptEntry("the alarm code is 4821", 1234, Speaker.UNKNOWN).toString()
        assertFalse(rendered.contains("4821"), "transcript text must not leak through toString: $rendered")
        assertFalse(rendered.contains("alarm"), rendered)
        assertTrue(rendered.contains("22 chars"), rendered)
    }

    @Test
    fun `nonsense bounds are rejected`() {
        val failures = listOf(
            runCatching { RollingTranscript(windowMillis = 0) },
            runCatching { RollingTranscript(maxEntries = 0) },
        )
        assertTrue(failures.all { it.isFailure })
    }
}
