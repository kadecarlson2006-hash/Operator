package com.operator.app.backend

import com.operator.app.audio.SpeechPlaybackResult
import com.operator.app.audio.StreamingSpeechPlayer
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechControllerTest {
    private class BytesStream(private val bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) : SpeechAudioStream {
        override val sampleRateHz = 24_000
        override val channels = 1
        private var sent = false
        override suspend fun read(buffer: ByteArray): Int {
            if (sent) return -1
            bytes.copyInto(buffer)
            sent = true
            return bytes.size
        }
        override fun close() = Unit
    }

    private class FakeBackend(private val gate: CompletableDeferred<Unit>? = null) : OperatorSpeechBackend {
        override val speechConfigured = true
        var requested: String? = null
        override suspend fun openSpeech(text: String): SpeechAudioStream {
            requested = text
            gate?.await()
            return BytesStream()
        }
    }

    private class FakePlayer : StreamingSpeechPlayer {
        override suspend fun play(
            stream: SpeechAudioStream,
            selection: RouteSelection,
            onPlaybackStarted: () -> Unit,
        ): SpeechPlaybackResult {
            val buffer = ByteArray(16)
            val read = stream.read(buffer)
            onPlaybackStarted()
            return SpeechPlaybackResult(read.toLong(), null, "fake")
        }
    }

    @Test
    fun `streams an answer and records first-audio and completion latency`() = runTest {
        var now = 1_000L
        val backend = FakeBackend()
        val controller = SpeechController(backend, FakePlayer(), this, { RouteSelection.Default }, { now })
        assertFalse(controller.speak("  "))

        assertTrue(controller.speak("  Good evening.  "))
        now = 1_180L
        advanceUntilIdle()

        assertEquals("Good evening.", backend.requested)
        assertEquals(SpeechPhase.IDLE, controller.state.value.phase)
        assertEquals(180L, controller.state.value.firstAudioMillis)
        assertEquals(180L, controller.state.value.totalMillis)
        assertEquals(4L, controller.state.value.bytesPlayed)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `stop speaking cancels a pending stream and allows another request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = SpeechController(FakeBackend(gate), FakePlayer(), this, { RouteSelection.Default })
        assertTrue(controller.speak("First"))
        runCurrent()
        assertTrue(controller.state.value.busy)

        controller.cancel()
        runCurrent()
        assertFalse(controller.state.value.busy)
        assertTrue(controller.state.value.cancelled)
        assertTrue(controller.speak("Second"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Second", controller.state.value.text)
    }
}
