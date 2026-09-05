package com.operator.app.backend

import com.operator.core.diagnostics.LatencyCheckpoint
import com.operator.core.diagnostics.LatencyTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Ask Operator panel renders. */
data class AskState(
    val prompt: String = "",
    val inFlight: Boolean = false,
    val answer: String? = null,
    val model: String? = null,
    val tier: String? = null,
    val routingReason: String? = null,
    val promptVersion: String? = null,
    /** End-to-end, measured on the phone: request sent to answer rendered. */
    val roundTripMillis: Long? = null,
    /** Model latency as measured by the backend. */
    val modelLatencyMillis: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: Double? = null,
    val upstreamProvider: String? = null,
    val error: String? = null,
) {
    val canSend: Boolean get() = prompt.isNotBlank() && !inFlight
}

/**
 * Drives one text question at a time (Milestone 6). No audio: this proves the phone → backend →
 * model → phone path and measures it end to end.
 */
class AskOperatorController(
    private val client: OperatorBackend,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state.asStateFlow()

    /** Latency checkpoints of the last exchange, for the diagnostics panel. */
    @Volatile var lastTimeline: LatencyTimeline = LatencyTimeline()
        private set

    private var job: Job? = null

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text) }

    fun clear() {
        job?.cancel()
        job = null
        _state.value = AskState()
        lastTimeline = LatencyTimeline()
    }

    /** Returns false when there is nothing to send or a request is already running. */
    fun send(): Boolean {
        val current = _state.value
        if (!current.canSend) return false
        _state.update { it.copy(inFlight = true, error = null, answer = null) }
        val startedAt = clock()
        lastTimeline = LatencyTimeline().mark(LatencyCheckpoint.AI_REQUEST_STARTED, startedAt)
        job = scope.launch {
            try {
                val response = client.ask(current.prompt.trim())
                val finishedAt = clock()
                lastTimeline = lastTimeline.mark(LatencyCheckpoint.AI_FIRST_TOKEN, finishedAt)
                _state.update {
                    it.copy(
                        inFlight = false,
                        answer = response.text,
                        model = response.model,
                        tier = response.tier,
                        routingReason = response.routingReason,
                        promptVersion = response.promptVersion,
                        roundTripMillis = finishedAt - startedAt,
                        modelLatencyMillis = response.latencyMillis,
                        inputTokens = response.inputTokens,
                        outputTokens = response.outputTokens,
                        costUsd = response.costUsd,
                        upstreamProvider = response.upstreamProvider,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(inFlight = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(inFlight = false, error = e.message ?: e::class.simpleName ?: "unknown error") }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(inFlight = false) }
    }
}
