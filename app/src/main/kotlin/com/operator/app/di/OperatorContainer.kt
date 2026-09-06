package com.operator.app.di

import android.app.Application
import com.operator.app.audio.AndroidAudioPlayer
import com.operator.app.audio.AndroidAudioRecorder
import com.operator.app.audio.AudioRouteMonitor
import com.operator.app.audio.AudioSubsystemReporter
import com.operator.app.audio.AndroidStreamingSpeechPlayer
import com.operator.app.backend.AskOperatorController
import com.operator.app.backend.OperatorBackendClient
import com.operator.app.backend.SpeechController
import com.operator.app.audio.CommunicationLink
import com.operator.app.audio.ContinuousMicrophone
import com.operator.app.audio.GlassesAudioCoordinator
import com.operator.app.transcription.ListenController
import com.operator.core.transcription.RollingTranscript
import com.operator.app.bluetooth.BluetoothStatusMonitor
import com.operator.app.config.BuildConfigLoader
import com.operator.app.glasses.GlassesProviderLoader
import com.operator.app.glasses.GlassesSubsystemReporter
import com.operator.app.permissions.BluetoothPermission
import com.operator.app.permissions.MicrophonePermission
import com.operator.core.audio.AudioLoopbackController
import com.operator.core.audio.AudioPlayer
import com.operator.core.audio.AudioRecorder
import com.operator.core.config.OperatorConfig
import com.operator.core.decision.ResponseDecisionEngine
import com.operator.core.decision.SilentDecisionEngine
import com.operator.core.diagnostics.RouteEventLog
import com.operator.core.glasses.GlassesProvider
import com.operator.core.model.Subsystem
import com.operator.core.model.SubsystemState
import com.operator.core.model.SubsystemStatus
import com.operator.core.state.OperatorStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Manual dependency container (ADR-008: no DI framework until it earns its keep).
 * One instance per process, owned by [com.operator.app.OperatorApplication].
 *
 * Everything the UI needs is reachable from here; everything here is replaceable with a
 * fake for tests. Future subsystems (AI, TTS, memory, glasses, remote) are added as
 * properties on this class behind their core interfaces.
 */
class OperatorContainer(app: Application) {

    val config: OperatorConfig = BuildConfigLoader.load()

    /** Process-wide scope for long-lived collectors. Main-immediate so state updates hit the UI promptly. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val stateManager = OperatorStateManager(
        initialMode = config.defaultMode,
        initialWit = config.defaultWit,
    )

    val routeEventLog = RouteEventLog()

    val microphonePermission = MicrophonePermission(app)
    val bluetoothPermission = BluetoothPermission(app)

    val audioRouteMonitor = AudioRouteMonitor(app, routeEventLog)
    val bluetoothStatus = BluetoothStatusMonitor(app, bluetoothPermission, routeEventLog)

    private val communicationLink = CommunicationLink(app, audioRouteMonitor)
    val audioRecorder: AudioRecorder = AndroidAudioRecorder(app, audioRouteMonitor, communicationLink)
    val audioPlayer: AudioPlayer = AndroidAudioPlayer(app, audioRouteMonitor, communicationLink)

    val loopback = AudioLoopbackController(
        recorder = audioRecorder,
        player = audioPlayer,
        scope = appScope,
        recordDurationMillis = config.recordTestDurationMillis,
    )

    /** Placeholder until Milestone 12. Always NO_RESPONSE. */
    val decisionEngine: ResponseDecisionEngine = SilentDecisionEngine

    /**
     * Milestone 11: the last [OperatorConfig.rollingContextSeconds] of conversation, in memory
     * only. One instance for the process, so the Listen panel, the foreground service, and any
     * question asked all see the same window.
     */
    val transcript = RollingTranscript(windowMillis = config.rollingContextSeconds * 1_000L)

