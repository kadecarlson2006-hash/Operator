package com.operator.core.tts

/**
 * Streaming text-to-speech contract. Provider credentials and implementations live in the
 * backend; callers consume raw PCM incrementally so playback can begin before synthesis ends.
 */
interface TTSProvider {
    val name: String
    suspend fun open(request: TTSRequest): TTSAudioStream
    fun cancel()
}

data class TTSRequest(val text: String, val voiceId: String? = null, val modelId: String? = null)

/** Raw signed 16-bit little-endian mono PCM returned incrementally by a TTS provider. */
interface TTSAudioStream : AutoCloseable {
    val sampleRateHz: Int
    val channels: Int
    suspend fun read(buffer: ByteArray): Int
    override fun close()
}
