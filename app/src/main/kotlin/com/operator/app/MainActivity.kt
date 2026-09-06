package com.operator.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.operator.app.permissions.BluetoothPermission
import com.operator.app.permissions.MicrophonePermission
import com.operator.app.ui.OperatorActions
import com.operator.app.ui.OperatorScreen
import com.operator.app.ui.OperatorViewModel
import com.operator.app.ui.theme.OperatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OperatorApplication).container
        setContent {
            OperatorTheme {
                val viewModel: OperatorViewModel = viewModel(factory = OperatorViewModel.factory(container))
                OperatorRoot(viewModel, this)
            }
        }
    }
}

@Composable
private fun OperatorRoot(viewModel: OperatorViewModel, activity: Activity) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
    }
    // Milestone 11: without this the "Operator is listening" notification is silently suppressed
    // on API 33+, leaving no visible sign that the microphone is open. Listening starts either
    // way; the user is simply asked first so the sign is there.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.startListening(activity)
    }

    // Re-check permissions and routes whenever the screen comes back (e.g. from system settings).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        // Milestone 11: listening deliberately survives leaving the screen — that is the point of
        // the foreground service, and it is not "behind the user's back" because the ongoing
        // notification says Operator is listening and stops it in one tap.
        //
        // Speaking does not get that treatment: it has no notification, so a reply must not carry
        // on out of sight. The loopback test still stops too.
        onPauseOrDispose {
            viewModel.stopAudio()
            viewModel.stopSpeaking()
        }
    }

    val actions = remember(viewModel, activity, notificationLauncher) {
        OperatorActions(
            onActivate = viewModel::activate,
            onStandby = viewModel::standby,
            onCommentNow = viewModel::commentNow,
            onToggleMute = viewModel::toggleMute,
            onSelectMode = viewModel::setMode,
            onSelectWit = viewModel::setWit,
            onRequestMicrophone = { microphoneLauncher.launch(MicrophonePermission.PERMISSION) },
            onRequestBluetooth = { bluetoothLauncher.launch(BluetoothPermission.PERMISSION) },
            onSelectInput = viewModel::selectInput,
            onSelectOutput = viewModel::selectOutput,
            onRecordTest = viewModel::recordTest,
            onPlayTest = viewModel::playTest,
            onStopAudio = viewModel::stopAudio,
            onDiscardClip = viewModel::discardClip,
            onRefresh = viewModel::refreshPermissions,
            onClearRouteLog = viewModel::clearRouteLog,
            onGlassesAction = { action -> viewModel.runGlassesAction(action, activity) },
            onAskPromptChange = viewModel::setAskPrompt,
            onAskSend = viewModel::sendAsk,
            onAskClear = viewModel::clearAsk,
            onSpeakAnswer = viewModel::speakAnswer,
            onStopSpeaking = viewModel::stopSpeaking,
            onStartListening = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.startListening(activity)
                }
            },
            onStopListening = { viewModel.stopListening(activity) },
            onClearTranscripts = viewModel::clearTranscripts,
        )
    }

    OperatorScreen(state = state, actions = actions)
}
