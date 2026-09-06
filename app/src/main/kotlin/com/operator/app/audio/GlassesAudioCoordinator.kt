package com.operator.app.audio

import com.operator.app.backend.SpeakingSession
import com.operator.app.transcription.ListeningSession
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the half-duplex boundary between hearing and speaking over a headset link.
 *
 * Bluetooth communication routes are shared resources. Pausing capture while Operator speaks
 * prevents its own voice from being transcribed and prevents two owners from racing
 * AudioManager.setCommunicationDevice. Listening resumes only when it was active beforehand and
 * the operator is still allowed to process audio.
 */
class GlassesAudioCoordinator(
    private val listening: ListeningSession,
    private val speaking: SpeakingSession,
    private val scope: CoroutineScope,
    private val selectionSupplier: () -> RouteSelection,
    private val audioAllowed: () -> Boolean,
) {
    private var resumeGeneration = 0L
    private var resumeJob: Job? = null

    @Synchronized
    fun startListening(): Boolean {
        cancelResume()
        if (!audioAllowed() || speaking.state.value.busy) return false
        listening.start(selectionSupplier())
        return listening.listening
    }

    @Synchronized
    fun stopListening() {
        cancelResume()
        listening.stop()
    }

    @Synchronized
    fun speak(text: String): Boolean {
        if (!audioAllowed() || speaking.state.value.busy) return false
        val shouldResume = listening.listening
        if (shouldResume) listening.stop()

        val started = speaking.speak(text)
        if (!started) {
            if (shouldResume && audioAllowed()) listening.start(selectionSupplier())
            return false
        }
        if (shouldResume) scheduleResume()
        return true
    }

    fun stopSpeaking() = speaking.cancel()

    @Synchronized
    fun mute() {
        cancelResume()
        listening.stop()
        speaking.cancel()
    }

    private fun scheduleResume() {
        val token = ++resumeGeneration
        resumeJob?.cancel()
        resumeJob = scope.launch {
            speaking.state.first { !it.busy }
            synchronized(this@GlassesAudioCoordinator) {
                if (token == resumeGeneration && audioAllowed()) listening.start(selectionSupplier())
            }
        }
    }

    private fun cancelResume() {
        resumeGeneration++
        resumeJob?.cancel()
        resumeJob = null
    }
}
