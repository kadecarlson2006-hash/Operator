package com.operator.core.audio

/**
 * Turns a stream of PCM-16 frames into complete utterances (Milestone 8).
 *
 * Everything here is a bounded in-memory buffer that is dropped as soon as a segment is emitted
 * or discarded: no ambient audio is ever written to disk, and audio the detector never opened the
 * gate for is not retained at all. A small pre-roll ring is kept so that the first syllable — the
 * one that opened the gate — survives, and nothing older than that ring is remembered.
 *
 * @param preRollFrames frames kept before the gate opens so utterances are not clipped.
 * @param minSpeechMillis utterances shorter than this are discarded as coughs, clicks, or knocks.
 * @param maxSegmentMillis hard cap; a long monologue is cut here rather than buffered forever.
 */
class SpeechSegmenter(
    private val sampleRateHz: Int,
    private val frameSamples: Int,
    private val detector: VoiceActivityDetector = VoiceActivityDetector(),
    private val preRollFrames: Int = 4,
    private val minSpeechMillis: Long = 350,
    private val maxSegmentMillis: Long = 20_000,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        require(frameSamples > 0) { "frameSamples must be > 0" }
        require(preRollFrames >= 0) { "preRollFrames must be >= 0" }
        require(minSpeechMillis >= 0) { "minSpeechMillis must be >= 0" }
        require(maxSegmentMillis > minSpeechMillis) { "maxSegmentMillis must exceed minSpeechMillis" }
    }

    private val preRoll = ArrayDeque<ShortArray>()
    private var current: MutableList<ShortArray>? = null
    private var speechFrames = 0

    private val frameMillis: Long get() = frameSamples * 1000L / sampleRateHz
    private val maxFrames: Int get() = (maxSegmentMillis / frameMillis).toInt().coerceAtLeast(1)

    /** True while an utterance is being captured. Drives the "listening" indicator. */
    val capturing: Boolean get() = current != null

    /** Level of the most recent frame (0..1), for a level meter. */
    val level: Float get() = detector.lastRms

    /**
     * Feeds one frame. Returns a finished utterance when the speaker stops (or the cap is hit),
     * otherwise null. Frames outside speech are dropped immediately.
     */
    fun accept(frame: ShortArray): PcmClip? {
        val keep = detector.accept(frame)
        if (keep) {
            val segment = current ?: mutableListOf<ShortArray>().also { started ->
                started.addAll(preRoll)
                preRoll.clear()
                current = started
                speechFrames = 0
            }
            segment.add(frame)
            speechFrames++
            if (segment.size >= maxFrames) return finish()
            return null
        }
        val segment = current
        if (segment != null) {
            // Trailing silence inside the hangover is part of the utterance; the detector has
            // already closed the gate by the time we get here, so this is the end of it.
            return finish()
        }
        rememberPreRoll(frame)
        return null
    }

    /** Ends any utterance in progress, e.g. when the user releases push-to-talk. */
    fun flush(): PcmClip? = finish()

    fun reset() {
        preRoll.clear()
        current = null
        speechFrames = 0
        detector.reset()
    }

    private fun rememberPreRoll(frame: ShortArray) {
        if (preRollFrames == 0) return
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
    }

    private fun finish(): PcmClip? {
        val segment = current ?: return null
        current = null
        val speechMillis = speechFrames * frameMillis
        speechFrames = 0
        // Too short to be speech either way: a click, a knock, a chair. Discard it, do not upload it.
        if (speechMillis < minSpeechMillis) return null
        val total = segment.sumOf { it.size }
        val samples = ShortArray(total)
        var offset = 0
        for (f in segment) {
            f.copyInto(samples, offset)
            offset += f.size
        }
        return PcmClip(samples = samples, sampleRateHz = sampleRateHz, channels = 1)
    }
}
