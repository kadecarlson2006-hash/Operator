package com.operator.app.decision

import com.operator.core.transcription.RollingTranscript
import com.operator.core.transcription.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AmbientState(
    /** Off by default: see [AmbientDecider]. */
    val enabled: Boolean = false,
    val waiting: Boolean = false,
    val considered: Int = 0,
)

/**
 * Makes Operator active: it decides whether to speak on its own, rather than when a button is
 * pressed (Milestone 13).
 *
 * Deliberately off by default. The confidence and relevance floors it depends on were chosen to
 * be testable rather than measured, and no model has yet judged a real conversation
 * (docs/RISKS_AND_UNKNOWNS.md items 43-44). Switching this on before those numbers have been
 * checked against a real room is how an assistant becomes the nuisance the whole policy exists to
 * prevent, so turning it on is a deliberate act.
 *
 * It waits for a lull rather than firing on every line. Someone mid-sentence has not finished the
 * thought, and interrupting a thought is worse than being slow to it; the pause also means the
 * transcript the model sees is a complete exchange rather than half of one. The backend's own
 * budget is what actually bounds spending — this only avoids obviously wasted calls.
 */
class AmbientDecider(
    private val transcript: RollingTranscript,
    private val scope: CoroutineScope,
    /** Asks the decision stage. Returns false when it declined to start. */
    private val decide: () -> Boolean,
    /** Whether Operator is in a state where deciding makes sense at all. */
    private val audioAllowed: () -> Boolean,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
) {
    private val _state = MutableStateFlow(AmbientState())
    val state: StateFlow<AmbientState> = _state.asStateFlow()

    private var job: Job? = null

    val enabled: Boolean get() = job?.isActive == true

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        if (on) start() else stop()
    }

    private fun start() {
        _state.update { it.copy(enabled = true) }
        job = scope.launch {
            // collectLatest restarts the wait whenever a new line lands, so the delay below is a
            // debounce: it only elapses once the room has actually gone quiet.
            transcript.state.collectLatest { lines ->
                if (lines.isEmpty()) {
                    _state.update { it.copy(waiting = false) }
                    return@collectLatest
                }
                // Nothing to react to if the last thing said was Operator's own.
                if (lines.last().speaker == Speaker.OPERATOR) {
                    _state.update { it.copy(waiting = false) }
                    return@collectLatest
                }
                _state.update { it.copy(waiting = true) }
                delay(settleMillis)
                _state.update { it.copy(waiting = false) }
                if (!audioAllowed()) return@collectLatest
                if (decide()) _state.update { it.copy(considered = it.considered + 1) }
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(enabled = false, waiting = false) }
    }

    private companion object {
        /** Long enough to be a pause in speech rather than a gap between words. */
        const val DEFAULT_SETTLE_MILLIS = 2_500L
    }
}
