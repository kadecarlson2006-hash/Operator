package com.operator.app.backend

import com.operator.core.diagnostics.LatencyCheckpoint
import com.operator.core.diagnostics.LatencyTimeline
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
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
    /** Memories the backend used to answer, with the reason each was chosen (Milestone 7). */
    val memoriesUsed: List<UsedMemory> = emptyList(),
    /** How many transcript lines the backend used for this answer (Milestone 11). */
    val transcriptLines: Int = 0,
    /** Set when the question was an explicit "remember that…" command. */
    val memoryWritten: MemoryWritten? = null,
    val retrievalMillis: Long? = null,
    val semanticRetrieval: Boolean = false,
    val retrievalNote: String? = null,
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
    /** Reads the live Operator mode and wit, so the backend applies the right memory scopes. */
    private val stateSupplier: () -> Pair<OperatorMode, WitLevel> = { OperatorMode.ACTIVE to WitLevel.NORMAL },
    /**
     * The rolling conversation to answer with (Milestone 11). Read at send time rather than held,
     * so a question always sees the window as it is now, not as it was when the panel opened.
     */
    private val transcriptSupplier: () -> List<String> = { emptyList() },
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
                val (mode, wit) = stateSupplier()
                val response = client.ask(
                    current.prompt.trim(),
                    mode = mode.name,
                    wit = wit.name,
                    transcript = transcriptSupplier(),
                )
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
                        memoriesUsed = response.memoriesUsed,
                        transcriptLines = response.transcriptLines,
                        memoryWritten = response.memoryWritten,
                        retrievalMillis = response.retrievalMillis,
                        semanticRetrieval = response.semanticRetrieval,
                        retrievalNote = response.retrievalNote,
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
