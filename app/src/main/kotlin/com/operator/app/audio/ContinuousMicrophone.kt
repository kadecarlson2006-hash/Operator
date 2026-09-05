package com.operator.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.operator.app.permissions.MicrophonePermission
import com.operator.core.audio.AudioException
import com.operator.core.audio.AudioRoute
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * An open microphone that emits fixed-size PCM-16 frames until the collector stops (Milestone 8).
 *
 * This is separate from [AndroidAudioRecorder], which records a clip of known length for the
 * Milestone 1 loopback test. The loop here is genuinely different: unbounded, frame at a time, so
 * voice-activity detection can decide what to keep. Nothing is buffered beyond the frame in
 * flight — the caller's segmenter owns any retention policy, and cancelling the collector stops
 * the hardware.
 *
 * Routing follows the same policy as [AndroidAudioRecorder]: a Bluetooth input is raised through
 * [CommunicationLink] with VOICE_COMMUNICATION, anything else uses MIC with setPreferredDevice.
 */
class ContinuousMicrophone(
    private val context: Context,
    private val monitor: AudioRouteMonitor,
    private val link: CommunicationLink,
) : MicrophoneSource {
    /** The device Android actually captured from on the current run, once it reports one. */
    @Volatile
    override var actualRoute: AudioRoute? = null
        private set

    override fun listen(selection: RouteSelection): Flow<ShortArray> = flow {
        if (!MicrophonePermission(context).refresh()) throw AudioException("Microphone permission not granted")
        val input = selection.input
        when {
            input == null -> emitFrames(MediaRecorder.AudioSource.MIC, null, "MIC, system default routing")
            input.usesCommunicationLink -> {
                val sink = findCommunicationSink(input)
                    ?: throw AudioException("No communication output matches ${input.summary}; is the headset connected?")
                link.use(sink) { confirmed ->
                    emitFrames(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        input,
                        "VOICE_COMMUNICATION via ${sink.summary}" + if (confirmed) "" else " (link not confirmed)",
                    )
                }
            }
            else -> emitFrames(MediaRecorder.AudioSource.MIC, input, "MIC, preferred ${input.summary}")
        }
    }.flowOn(Dispatchers.IO)

    private fun findCommunicationSink(input: AudioRoute): AudioRoute? {
        val candidates = monitor.routes.value.communicationDevices
        return candidates.firstOrNull { it.address != null && it.address == input.address }
            ?: candidates.firstOrNull { it.kind == input.kind }
    }

    @SuppressLint("MissingPermission") // verified at runtime above
    private suspend fun FlowCollector<ShortArray>.emitFrames(
        audioSource: Int,
        preferred: AudioRoute?,
        note: String,
    ) {
        val preferredInfo: AudioDeviceInfo? = preferred?.let {
            monitor.findInput(it) ?: throw AudioException("Selected input ${it.summary} is no longer available")
        }

        val record = openPcmRecord(audioSource)
        val fullNote = note + record.preferDevice(preferredInfo)

        actualRoute = null
        try {
            record.startCaptureOrThrow()
            monitor.log("Listening [$fullNote]")
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = ShortArray(AudioFormatSpec.CHUNK_FRAMES)
                val read = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read < 0) throw AudioException("AudioRecord.read error $read")
                if (read == 0) continue
                if (actualRoute == null) {
                    actualRoute = record.routedDevice?.let(AudioRouteMapper::toRoute)
                    actualRoute?.let { monitor.log("Listening routed to ${it.summary} [$fullNote]") }
                }
                emit(if (read == frame.size) frame else frame.copyOf(read))
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w(TAG, "stop() failed", it) }
            record.release()
            monitor.log("Stopped listening")
        }
    }

    private companion object {
        const val TAG = "ContinuousMicrophone"
    }
}
