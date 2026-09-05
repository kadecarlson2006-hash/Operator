package com.operator.app.transcription

import android.util.Log
import com.operator.app.audio.MicrophoneSource
import com.operator.app.backend.BackendException
import com.operator.app.backend.OperatorBackend
import com.operator.core.audio.PcmClip
import com.operator.core.audio.RouteSelection
import com.operator.core.audio.SpeechSegmenter
import com.operator.core.audio.VoiceActivityDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ListenStatus(val label: String) {
    IDLE("Idle"),
    CALIBRATING("Learning the room"),
    LISTENING("Listening"),
    CAPTURING("Capturing speech"),
    TRANSCRIBING("Transcribing"),
    ERROR("Error"),
}

/** One finished utterance. Held in memory only, and only until the panel is cleared. */
data class TranscriptLine(
    val text: String,
    val empty: Boolean,
    val audioSeconds: Double?,
    /** Backend-reported provider latency. */
    val latencyMillis: Long?,
    /** Measured on the phone: utterance ended to transcript rendered. */
    val roundTripMillis: Long?,
)

data class ListenState(
    val status: ListenStatus = ListenStatus.IDLE,
    val listening: Boolean = false,
    /** Most recent frame level (0..1), for the meter. */
    val level: Float = 0f,
    val transcripts: List<TranscriptLine> = emptyList(),
    val utterances: Int = 0,
    val route: String? = null,
    val error: String? = null,
)

/**
 * The hearing path on the phone (Milestone 8): open microphone → voice-activity detection →
 * one utterance → backend → transcript.
 *
 * Two rules from the brief are structural here rather than incidental:
 *  - Silence never leaves the device. Frames the detector rejects are dropped where they are
 *    read; only a complete utterance is ever uploaded.
 *  - No raw audio is retained. Segments are handed to the uploader and released; transcripts are
 *    a bounded in-memory list that is never written to disk, and the text is not logged.
 */
class ListenController(
    private val microphone: MicrophoneSource,
    private val backend: OperatorBackend,
    private val scope: CoroutineScope,
    private val sessionId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxTranscripts: Int = 10,
) {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private var uploadJob: Job? = null

    /**
     * Utterances waiting to be transcribed. Bounded, and the oldest is dropped under pressure:
     * a backlog of stale speech is worth less than staying responsive, and an unbounded queue
     * would hold raw audio in memory indefinitely.
     */
    private var queue: Channel<Utterance>? = null

    private class Utterance(val clip: PcmClip, val endedAtMillis: Long)

    val listening: Boolean get() = listenJob?.isActive == true

    fun start(selection: RouteSelection) {
        if (listening) return
        if (!backend.configured) {
            _state.update { it.copy(status = ListenStatus.ERROR, error = "No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.") }
            return
        }
        val channel = Channel<Utterance>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        queue = channel
        _state.update { it.copy(status = ListenStatus.CALIBRATING, listening = true, error = null) }

        uploadJob = scope.launch {
            for (utterance in channel) transcribe(utterance)
        }

        listenJob = scope.launch {
            val detector = VoiceActivityDetector()
            val segmenter = SpeechSegmenter(
                sampleRateHz = SAMPLE_RATE_HZ,
                frameSamples = FRAME_SAMPLES,
                detector = detector,
                minSpeechMillis = MIN_SPEECH_MILLIS,
                maxSegmentMillis = MAX_SEGMENT_MILLIS,
            )
            try {
                microphone.listen(selection).collect { frame ->
                    val segment = segmenter.accept(frame)
                    _state.update {
                        it.copy(
                            level = segmenter.level,
                            route = microphone.actualRoute?.summary ?: it.route,
                            status = when {
                                it.status == ListenStatus.TRANSCRIBING -> it.status
                                detector.calibrating -> ListenStatus.CALIBRATING
                                segmenter.capturing -> ListenStatus.CAPTURING
                                else -> ListenStatus.LISTENING
                            },
                        )
                    }
                    if (segment != null) {
                        _state.update { it.copy(utterances = it.utterances + 1) }
                        channel.trySend(Utterance(segment, clock()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Listening stopped", e)
                _state.update { it.copy(status = ListenStatus.ERROR, listening = false, error = e.message ?: e::class.simpleName) }
            } finally {
                // Anything still being spoken when we stop is not uploaded: the user asked us to stop.
                segmenter.reset()
                channel.close()
            }
        }
    }

    /** Stops immediately. In-flight audio is discarded rather than uploaded after the fact. */
    fun stop() {
        listenJob?.cancel()
        listenJob = null
        uploadJob?.cancel()
        uploadJob = null
        queue?.close()
        queue = null
        _state.update {
            it.copy(
                status = if (it.status == ListenStatus.ERROR) it.status else ListenStatus.IDLE,
                listening = false,
                level = 0f,
            )
        }
    }

    /** Drops every transcript held in memory. */
    fun clearTranscripts() = _state.update { it.copy(transcripts = emptyList(), utterances = 0) }

    private suspend fun transcribe(utterance: Utterance) {
        _state.update { it.copy(status = ListenStatus.TRANSCRIBING) }
        try {
            val pcm = utterance.clip.samples.toLittleEndianBytes()
            val response = backend.transcribe(
                pcm = pcm,
                sampleRateHz = utterance.clip.sampleRateHz,
                channels = utterance.clip.channels,
                sessionId = sessionId,
            )
            val line = TranscriptLine(
                text = response.text,
                empty = response.empty || response.text.isBlank(),
                audioSeconds = response.audioSeconds,
                latencyMillis = response.latencyMillis,
                roundTripMillis = clock() - utterance.endedAtMillis,
            )
            _state.update {
                it.copy(
                    transcripts = (it.transcripts + line).takeLast(maxTranscripts),
                    status = if (it.listening) ListenStatus.LISTENING else ListenStatus.IDLE,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            // Deliberately no transcript text in the log: transcript logging is off by default.
            Log.w(TAG, "Transcription failed")
            _state.update {
                it.copy(
                    status = if (it.listening) ListenStatus.LISTENING else ListenStatus.IDLE,
                    error = e.message,
                )
            }
        }
    }

    private companion object {
        const val TAG = "ListenController"
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms
        const val MIN_SPEECH_MILLIS = 350L
        const val MAX_SEGMENT_MILLIS = 20_000L
    }
}

/** PCM-16 little-endian, the layout the backend's /transcribe expects. */
internal fun ShortArray.toLittleEndianBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt()
        out[i * 2] = v.toByte()
        out[i * 2 + 1] = (v shr 8).toByte()
    }
    return out
}
