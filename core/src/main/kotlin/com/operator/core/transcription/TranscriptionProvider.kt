package com.operator.core.transcription

import com.operator.core.audio.PcmClip

/**
 * Speech-to-text contract (Milestone 8+). Implementations may be cloud or on-device.
 * Local VAD is expected to run *before* this so that silence is never uploaded.
 */
interface TranscriptionProvider {
    val name: String
    suspend fun transcribe(clip: PcmClip): Transcript
}

data class Transcript(
    val text: String,
    val languageCode: String? = null,
    val latencyMillis: Long? = null,
    /** Length of the audio that was transcribed, when the provider reports it. */
    val audioSeconds: Double? = null,
    /** Which provider produced this, for diagnostics and usage accounting. */
    val provider: String? = null,
)
