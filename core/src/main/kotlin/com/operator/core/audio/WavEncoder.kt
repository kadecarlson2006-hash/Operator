package com.operator.core.audio

/**
 * PCM-16 → WAV (RIFF) bytes (Milestone 8).
 *
 * Speech-to-text services take a container, not bare samples, and WAV is the one format that is
 * lossless, universally accepted, and needs no codec on the device. Encoding happens in memory
 * on the way to the request body; nothing is written to disk.
 */
object WavEncoder {
    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT: Short = 1
    private const val BITS_PER_SAMPLE = 16

    fun encode(clip: PcmClip): ByteArray = encode(clip.samples, clip.sampleRateHz, clip.channels)

    fun encode(samples: ShortArray, sampleRateHz: Int, channels: Int): ByteArray {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        require(channels > 0) { "channels must be > 0" }
        val dataBytes = samples.size * 2
        val out = ByteArray(HEADER_BYTES + dataBytes)
        var i = 0
        fun ascii(s: String) { for (c in s) out[i++] = c.code.toByte() }
        fun le32(v: Int) { out[i++] = v.toByte(); out[i++] = (v shr 8).toByte(); out[i++] = (v shr 16).toByte(); out[i++] = (v shr 24).toByte() }
        fun le16(v: Int) { out[i++] = v.toByte(); out[i++] = (v shr 8).toByte() }

        val byteRate = sampleRateHz * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8

        ascii("RIFF")
        le32(36 + dataBytes)        // chunk size: everything after this field
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                    // PCM subchunk size
        le16(PCM_FORMAT.toInt())
        le16(channels)
        le32(sampleRateHz)
        le32(byteRate)
        le16(blockAlign)
        le16(BITS_PER_SAMPLE)
        ascii("data")
        le32(dataBytes)
        for (s in samples) {
            out[i++] = s.toInt().toByte()
            out[i++] = (s.toInt() shr 8).toByte()
        }
        return out
    }
}
