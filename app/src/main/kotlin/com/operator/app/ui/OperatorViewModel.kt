package com.operator.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.operator.app.BuildConfig
import com.operator.app.audio.AudioRoutes
import com.operator.app.backend.AskState
import com.operator.app.backend.SpeechState
import com.operator.app.bluetooth.BluetoothStatus
import com.operator.app.di.OperatorContainer
import com.operator.app.decision.AmbientState
import com.operator.app.decision.DecisionState
import com.operator.app.transcription.ListenState
import com.operator.app.transcription.TranscriptionService
import com.operator.core.transcription.Speaker
import com.operator.core.transcription.TranscriptEntry
import com.operator.core.audio.AudioLoopbackState
import com.operator.core.audio.AudioRoute
import com.operator.core.diagnostics.RouteEvent
import com.operator.core.glasses.GlassesAction
import com.operator.core.model.OperatorMode
import com.operator.core.model.WitLevel
import com.operator.core.state.OperatorEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OperatorViewModel(private val container: OperatorContainer) : ViewModel() {

    private val lastEvent = MutableStateFlow<String?>(null)

    private data class AudioSection(
        val loopback: AudioLoopbackState,
        val routes: AudioRoutes,
        val micGranted: Boolean,
        val events: List<RouteEvent>,
    )

    private data class BluetoothSection(val status: BluetoothStatus, val granted: Boolean)

    private data class AiSection(
        val ask: AskState,
        val glasses: com.operator.core.glasses.GlassesState,
        val listen: ListenState,
        val transcript: List<TranscriptEntry>,
        val speech: SpeechState,
        val decision: DecisionState,
        val ambient: AmbientState,
    )

    private val audioSection = combine(
        container.loopback.state,
        container.audioRouteMonitor.routes,
        container.microphonePermission.granted,
        container.routeEventLog.events,
    ) { loopback, routes, mic, events -> AudioSection(loopback, routes, mic, events) }

    private val bluetoothSection = combine(
        container.bluetoothStatus.status,
        container.bluetoothPermission.granted,
    ) { status, granted -> BluetoothSection(status, granted) }

    private val aiSection = combine(
        container.ask.state,
        container.glasses.state,
        container.listen.state,
        container.transcript.state,
        combine(
            container.speech.state,
            container.decision.state,
            container.ambient.state,
        ) { speech, decision, ambient -> Triple(speech, decision, ambient) },
    ) { ask, glasses, listen, transcript, voice ->
        AiSection(ask, glasses, listen, transcript, voice.first, voice.second, voice.third)
    }

    val uiState: StateFlow<OperatorUiState> = combine(
        container.stateManager.state,
        audioSection,
        bluetoothSection,
        aiSection,
        lastEvent,
    ) { operator, audio, bt, ai, event ->
        OperatorUiState(
            operator = operator,
            loopback = audio.loopback,
            routes = audio.routes,
            microphonePermissionGranted = audio.micGranted,
            bluetooth = bt.status,
            bluetoothPermissionGranted = bt.granted,
            bluetoothPermissionIsRuntime = container.bluetoothPermission.isRuntimePermission,
            routeEvents = audio.events,
            ask = ai.ask,
            listen = ai.listen,
            transcript = ai.transcript,
            speech = ai.speech,
            decision = ai.decision,
            ambient = ai.ambient,
            glasses = ai.glasses,
            glassesActions = container.glasses.actions,
            lastEvent = event,
            config = container.config,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperatorUiState(config = container.config))

    init {
        viewModelScope.launch {
            container.stateManager.events.collect { event ->
                lastEvent.value = when (event) {
                    OperatorEvent.CommentNowRequested -> "COMMENT NOW received"
                    OperatorEvent.EmergencyMuteEngaged -> "EMERGENCY MUTE engaged"
                    OperatorEvent.MuteReleased -> "Mute released"
                    is OperatorEvent.ModeChanged -> "Mode ${event.from.label} → ${event.to.label}"
                }
                if (event == OperatorEvent.EmergencyMuteEngaged) {
                    container.loopback.cancel()
                    // Muted means neither listening nor speaking; pending auto-resume is revoked.
                    container.glassesAudio.mute()
                    // And it means forgetting what was already heard: leaving the window populated
                    // would keep feeding prompts the conversation the user just muted.
                    container.transcript.clear()
                    // And it means Operator stops deciding on its own, not merely stops speaking.
                    container.ambient.setEnabled(false)
                }
            }
        }
    }

    // --- Operator controls ---
    fun activate() = container.stateManager.activate()
    fun standby() = container.stateManager.standby()
    /**
     * Milestone 12: COMMENT NOW now actually asks. The state manager still refuses while muted or
     * OFF, and the backend's local rules refuse again — a request is an invitation to speak, not
     * an instruction to.
     */
    fun commentNow() {
        if (!container.stateManager.commentNow()) {
            lastEvent.value = "COMMENT NOW ignored (muted or OFF)"
            return
        }
        if (!container.decision.request(trigger = "COMMENT_NOW")) {
            lastEvent.value = "COMMENT NOW ignored (already deciding, or no backend configured)"
        }
    }

    /** Asks whether the conversation so far is worth commenting on, unprompted. */
    fun considerCommenting() {
        if (!container.decision.request(trigger = "AMBIENT")) {
            lastEvent.value = "DECIDE ignored (already deciding, or no backend configured)"
        }
    }

    fun clearDecision() = container.decision.clear()

    /**
     * Milestone 14: tells the backend what the user thought of the comment on screen.
     *
     * Whether a verdict changes anything is the backend's business — the phone reports what
     * happened and does not decide what it means (ADR-045).
     */
    fun sendFeedback(verdict: String) = container.decision.sendFeedback(verdict)

    /**
     * Milestone 13: lets Operator decide on its own when to consider speaking. Muting stops it,
     * and it stays off across restarts — this is not a setting that should quietly persist itself
     * into a room nobody expected it in.
     */
    fun setAmbient(enabled: Boolean) {
        container.ambient.setEnabled(enabled)
        lastEvent.value = if (enabled) "ACTIVE OPERATOR on — deciding on its own" else "ACTIVE OPERATOR off"
    }
    fun toggleMute() = container.stateManager.toggleMute()
    fun setMode(mode: OperatorMode) = container.stateManager.setMode(mode)
    fun setWit(wit: WitLevel) = container.stateManager.setWit(wit)

    // --- Permissions / refresh ---
    fun refreshPermissions() {
        container.microphonePermission.refresh()
        container.bluetoothPermission.refresh()
        container.audioRouteMonitor.refresh()
        container.bluetoothStatus.refresh()
    }

    // --- Audio test (Milestones 1–2) ---
    fun selectInput(route: AudioRoute?) = container.loopback.selectInput(route)
    fun selectOutput(route: AudioRoute?) = container.loopback.selectOutput(route)
    fun recordTest() {
        if (!container.loopback.startRecordTest()) lastEvent.value = "RECORD TEST ignored (busy)"
    }
    fun playTest() {
        if (!container.loopback.startPlayTest()) lastEvent.value = "PLAY TEST ignored (busy or nothing recorded)"
    }
    fun stopAudio() = container.loopback.cancel()
    fun discardClip() = container.loopback.discardClip()
    fun clearRouteLog() = container.routeEventLog.clear()

    // --- Ask Operator (Milestone 6) ---
    fun setAskPrompt(text: String) = container.ask.setPrompt(text)
    fun sendAsk() {
        if (!container.ask.send()) lastEvent.value = "ASK ignored (empty prompt or already in flight)"
    }
    fun clearAsk() = container.ask.clear()
    fun speakAnswer() {
        if (container.stateManager.current.let { it.muted || !it.isProcessing }) {
            lastEvent.value = "SPEAK ignored (muted or OFF)"
            return
        }
        val answer = container.ask.state.value.answer.orEmpty()
        if (container.glassesAudio.speak(answer)) {
            // What Operator says is part of the conversation. Milestone 11 defined Speaker.OPERATOR
            // for exactly this, and without it the model is told "do not repeat yourself" while
            // being shown no record of what it already said.
            container.transcript.add(answer, Speaker.OPERATOR)
        } else {
            lastEvent.value = "SPEAK ignored (no answer, muted, busy, or backend unavailable)"
        }
    }
    fun stopSpeaking() = container.glassesAudio.stopSpeaking()

    // --- Listening (Milestones 8, 10 and 11) ---
    /**
     * Two things have to be true to listen, and they belong to different milestones: the process
     * must be held in the foreground (Milestone 11's TranscriptionService) and the microphone
     * must not be wanted by speech (Milestone 10's coordinator). The service is the entry point
     * and asks the coordinator; if the coordinator refuses, the service stops itself rather than
     * sitting in the foreground with no microphone.
     *
     * Must be called from the foreground: that is the only path Android 14+ allows for starting
     * a microphone service.
     */
    fun startListening(context: Context) = TranscriptionService.start(context)
    fun stopListening(context: Context) = TranscriptionService.stop(context)

    /** Teardown paths that have no Context to hand. Goes through the coordinator, not around it. */
    fun stopListeningNow() = container.glassesAudio.stopListening()


    fun clearTranscripts() = container.listen.clearTranscripts()

    // --- Glasses (Milestone 3) ---
    fun runGlassesAction(action: GlassesAction, activity: Any?) {
        lastEvent.value = "Glasses: ${action.label}"
        action.run(if (action.needsActivity) activity else null)
    }

    companion object {
        fun factory(container: OperatorContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { OperatorViewModel(container) }
        }
    }
}
