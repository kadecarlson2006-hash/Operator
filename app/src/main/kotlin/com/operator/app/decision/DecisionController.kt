package com.operator.app.decision

import android.util.Log
import com.operator.app.backend.BackendException
import com.operator.app.backend.DecideResponse
import com.operator.app.backend.OperatorBackend
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import com.operator.core.transcription.RollingTranscript
import com.operator.core.transcription.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The last thing the decision stage decided, for the panel. */
data class DecisionState(
    val inFlight: Boolean = false,
    val shouldSpeak: Boolean = false,
    val category: String? = null,
    /** A short diagnostic label, never the model's reasoning. */
    val reasonCode: String? = null,
    val spoken: String? = null,
    val confidence: Float = 0f,
    val relevance: Float = 0f,
    /** True when the local rules refused before any model was consulted — a free decision. */
    val gatedLocally: Boolean = false,
    val modelCalled: Boolean = false,
    val model: String? = null,
    val suppressedAfterModel: Boolean = false,
    val latencyMillis: Long = 0,
    /** True when live search backed the answer rather than the model's own recollection. */
    val searched: Boolean = false,
    /** Where the time went: memory retrieval, then the model call including any search. */
    val retrievalMillis: Long = 0,
    val modelMillis: Long = 0,
    val decisions: Int = 0,
    val spokenCount: Int = 0,
    val error: String? = null,
    /** Milestone 14: the trigger that produced [spoken], so a verdict can be attributed. */
    val trigger: String? = null,
    /** Set once a verdict has been sent about the current comment, so it cannot be sent twice. */
    val feedbackSent: String? = null,
)

/**
 * Asks the backend whether Operator should speak, and speaks it if so (Milestone 12).
 *
 * The controller does not decide anything itself. It gathers what the decision needs — the
 * rolling window, the mode and wit, whether the user is muted, and what Operator has said
 * recently so it does not repeat itself — and then does what it is told. Silence comes back far
 * more often than speech, and is displayed rather than treated as a failure.
 */
class DecisionController(
    private val backend: OperatorBackend,
    private val transcript: RollingTranscript,
    private val scope: CoroutineScope,
    /** Speaks the decision. Returns false when speech was refused (muted, busy, unconfigured). */
    private val speak: (String) -> Boolean,
    private val stateSupplier: () -> Triple<OperatorMode, WitLevel, Boolean>,
    private val sessionId: String? = null,
) {
    private val _state = MutableStateFlow(DecisionState())
    val state: StateFlow<DecisionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Returns false when a decision is already in flight or the backend is not configured. */
    fun request(trigger: String): Boolean {
        if (job?.isActive == true) return false
        if (!backend.configured) {
            _state.update { it.copy(error = "No backend URL configured. Set OPERATOR_BACKEND_URL in local.properties.") }
            return false
        }
        val (mode, wit, muted) = stateSupplier()
        val window = transcript.entries()

        _state.update { it.copy(inFlight = true, error = null) }
        job = scope.launch {
            try {
                val decision = backend.decide(
                    trigger = trigger,
                    transcript = window.map { "${it.speaker.label}: ${it.text}" },
                    mode = mode.name,
                    wit = wit.name,
                    // Only Operator's own lines, so the model is told what not to repeat.
                    recentComments = window.filter { it.speaker == Speaker.OPERATOR }.map { it.text },
                    muted = muted,
                    sessionId = sessionId,
                )
                apply(decision, trigger)
            } catch (e: CancellationException) {
                _state.update { it.copy(inFlight = false) }
                throw e
            } catch (e: BackendException) {
                Log.w(TAG, "Decision failed")
                _state.update { it.copy(inFlight = false, error = e.message) }
            }
        }
        return true
    }

    fun clear() {
        job?.cancel()
        job = null
        _state.value = DecisionState()
    }

    /**
     * Tells the backend what the user thought of the comment currently on screen (Milestone 14).
     *
     * Only what Operator actually said can be judged: a decision that was refused locally never
     * reached the user's ears, and a verdict on it would be about a thing that did not happen.
     * Feedback is fire-and-forget - a failed send leaves the buttons live to try again rather
     * than surfacing an error over a comment the user has already moved past.
     */
    fun sendFeedback(verdict: String) {
        val current = _state.value
        val comment = current.spoken?.takeIf { it.isNotBlank() } ?: return
        if (current.feedbackSent != null) return

        // Optimistic: the tap is acknowledged immediately, because the point of the buttons is
        // that reacting to Operator is cheap.
        _state.update { it.copy(feedbackSent = verdict) }
        scope.launch {
            try {
                backend.sendFeedback(
                    comment = comment,
                    verdict = verdict,
                    trigger = current.trigger ?: "AMBIENT",
                    confidence = current.confidence,
                    relevance = current.relevance,
                    category = current.category,
                    sessionId = sessionId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Feedback not recorded", e)
                // Put the buttons back rather than claiming a verdict was stored that was not.
                _state.update { if (it.spoken == comment) it.copy(feedbackSent = null) else it }
            }
        }
    }

    private fun apply(decision: DecideResponse, trigger: String) {
        val text = decision.response?.takeIf { decision.shouldSpeak && it.isNotBlank() }
        // Speech can still be refused downstream — muted, already speaking, no TTS configured — so
        // a decision to speak is not the same as having spoken.
        val actuallySpoke = text != null && speak(text)
        if (actuallySpoke) transcript.add(text, Speaker.OPERATOR)

        _state.update {
            it.copy(
                inFlight = false,
                shouldSpeak = decision.shouldSpeak,
                category = decision.category,
                reasonCode = decision.reasonCode,
                spoken = if (actuallySpoke) text else null,
                confidence = decision.confidence,
                relevance = decision.relevance,
                gatedLocally = decision.gatedLocally,
                modelCalled = decision.modelCalled,
                model = decision.model,
                suppressedAfterModel = decision.suppressedAfterModel,
                latencyMillis = decision.latencyMillis,
                searched = decision.searched,
                retrievalMillis = decision.retrievalMillis,
                modelMillis = decision.modelMillis,
                trigger = trigger,
                // A new decision is a new thing to judge, so any earlier verdict stops applying.
                feedbackSent = null,
                decisions = it.decisions + 1,
                spokenCount = it.spokenCount + if (actuallySpoke) 1 else 0,
                error = if (text != null && !actuallySpoke) "Decided to speak, but speech was refused." else null,
            )
        }
    }

    private companion object {
        const val TAG = "DecisionController"
    }
}
