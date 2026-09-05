package com.operator.core.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WavEncoderTest {

    private fun ascii(bytes: ByteArray, offset: Int, length: Int) =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `header describes 16-bit mono PCM at the clip's sample rate`() {
        val samples = ShortArray(160) { it.toShort() }
        val wav = WavEncoder.encode(PcmClip(samples, sampleRateHz = 16_000, channels = 1))

        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))

        assertEquals(16, le32(wav, 16), "PCM fmt chunk is 16 bytes")
        assertEquals(1, le16(wav, 20), "format 1 = uncompressed PCM")
        assertEquals(1, le16(wav, 22), "mono")
        assertEquals(16_000, le32(wav, 24), "sample rate")
        assertEquals(32_000, le32(wav, 28), "byte rate = rate * channels * 2")
        assertEquals(2, le16(wav, 32), "block align")
        assertEquals(16, le16(wav, 34), "bits per sample")
    }

    @Test
    fun `sizes are consistent with the payload`() {
        val samples = ShortArray(160) { it.toShort() }
        val wav = WavEncoder.encode(PcmClip(samples, 16_000, 1))
        assertEquals(44 + 320, wav.size)
        assertEquals(320, le32(wav, 40), "data chunk size is bytes, not samples")
        assertEquals(wav.size - 8, le32(wav, 4), "RIFF size counts everything after the field")
    }

    @Test
    fun `samples are written little-endian and round-trip`() {
        val samples = shortArrayOf(0, 1, -1, 256, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = WavEncoder.encode(samples, 16_000, 1)
        val decoded = ShortArray(samples.size) { i ->
            val lo = wav[44 + i * 2].toInt() and 0xFF
            val hi = wav[44 + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        assertContentEquals(samples, decoded)
    }

    @Test
    fun `an empty clip still produces a valid header`() {
        val wav = WavEncoder.encode(ShortArray(0), 16_000, 1)
        assertEquals(44, wav.size)
        assertEquals(0, le32(wav, 40))
    }

    @Test
    fun `stereo is described correctly`() {
        val wav = WavEncoder.encode(ShortArray(200), 44_100, 2)
        assertEquals(2, le16(wav, 22))
        assertEquals(44_100, le32(wav, 24))
        assertEquals(44_100 * 2 * 2, le32(wav, 28))
        assertEquals(4, le16(wav, 32))
    }
}