    /** Milestone 6: the phone's only route to the models; credentials stay on the backend. */
    val backendClient = OperatorBackendClient(config.backendUrl)
    val ask = AskOperatorController(
        backendClient,
        appScope,
        stateSupplier = { stateManager.current.let { it.mode to it.wit } },
        transcriptSupplier = { transcript.entries().map { "${it.speaker.label}: ${it.text}" } },
    )
    val speechPlayer = AndroidStreamingSpeechPlayer(app, audioRouteMonitor, communicationLink)
    val speech = SpeechController(
        backendClient,
        speechPlayer,
        appScope,
        selectionSupplier = { loopback.state.value.selection },
    )

    /** Milestone 8: open microphone, gated by voice-activity detection before anything is sent. */
    private val continuousMicrophone = ContinuousMicrophone(app, audioRouteMonitor, communicationLink)
    val listen = ListenController(continuousMicrophone, backendClient, appScope, transcript)

    /**
     * Milestone 10 decides *who holds the microphone*; Milestone 11's TranscriptionService keeps
     * the process in the foreground so it can be held at all. Both are needed: the coordinator
     * pauses capture while Operator speaks, and the service is what lets capture survive the
     * screen going off. The service therefore drives listening through this, never around it.
     */
    val glassesAudio = GlassesAudioCoordinator(
        listen,
        speech,
        appScope,
        selectionSupplier = { loopback.state.value.selection },
        audioAllowed = { stateManager.current.let { !it.muted && it.isProcessing } },
    )

    /** Meta Wearables toolkit when compiled in, otherwise an honest no-op (ADR-004 / ADR-013). */
    val glasses: GlassesProvider = GlassesProviderLoader.load(app, appScope)
    private val glassesSubsystemReporter = GlassesSubsystemReporter(stateManager, glasses)

    private val audioSubsystemReporter = AudioSubsystemReporter(
        stateManager = stateManager,
        permission = microphonePermission,
        routes = audioRouteMonitor,
        loopback = loopback,
    )

    init {
        audioRouteMonitor.start()
        bluetoothStatus.start()
        audioSubsystemReporter.start(appScope)
        glassesSubsystemReporter.start(appScope)
        stateManager.updateSubsystem(
            Subsystem.AI,
            if (backendClient.configured) SubsystemStatus(SubsystemState.READY, config.backendUrl)
            else SubsystemStatus(SubsystemState.NOT_CONFIGURED, "Set OPERATOR_BACKEND_URL"),
        )
        stateManager.updateSubsystem(
            Subsystem.HEARING,
            if (backendClient.configured) SubsystemStatus(SubsystemState.READY, "Voice detection on device, transcription on the backend")
            else SubsystemStatus(SubsystemState.NOT_CONFIGURED, "Set OPERATOR_BACKEND_URL"),
        )
        listen.state
            .onEach { l ->
                stateManager.updateSubsystem(
                    Subsystem.HEARING,
                    when {
                        l.error != null -> SubsystemStatus(SubsystemState.ERROR, l.error)
                        l.listening -> SubsystemStatus(SubsystemState.ACTIVE, l.status.label)
                        !backendClient.configured -> SubsystemStatus(SubsystemState.NOT_CONFIGURED, "Set OPERATOR_BACKEND_URL")
                        else -> SubsystemStatus(SubsystemState.READY, "Idle")
                    },
                )
            }
            .launchIn(appScope)
        stateManager.updateSubsystem(
            Subsystem.VOICE,
            if (backendClient.speechConfigured) SubsystemStatus(SubsystemState.READY, "ElevenLabs via backend")
            else SubsystemStatus(SubsystemState.NOT_CONFIGURED, "Set OPERATOR_BACKEND_URL"),
        )
        // Initialise the vendor SDK at process start, like Meta's samples do in Application.onCreate.
        glasses.initialize()
        // A selected device that disconnects must not silently keep being "selected".
        audioRouteMonitor.routes
            .onEach { loopback.onRoutesChanged(it.inputs, it.outputs) }
            .launchIn(appScope)
    }
}
