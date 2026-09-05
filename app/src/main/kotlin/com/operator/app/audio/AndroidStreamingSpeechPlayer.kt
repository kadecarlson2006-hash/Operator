package com.operator.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.operator.app.backend.SpeechAudioStream
import com.operator.core.audio.AudioException
import com.operator.core.audio.AudioRoute
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

data class SpeechPlaybackResult(
    val bytesPlayed: Long,
    val route: AudioRoute?,
    val note: String,
)

interface StreamingSpeechPlayer {
    suspend fun play(
        stream: SpeechAudioStream,
        selection: RouteSelection,
        onPlaybackStarted: () -> Unit,
    ): SpeechPlaybackResult
}

/** Writes backend-streamed signed 16-bit mono PCM directly to AudioTrack. */
class AndroidStreamingSpeechPlayer(
    context: Context,
    private val monitor: AudioRouteMonitor,
    private val link: CommunicationLink,
) : StreamingSpeechPlayer {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override suspend fun play(
        stream: SpeechAudioStream,
        selection: RouteSelection,
        onPlaybackStarted: () -> Unit,
    ): SpeechPlaybackResult = withContext(Dispatchers.IO) {
        require(stream.channels == 1) { "speech playback supports mono only" }
        val output = selection.output
        when {
            output == null -> render(stream, null, "USAGE_MEDIA, system default routing", mediaAttributes(), onPlaybackStarted)
            output.usesCommunicationLink -> link.use(output) { confirmed ->
                render(
                    stream,
                    output,
                    "USAGE_VOICE_COMMUNICATION via ${output.summary}" + if (confirmed) "" else " (link not confirmed)",
                    voiceAttributes(),
                    onPlaybackStarted,
                )
            }
            else -> render(stream, output, "USAGE_MEDIA, preferred ${output.summary}", mediaAttributes(), onPlaybackStarted)
        }
    }

    private suspend fun render(
        stream: SpeechAudioStream,
        preferred: AudioRoute?,
        baseNote: String,
        attributes: AudioAttributes,
        onPlaybackStarted: () -> Unit,
    ): SpeechPlaybackResult = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(
            stream.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw AudioException("AudioTrack.getMinBufferSize failed ($minBuffer)")
        val preferredInfo: AudioDeviceInfo? = preferred?.let {
            monitor.findOutput(it) ?: throw AudioException("Selected output ${it.summary} is no longer available")
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        val focus = audioManager.requestAudioFocus(focusRequest)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "Audio focus not granted ($focus)")

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(stream.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer * 2, STREAM_BUFFER_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            throw AudioException("AudioTrack could not be created: ${e.message}", e)
        }

        var note = baseNote
        var routed: AudioRoute? = null
        var totalBytes = 0L
        var started = false
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw AudioException("AudioTrack failed to initialize")
            if (preferredInfo != null && !track.setPreferredDevice(preferredInfo)) note += " (setPreferredDevice rejected)"
            track.play()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                ensureActive()
                val read = stream.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                var offset = 0
                while (offset < read) {
                    ensureActive()
                    val written = track.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) throw AudioException("AudioTrack.write error $written")
                    offset += written
                    totalBytes += written
                }
                if (!started) {
                    started = true
                    onPlaybackStarted()
                }
                if (routed == null) {
                    routed = track.routedDevice?.let(AudioRouteMapper::toRoute)
                    if (routed != null) monitor.log("Speech routed to ${routed.summary} [$note]")
                }
            }
            if (!started) throw AudioException("Speech backend returned no audio")
            awaitPlaybackDrain(track, totalBytes, stream.sampleRateHz, stream.channels)
            if (routed == null) {
                routed = track.routedDevice?.let(AudioRouteMapper::toRoute)
                monitor.log("Speech route unavailable after playback [$note]")
            }
        } finally {
            stream.close()
            runCatching { track.pause(); track.flush(); track.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
            track.release()
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        SpeechPlaybackResult(totalBytes, routed, note)
    }

    private suspend fun awaitPlaybackDrain(track: AudioTrack, totalBytes: Long, sampleRateHz: Int, channels: Int) {
        val totalFrames = totalBytes / (PCM_BYTES_PER_SAMPLE * channels)
        val remainingMillis = totalFrames * 1_000L / sampleRateHz
        val deadline = System.currentTimeMillis() + remainingMillis + DRAIN_GRACE_MILLIS
        while (track.playbackHeadPosition.toLong() < totalFrames && System.currentTimeMillis() < deadline) {
            ensureActive()
            delay(DRAIN_POLL_MILLIS)
        }
    }

    private fun mediaAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun voiceAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private companion object {
        const val TAG = "StreamingSpeechPlayer"
        const val STREAM_BUFFER_BYTES = 8_192
        const val PCM_BYTES_PER_SAMPLE = 2
        const val DRAIN_GRACE_MILLIS = 1_500L
        const val DRAIN_POLL_MILLIS = 20L
    }
}
