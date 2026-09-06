package com.operator.app.transcription

import com.operator.app.audio.MicrophoneSource
import com.operator.app.backend.AskResponse
import com.operator.app.backend.BackendException
import com.operator.app.backend.OperatorBackend
import com.operator.app.backend.TranscribeResponse
import com.operator.core.audio.AudioRoute
import com.operator.core.audio.RouteSelection
import com.operator.core.transcription.RollingTranscript
import com.operator.core.transcription.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class ListenControllerTest {

    private companion object {
        const val FRAME = 320 // 20 ms at 16 kHz
    }

    private fun silence() = ShortArray(FRAME)

    private fun speech(amplitude: Float = 0.4f) =
        ShortArray(FRAME) { i -> (sin(2 * PI * 300 * i / 16000.0) * amplitude * Short.MAX_VALUE).toInt().toShort() }

    /** A microphone whose frames the test pushes by hand. */
    private class FakeMic(override val actualRoute: AudioRoute? = null) : MicrophoneSource {
        val frames = Channel<ShortArray>(Channel.UNLIMITED)
        var listenCalls = 0
        var lastSelection: RouteSelection? = null
        override fun listen(selection: RouteSelection): Flow<ShortArray> {
            listenCalls++
            lastSelection = selection
            return frames.consumeAsFlow()
        }
    }

    private class ExplodingMic(private val error: Exception) : MicrophoneSource {
        override val actualRoute: AudioRoute? = null
        override fun listen(selection: RouteSelection): Flow<ShortArray> = flow { throw error }
    }

    private class FakeBackend(
        private val text: String = "lights down",
        private val failure: Exception? = null,
        override val configured: Boolean = true,
    ) : OperatorBackend {
        val uploads = mutableListOf<ByteArray>()
        var lastSampleRate: Int? = null
        override suspend fun ask(
            prompt: String,
            tier: String?,
            sessionId: String?,
            mode: String?,
            wit: String?,
            transcript: List<String>,
        ): AskResponse = throw UnsupportedOperationException("this fake only transcribes")

        override suspend fun transcribe(pcm: ByteArray, sampleRateHz: Int, channels: Int, sessionId: String?): TranscribeResponse {
            uploads += pcm
            lastSampleRate = sampleRateHz
            failure?.let { throw it }
            return TranscribeResponse(text = text, provider = "fake", audioSeconds = 0.5, latencyMillis = 30, empty = text.isBlank())
        }
    }

    /** Feeds enough silence for the detector to learn the room, then one utterance and a pause. */
    private suspend fun FakeMic.speakOnce(speechFrames: Int = 30) {
        repeat(20) { frames.send(ShortArray(FRAME)) }
        repeat(speechFrames) { frames.send(ShortArray(FRAME) { i -> (sin(2 * PI * 300 * i / 16000.0) * 0.4 * Short.MAX_VALUE).toInt().toShort() }) }
        repeat(20) { frames.send(ShortArray(FRAME)) }
    }

    @Test
    fun `an utterance is uploaded and its transcript appears`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend()
        val controller = ListenController(mic, backend, this)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        assertEquals(1, backend.uploads.size)
        assertEquals(16_000, backend.lastSampleRate)
        val state = controller.state.value
        assertEquals(1, state.transcripts.size)
        assertEquals("lights down", state.transcripts.first().text)
        assertFalse(state.transcripts.first().empty)
        assertEquals(1, state.utterances)
        assertNotNull(state.transcripts.first().roundTripMillis)

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `silence alone is never uploaded`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend()
        val controller = ListenController(mic, backend, this)

        controller.start(RouteSelection.Default)
        repeat(200) { mic.frames.send(silence()) }
        advanceUntilIdle()

        assertEquals(0, backend.uploads.size)
        assertEquals(0, controller.state.value.utterances)
        assertTrue(controller.state.value.transcripts.isEmpty())

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `stopping closes the microphone and discards speech in progress`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend()
        val controller = ListenController(mic, backend, this)

        controller.start(RouteSelection.Default)
        repeat(20) { mic.frames.send(silence()) }
        repeat(10) { mic.frames.send(speech()) } // still talking
        advanceUntilIdle()
        assertTrue(controller.listening)

        controller.stop()
        advanceUntilIdle()

        assertFalse(controller.listening)
        assertEquals(ListenStatus.IDLE, controller.state.value.status)
        assertEquals(0f, controller.state.value.level, 0.0001f)
        assertEquals("audio cut short by STOP must not be uploaded afterwards", 0, backend.uploads.size)
        mic.frames.close()
    }

    @Test
    fun `an empty transcript is shown as such rather than as an error`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend(text = "")
        val controller = ListenController(mic, backend, this)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        val line = controller.state.value.transcripts.single()
        assertTrue(line.empty)
        assertNull(controller.state.value.error)

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `a backend failure is reported and listening continues`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend(failure = BackendException("backend unreachable"))
        val controller = ListenController(mic, backend, this)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        assertEquals("backend unreachable", controller.state.value.error)
        assertTrue(controller.state.value.transcripts.isEmpty())
        assertTrue("a failed upload must not stop the microphone", controller.listening)

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `starting without a configured backend fails fast and opens no microphone`() = runTest {
        val mic = FakeMic()
        val controller = ListenController(mic, FakeBackend(configured = false), this)

        controller.start(RouteSelection.Default)
        advanceUntilIdle()

        assertEquals(ListenStatus.ERROR, controller.state.value.status)
        assertFalse(controller.listening)
        assertEquals("the microphone must not open with nowhere to send audio", 0, mic.listenCalls)
    }

    @Test
    fun `a microphone failure surfaces as an error rather than a silent stop`() = runTest {
        val controller = ListenController(ExplodingMic(IllegalStateException("mic busy")), FakeBackend(), this)

        controller.start(RouteSelection.Default)
        advanceUntilIdle()

        assertEquals(ListenStatus.ERROR, controller.state.value.status)
        assertEquals("mic busy", controller.state.value.error)
        assertFalse(controller.state.value.listening)
    }

    @Test
    fun `start is ignored while already listening`() = runTest {
        val mic = FakeMic()
        val controller = ListenController(mic, FakeBackend(), this)

        controller.start(RouteSelection.Default)
        advanceUntilIdle()
        controller.start(RouteSelection.Default)
        advanceUntilIdle()

        assertEquals(1, mic.listenCalls)

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `transcripts are bounded and can be cleared`() = runTest {
        val mic = FakeMic()
        val backend = FakeBackend()
        val controller = ListenController(mic, backend, this, maxTranscripts = 2)

        controller.start(RouteSelection.Default)
        repeat(3) { mic.speakOnce() }
        advanceUntilIdle()

        assertEquals(2, controller.state.value.transcripts.size)
        assertEquals(3, controller.state.value.utterances)

        controller.clearTranscripts()
        assertTrue(controller.state.value.transcripts.isEmpty())
        assertEquals(0, controller.state.value.utterances)

        controller.stop()
        mic.frames.close()
    }

    // --- Milestone 11: the rolling window ---

    @Test
    fun `a transcript joins the rolling window`() = runTest {
        val mic = FakeMic()
        val window = RollingTranscript()
        val controller = ListenController(mic, FakeBackend(text = "meeting moved to Thursday"), this, window)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        assertEquals(listOf("meeting moved to Thursday"), window.entries().map { it.text })
        assertEquals(
            "the provider gives text, not diarisation, so the speaker is not known",
            Speaker.UNKNOWN,
            window.entries().single().speaker,
        )

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `an empty transcript is not a turn in the window`() = runTest {
        val mic = FakeMic()
        val window = RollingTranscript()
        val controller = ListenController(mic, FakeBackend(text = "  "), this, window)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        assertTrue("silence heard as nothing is not conversation", window.entries().isEmpty())

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `a failed upload leaves the window untouched`() = runTest {
        val mic = FakeMic()
        val window = RollingTranscript()
        val controller = ListenController(mic, FakeBackend(failure = BackendException("no route to host")), this, window)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()

        assertTrue("nothing was heard, so nothing should be remembered", window.entries().isEmpty())

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `clearing drops the rolling window too, not just the panel`() = runTest {
        val mic = FakeMic()
        val window = RollingTranscript()
        val controller = ListenController(mic, FakeBackend(), this, window)

        controller.start(RouteSelection.Default)
        mic.speakOnce()
        advanceUntilIdle()
        assertEquals(1, window.entries().size)

        controller.clearTranscripts()

        assertTrue("CLEAR must mean cleared, or prompts keep seeing it", window.entries().isEmpty())
        assertTrue(controller.state.value.transcripts.isEmpty())

        controller.stop()
        mic.frames.close()
    }

    @Test
    fun `the uploaded bytes are little-endian pcm of the captured samples`() {
        val samples = shortArrayOf(0, 1, -1, 258, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = samples.toLittleEndianBytes()
        assertEquals(samples.size * 2, bytes.size)
        val decoded = ShortArray(samples.size) { i ->
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        assertTrue(samples.contentEquals(decoded))
    }
}
