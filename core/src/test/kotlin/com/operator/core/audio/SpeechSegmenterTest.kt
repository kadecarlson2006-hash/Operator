package com.operator.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechSegmenterTest {

    private fun silence() = ShortArray(FRAME)

    private fun tone(amplitude: Float = 0.4f) =
        ShortArray(FRAME) { i -> (sin(2 * PI * 300 * i / RATE) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    private fun segmenter(
        minSpeechMillis: Long = 100,
        maxSegmentMillis: Long = 2_000,
        preRollFrames: Int = 4,
    ) = SpeechSegmenter(
        sampleRateHz = RATE,
        frameSamples = FRAME,
        detector = VoiceActivityDetector(onsetFrames = 2, hangoverFrames = 4),
        preRollFrames = preRollFrames,
        minSpeechMillis = minSpeechMillis,
        maxSegmentMillis = maxSegmentMillis,
    )

    /** Feeds calibration frames so the detector has learned the (silent) room. */
    private fun SpeechSegmenter.settle() = repeat(12) { assertNull(accept(silence())) }

    @Test
    fun `silence alone never produces a segment`() {
        val s = segmenter()
        repeat(100) { assertNull(s.accept(silence())) }
        assertFalse(s.capturing)
    }

    @Test
    fun `an utterance is emitted once the speaker stops`() {
        val s = segmenter()
        s.settle()
        repeat(20) { assertNull(s.accept(tone())) }
        assertTrue(s.capturing)
        // Hangover is 4 frames; the fourth silent frame closes the gate and emits.
        assertNull(s.accept(silence()))
        assertNull(s.accept(silence()))
        assertNull(s.accept(silence()))
        val clip = s.accept(silence())
        assertNotNull(clip)
        assertEquals(RATE, clip.sampleRateHz)
        assertEquals(1, clip.channels)
        assertFalse(s.capturing)
    }

    @Test
    fun `the pre-roll keeps the frames just before the gate opened`() {
        fun capture(preRollFrames: Int): PcmClip {
            val s = segmenter(preRollFrames = preRollFrames)
            s.settle()
            repeat(20) { s.accept(tone()) }
            repeat(3) { s.accept(silence()) }
            return assertNotNull(s.accept(silence()), "the hangover should have closed the gate")
        }

        val withPreRoll = capture(preRollFrames = 4)
        val without = capture(preRollFrames = 0)

        assertEquals(
            4 * FRAME,
            withPreRoll.samples.size - without.samples.size,
            "the pre-roll should add exactly the frames captured before the gate opened",
        )
    }

    @Test
    fun `a click shorter than the minimum is discarded rather than uploaded`() {
        val s = segmenter(minSpeechMillis = 300) // 15 frames at 20 ms
        s.settle()
        repeat(3) { s.accept(tone(0.9f)) }
        repeat(4) { assertNull(s.accept(silence()), "a 60 ms knock is not an utterance") }
        assertFalse(s.capturing)
    }

    @Test
    fun `a monologue is cut at the maximum segment length`() {
        val s = segmenter(maxSegmentMillis = 400) // 20 frames at 20 ms
        s.settle()
        var emitted: PcmClip? = null
        repeat(40) { if (emitted == null) emitted = s.accept(tone()) }
        val clip = assertNotNull(emitted, "a speaker who never stops must still be cut")
        assertTrue(clip.durationMillis <= 500, "segment was ${clip.durationMillis} ms")
    }

    @Test
    fun `flush ends an utterance in progress`() {
        val s = segmenter()
        s.settle()
        repeat(20) { s.accept(tone()) }
        assertTrue(s.capturing)
        val clip = assertNotNull(s.flush())
        assertTrue(clip.samples.isNotEmpty())
        assertFalse(s.capturing)
        assertNull(s.flush(), "a second flush has nothing left to emit")
    }

    @Test
    fun `flush with nothing captured returns null`() {
        val s = segmenter()
        s.settle()
        assertNull(s.flush())
    }

    @Test
    fun `two utterances separated by silence are emitted separately`() {
        val s = segmenter()
        s.settle()
        repeat(20) { s.accept(tone()) }
        repeat(3) { s.accept(silence()) }
        val first = assertNotNull(s.accept(silence()))
        repeat(20) { s.accept(tone()) }
        repeat(3) { s.accept(silence()) }
        val second = assertNotNull(s.accept(silence()))
        assertTrue(first.samples.isNotEmpty() && second.samples.isNotEmpty())
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = 320 // 20 ms
    }
}
