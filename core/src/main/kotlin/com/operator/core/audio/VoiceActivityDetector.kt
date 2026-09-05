package com.operator.core.audio

import kotlin.math.sqrt

/**
 * Energy-based voice activity detection over PCM-16 frames (Milestone 8).
 *
 * This is the privacy gate, not a quality feature: nothing is uploaded until this says a frame
 * contains speech, so silence never leaves the device (project rule: no permanent raw audio,
 * rolling in-memory buffers only). It is deliberately simple and deterministic — an RMS level
 * against an adaptive noise floor, with hysteresis so a single loud sample cannot open the gate
 * and a short pause mid-sentence cannot close it.
 *
 * UNKNOWN TO VERIFY: the thresholds below are estimates chosen to be testable, not values
 * measured on the glasses' microphone in a noisy room. See docs/RISKS_AND_UNKNOWNS.md item 33.
 */
class VoiceActivityDetector(
    /** RMS (0..1) must exceed the noise floor by this factor before a frame counts as speech. */
    private val activationFactor: Float = 3.0f,
    /** Absolute floor so a silent room with near-zero noise cannot be triggered by dither. */
    private val minimumRms: Float = 0.01f,
    /** Consecutive speech frames needed to open the gate. */
    private val onsetFrames: Int = 2,
    /** Consecutive non-speech frames needed to close it (mid-sentence pauses stay open). */
    private val hangoverFrames: Int = 12,
    /** How quickly the noise floor follows the room, per non-speech frame. */
    private val noiseAdaptation: Float = 0.05f,
    /**
     * Frames spent learning the room before the gate may open at all. Without this, starting up
     * inside a noisy room latches the gate open on the noise itself and Operator uploads the
     * whole room; the floor can never adapt because it only adapts while the gate is shut.
     */
    private val calibrationFrames: Int = 8,
) {
    init {
        require(activationFactor > 1f) { "activationFactor must be > 1" }
        require(minimumRms > 0f) { "minimumRms must be > 0" }
        require(onsetFrames >= 1) { "onsetFrames must be >= 1" }
        require(hangoverFrames >= 1) { "hangoverFrames must be >= 1" }
        require(noiseAdaptation in 0f..1f) { "noiseAdaptation must be 0..1" }
        require(calibrationFrames >= 0) { "calibrationFrames must be >= 0" }
    }

    private var noiseFloor = minimumRms
    private var consecutiveSpeech = 0
    private var consecutiveSilence = 0
    private var framesSeen = 0
    private var calibrationSum = 0f

    /** True while the gate is open, i.e. while frames should be kept for transcription. */
    var speaking: Boolean = false
        private set

    /** Level of the most recent frame, for the UI meter. */
    var lastRms: Float = 0f
        private set

    /**
     * Feeds one frame and returns whether it should be retained.
     *
     * The frame that opens the gate is retained, so an utterance is never clipped at its onset.
     */
    fun accept(frame: ShortArray): Boolean {
        val rms = rms(frame)
        lastRms = rms
        framesSeen++
        if (framesSeen <= calibrationFrames) {
            // A plain running mean, not the slow exponential decay used later: calibration has
            // only a handful of frames to reach the true level of the room.
            calibrationSum += rms
            noiseFloor = maxOf(minimumRms, calibrationSum / framesSeen)
            return false
        }
        val loud = rms >= minimumRms && rms >= noiseFloor * activationFactor
        if (loud) {
            consecutiveSpeech++
            consecutiveSilence = 0
            if (!speaking && consecutiveSpeech >= onsetFrames) speaking = true
        } else {
            consecutiveSilence++
            consecutiveSpeech = 0
            // Only adapt to the room while it is quiet, so speech cannot raise the floor above itself.
            if (!speaking) noiseFloor = maxOf(minimumRms, noiseFloor * (1 - noiseAdaptation) + rms * noiseAdaptation)
            if (speaking && consecutiveSilence >= hangoverFrames) speaking = false
        }
        return speaking
    }

    /** Frames of trailing silence seen since the last speech frame. */
    val silenceRun: Int get() = consecutiveSilence

    /** True until the room has been learned; the gate cannot open before this clears. */
    val calibrating: Boolean get() = framesSeen < calibrationFrames

    fun reset() {
        noiseFloor = minimumRms
        consecutiveSpeech = 0
        consecutiveSilence = 0
        framesSeen = 0
        calibrationSum = 0f
        speaking = false
        lastRms = 0f
    }

    companion object {
        fun rms(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) {
                val v = s / 32768.0
                sum += v * v
            }
            return sqrt(sum / frame.size).toFloat()
        }
    }
}
