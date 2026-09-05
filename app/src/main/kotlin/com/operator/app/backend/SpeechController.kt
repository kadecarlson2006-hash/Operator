package com.operator.app.backend

import com.operator.app.audio.SpeechPlaybackResult
import com.operator.app.audio.StreamingSpeechPlayer
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class SpeechPhase { IDLE, CONNECTING, PLAYING, ERROR }

data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val text: String? = null,
    val firstAudioMillis: Long? = null,
    val totalMillis: Long? = null,
    val bytesPlayed: Long = 0,
    val route: String? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = phase == SpeechPhase.CONNECTING || phase == SpeechPhase.PLAYING
}

interface SpeakingSession {
    val state: StateFlow<SpeechState>
    fun speak(text: String): Boolean
    fun cancel()
}

class SpeechController(
    private val backend: OperatorSpeechBackend,
    private val player: StreamingSpeechPlayer,
    private val scope: CoroutineScope,
    private val selectionSupplier: () -> RouteSelection,
    private val clock: () -> Long = System::currentTimeMillis,
) : SpeakingSession {
    private val _state = MutableStateFlow(SpeechState())
    override val state: StateFlow<SpeechState> = _state.asStateFlow()
    private val generation = AtomicLong()
    private var job: Job? = null

    @Synchronized
    override fun speak(text: String): Boolean {
        val spoken = text.trim()
        if (spoken.isEmpty() || _state.value.busy || !backend.speechConfigured) return false
        val requestId = generation.incrementAndGet()
        val startedAt = clock()
        _state.value = SpeechState(phase = SpeechPhase.CONNECTING, text = spoken)
        job = scope.launch {
            try {
                val stream = backend.openSpeech(spoken)
                val result = player.play(stream, selectionSupplier()) {
                    updateIfCurrent(requestId) {
                        it.copy(phase = SpeechPhase.PLAYING, firstAudioMillis = clock() - startedAt)
                    }
                }
                complete(requestId, startedAt, result)
            } catch (e: CancellationException) {
                updateIfCurrent(requestId) { SpeechState(cancelled = true) }
                throw e
            } catch (e: Exception) {
                updateIfCurrent(requestId) {
                    it.copy(phase = SpeechPhase.ERROR, totalMillis = clock() - startedAt, error = e.message ?: "Speech failed")
                }
            }
        }
        return true
    }

    @Synchronized
    override fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _state.value = SpeechState(cancelled = true)
    }

    fun clear() {
        if (!_state.value.busy) _state.value = SpeechState()
    }

    private fun complete(requestId: Long, startedAt: Long, result: SpeechPlaybackResult) {
        updateIfCurrent(requestId) {
            it.copy(
                phase = SpeechPhase.IDLE,
                totalMillis = clock() - startedAt,
                bytesPlayed = result.bytesPlayed,
                route = result.route?.summary,
                cancelled = false,
                error = null,
            )
        }
    }

    private inline fun updateIfCurrent(requestId: Long, transform: (SpeechState) -> SpeechState) {
        synchronized(this) {
            if (generation.get() == requestId) _state.update(transform)
        }
    }
}
