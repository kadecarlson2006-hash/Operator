package com.operator.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import com.operator.core.audio.AudioException
import kotlin.math.max

/**
 * Opens an [AudioRecord] for Operator's PCM format, with every failure turned into an
 * [AudioException] that says what actually went wrong.
 *
 * Shared by [AndroidAudioRecorder], which captures a clip of known length for the Milestone 1
 * loopback, and [ContinuousMicrophone], which streams frames for Milestone 8. Their read loops
 * differ — one is bounded, the other is not — but opening the hardware is identical, and a
 * divergence here would mean the two paths could behave differently on the same device.
 *
 * The caller owns the returned recorder and must `stop()` and `release()` it.
 */
@SuppressLint("MissingPermission") // callers verify RECORD_AUDIO at runtime before calling
internal fun openPcmRecord(audioSource: Int): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(
        AudioFormatSpec.SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) throw AudioException("AudioRecord.getMinBufferSize failed ($minBuffer)")

    val record = try {
        AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioFormatSpec.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer * 2, MIN_BUFFER_BYTES))
            .build()
    } catch (e: SecurityException) {
        throw AudioException("Microphone permission denied by the system", e)
    } catch (e: IllegalArgumentException) {
        throw AudioException("Unsupported audio format: ${e.message}", e)
    } catch (e: UnsupportedOperationException) {
        throw AudioException("AudioRecord could not be created: ${e.message}", e)
    }

    if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw AudioException("AudioRecord failed to initialize (state=${record.state})")
    }
    return record
}

/** Starts capture, or explains why the microphone would not open. */
@SuppressLint("MissingPermission") // as above
internal fun AudioRecord.startCaptureOrThrow() {
    startRecording()
    if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        throw AudioException("Microphone is busy or unavailable (recordingState=$recordingState)")
    }
}

/**
 * Asks the platform for [device] and reports whether it agreed, as a suffix for the route note.
 * A rejection is not fatal: the capture still runs, and `routedDevice` remains the ground truth.
 */
internal fun AudioRecord.preferDevice(device: android.media.AudioDeviceInfo?): String =
    if (device != null && !setPreferredDevice(device)) " (setPreferredDevice rejected)" else ""

/** Enough headroom that a slow consumer does not drop frames on typical devices. */
private const val MIN_BUFFER_BYTES = 16_384
