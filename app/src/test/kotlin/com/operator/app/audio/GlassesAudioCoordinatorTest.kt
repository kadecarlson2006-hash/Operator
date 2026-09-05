package com.operator.app.audio

import com.operator.app.backend.SpeakingSession
import com.operator.app.backend.SpeechPhase
import com.operator.app.backend.SpeechState
import com.operator.app.transcription.ListeningSession
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesAudioCoordinatorTest {
    private class FakeListening : ListeningSession {
        override var listening = false
        var starts = 0
        var stops = 0
        override fun start(selection: RouteSelection) { listening = true; starts++ }
        override fun stop() { listening = false; stops++ }
    }

    private class FakeSpeaking : SpeakingSession {
        private val mutableState = MutableStateFlow(SpeechState())
        override val state: StateFlow<SpeechState> = mutableState
        override fun speak(text: String): Boolean {
            if (text.isBlank()) return false
            mutableState.value = SpeechState(phase = SpeechPhase.PLAYING, text = text)
            return true
        }
        override fun cancel() { mutableState.value = SpeechState(cancelled = true) }
        fun complete() { mutableState.value = SpeechState() }
    }

    @Test
    fun `speaking pauses an active microphone and resumes after playback`() = runTest {
        val listening = FakeListening()
        val speaking = FakeSpeaking()
        val coordinator = GlassesAudioCoordinator(listening, speaking, this, { RouteSelection.Default }, { true })

        assertTrue(coordinator.startListening())
        assertTrue(coordinator.speak("Good evening."))
        assertFalse(listening.listening)
        runCurrent()
        assertFalse(listening.listening)

        speaking.complete()
        advanceUntilIdle()
        assertTrue(listening.listening)
        assertEquals(2, listening.starts)
    }

    @Test
    fun `mute revokes pending microphone resume`() = runTest {
        val listening = FakeListening()
        val speaking = FakeSpeaking()
        var allowed = true
        val coordinator = GlassesAudioCoordinator(listening, speaking, this, { RouteSelection.Default }, { allowed })

        coordinator.startListening()
        coordinator.speak("Stop now.")
        allowed = false
        coordinator.mute()
        advanceUntilIdle()

        assertFalse(listening.listening)
        assertTrue(speaking.state.value.cancelled)
    }
}
