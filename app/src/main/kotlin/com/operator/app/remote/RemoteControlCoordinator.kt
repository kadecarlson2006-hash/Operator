package com.operator.app.remote

import android.util.Log
import com.operator.core.remote.ButtonEvent
import com.operator.core.remote.RemoteAction
import com.operator.core.remote.RemoteButtonMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the remote has done lately, for the panel. */
data class RemoteState(
    /** True once any button event has arrived, which is how the user knows a remote is connected. */
    val seen: Boolean = false,
    val lastAction: String? = null,
    val lastKeyCode: Int? = null,
    val presses: Int = 0,
    val awaitingSecondPress: Boolean = false,
)

/**
 * Drives Operator from a physical button (Milestone 15).
 *
 * The classification lives in `:core`'s [RemoteButtonMapper] so it can be tested without a device.
 * This class exists for the one thing that needs a coroutine: a single tap cannot be resolved
 * until the double-press window has passed with no second tap, so something has to wait and then
 * ask again.
 *
 * **Foreground only.** Key events reach this through the activity, so a button pressed while
 * Operator is in the background does nothing. Capturing media buttons system-wide needs a
 * MediaSession, which is not built: doing it properly means holding a session that survives the
 * screen going off, and that is worth doing once there is hardware to test it against
 * (risks 10, 54).
 */
class RemoteControlCoordinator(
    private val scope: CoroutineScope,
    private val mapper: RemoteButtonMapper = RemoteButtonMapper(),
    /** Whether Operator is talking right now, which decides what a lone tap means. */
    private val speaking: () -> Boolean = { false },
    private val onAction: (RemoteAction) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private var pendingJob: Job? = null
    private var downAtMillis: Long? = null

    /** Call when a button goes down. Returns true when the event was consumed. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (downAtMillis == null) downAtMillis = clock()
        return true
    }

    /** Call when the button comes back up. Returns true when the event was consumed. */
    fun onKeyUp(keyCode: Int): Boolean {
        val down = downAtMillis ?: clock()
        downAtMillis = null
        val event = ButtonEvent(keyCode, down, clock())

        _state.update { it.copy(seen = true, lastKeyCode = keyCode, presses = it.presses + 1) }

        val immediate = mapper.onPress(event, speaking())
        if (immediate != null) {
            pendingJob?.cancel()
            pendingJob = null
            fire(immediate)
            return true
        }

        // A tap that might yet become a double press. Wait exactly as long as the window, then
        // ask the mapper again.
        _state.update { it.copy(awaitingSecondPress = mapper.hasPendingTap()) }
        pendingJob?.cancel()
        pendingJob = scope.launch {
            val wait = mapper.millisUntilResolvable(clock()) ?: return@launch
            delay(wait)
            mapper.resolvePending(clock(), speaking())?.let(::fire)
            _state.update { it.copy(awaitingSecondPress = false) }
        }
        return true
    }

    /** Drops any half-finished gesture, so a stale tap cannot fire after the screen comes back. */
    fun reset() {
        pendingJob?.cancel()
        pendingJob = null
        downAtMillis = null
        mapper.reset()
        _state.update { it.copy(awaitingSecondPress = false) }
    }

    private fun fire(action: RemoteAction) {
        Log.i(TAG, "Remote action ${action.name}")
        _state.update { it.copy(lastAction = action.name, awaitingSecondPress = false) }
        onAction(action)
    }

    private companion object {
        const val TAG = "RemoteControl"
    }
}
