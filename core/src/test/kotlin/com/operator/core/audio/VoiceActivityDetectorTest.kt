package com.operator.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceActivityDetectorTest {

    private fun silence(samples: Int = FRAME) = ShortArray(samples)

    private fun tone(amplitude: Float, samples: Int = FRAME): ShortArray =
        ShortArray(samples) { i -> (sin(2 * PI * 300 * i / 16000.0) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    @Test
    fun `silence never opens the gate`() {
        val vad = VoiceActivityDetector()
        repeat(50) { assertFalse(vad.accept(silence())) }
        assertFalse(vad.speaking)
    }

    @Test
    fun `speech opens the gate after the onset frames`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, calibrationFrames = 2)
        repeat(10) { vad.accept(silence()) }
        assertFalse(vad.accept(tone(0.3f)), "one loud frame is not yet speech")
        assertTrue(vad.accept(tone(0.3f)), "second consecutive loud frame opens the gate")
        assertTrue(vad.speaking)
    }

    @Test
    fun `a single click does not open the gate`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, calibrationFrames = 2)
        repeat(10) { vad.accept(silence()) }
        assertFalse(vad.accept(tone(0.9f)))
        assertFalse(vad.accept(silence()))
        assertFalse(vad.speaking)
    }

    @Test
    fun `the gate stays open through a short pause and closes after the hangover`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, hangoverFrames = 5, calibrationFrames = 2)
        repeat(10) { vad.accept(silence()) }
        repeat(4) { vad.accept(tone(0.3f)) }
        assertTrue(vad.speaking)
        repeat(4) { assertTrue(vad.accept(silence()), "a pause shorter than the hangover keeps the gate open") }
        assertFalse(vad.accept(silence()), "the fifth silent frame closes it")
        assertFalse(vad.speaking)
    }

    @Test
    fun `a noisy room raises the floor so room noise alone is not speech`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, minimumRms = 0.001f, calibrationFrames = 2)
        // Steady background hiss well above the absolute minimum, present from the first frame.
        repeat(200) { assertFalse(vad.accept(tone(0.05f))) }
        assertFalse(vad.speaking, "constant background noise must not read as speech")
    }

    @Test
    fun `speech still cuts through a noisy room`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, minimumRms = 0.001f, calibrationFrames = 2)
        repeat(20) { vad.accept(tone(0.05f)) }
        vad.accept(tone(0.5f))
        assertTrue(vad.accept(tone(0.5f)), "a voice well above the learned floor opens the gate")
    }

    @Test
    fun `the gate cannot open while the room is still being learned`() {
        val vad = VoiceActivityDetector(onsetFrames = 2, calibrationFrames = 6)
        repeat(6) { assertFalse(vad.accept(tone(0.4f)), "no capture until the room is learned") }
        assertFalse(vad.calibrating)
    }

    @Test
    fun `rms is zero for silence and near the amplitude for a full-scale square wave`() {
        assertEquals(0f, VoiceActivityDetector.rms(silence()))
        val square = ShortArray(FRAME) { if (it % 2 == 0) 16384 else -16384 }
        assertEquals(0.5f, VoiceActivityDetector.rms(square), 0.001f)
    }

    @Test
    fun `reset returns the detector to its initial state`() {
        val vad = VoiceActivityDetector(calibrationFrames = 2)
        repeat(10) { vad.accept(silence()) }
        repeat(5) { vad.accept(tone(0.4f)) }
        assertTrue(vad.speaking)
        vad.reset()
        assertFalse(vad.speaking)
        assertEquals(0f, vad.lastRms)
    }

    @Test
    fun `the gate cannot open during calibration, however loud the room is`() {
        // The property calibration exists for. Nothing pinned it before, which is how the length
        // came to be 160 ms - shorter than a single syllable, and shorter than most of the noises
        // it is meant to measure (risk 33).
        val vad = VoiceActivityDetector(calibrationFrames = 25)
        repeat(25) {
            vad.accept(tone(0.6f))
            assertFalse(vad.speaking, "the gate opened while still learning the room")
        }
    }

    @Test
    fun `calibration is long enough to span more than one noise event`() {
        // At the app's 20 ms frames this is a second. The exact number is still an estimate, but a
        // default shorter than a syllable cannot do the job the parameter exists for.
        assertTrue(
            VoiceActivityDetector.DEFAULT_CALIBRATION_FRAMES * 20 >= 500,
            "calibration should span at least half a second at 20 ms frames",
        )
    }

    private companion object {
        const val FRAME = 320 // 20 ms at 16 kHz
    }
}
