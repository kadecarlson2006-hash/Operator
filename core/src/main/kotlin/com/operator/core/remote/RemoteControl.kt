package com.operator.core.remote

/**
 * What a physical button can ask Operator to do (Milestone 15).
 *
 * The list is short on purpose. A remote worn on a finger is operated without looking at it, often
 * mid-conversation, so every action has to be one the user can be sure of by feel. Anything that
 * needs confirmation, or that would be embarrassing to trigger by accident, does not belong here.
 */
enum class RemoteAction {
    /** Hold to talk, release to send. The reason a physical button is wanted at all. */
    PUSH_TO_TALK,

    /** Ask Operator whether it has anything to say. Equivalent to the on-screen button. */
    COMMENT_NOW,

    /** Stop Operator mid-sentence. */
    STOP_SPEAKING,

    /**
     * Emergency mute. Always reachable, and never a long press away.
     *
     * Toggles rather than latches: whatever silenced Operator should be able to bring it back, and
     * a remote you cannot un-mute from is worse than one you cannot mute from.
     */
    MUTE,
}

/** How the button was pressed. */
enum class PressKind { SINGLE, DOUBLE, LONG }

/** A press, as the platform reports it. */
data class ButtonEvent(val keyCode: Int, val downAtMillis: Long, val upAtMillis: Long) {
    val heldMillis: Long get() = (upAtMillis - downAtMillis).coerceAtLeast(0)
}

/**
 * Turns raw button presses into Operator actions.
 *
 * Deliberately platform-free so the timing rules can be tested without a device, which matters
 * more here than usual: nobody has this hardware yet (risk 10), so unit tests are the only thing
 * exercising any of it.
 *
 * Rules, and why:
 *
 *  - A **long press** is push-to-talk. Holding to speak is the gesture people already know from
 *    every radio and walkie-talkie, and it cannot be triggered by brushing the button.
 *  - A **single press** asks for a comment, unless Operator is speaking, in which case it stops
 *    it. Interrupting is what a single tap should do while something is talking at you.
 *  - A **double press** mutes. Two taps is hard to do by accident and fast to do in a hurry.
 *
 * Mute is not the long press even though it is the most serious action, because a long press is
 * indistinguishable from a button snagged on a sleeve.
 */
class RemoteButtonMapper(
    /** Presses longer than this are a hold, not a tap. */
    private val longPressMillis: Long = DEFAULT_LONG_PRESS_MILLIS,
    /** Two taps closer together than this are one double press. */
    private val doublePressWindowMillis: Long = DEFAULT_DOUBLE_PRESS_WINDOW_MILLIS,
    /** Which key codes this remote sends. Empty accepts any key. */
    private val acceptedKeyCodes: Set<Int> = emptySet(),
) {
    init {
        require(longPressMillis > 0) { "longPressMillis must be positive" }
        require(doublePressWindowMillis > 0) { "doublePressWindowMillis must be positive" }
    }

    private var pendingTapAtMillis: Long? = null
    private var pendingTapKeyCode: Int? = null

    /**
     * Classifies a press, or returns null when the decision has to wait.
     *
     * A single tap cannot be resolved the moment it happens: it might be the first half of a
     * double press. It is held until either a second tap arrives or [resolvePending] is called
     * once the window has passed. Reporting a tap immediately and then correcting it would mean
     * Operator acts and then un-acts, which on a device that speaks out loud is worse than waiting.
     */
    fun onPress(event: ButtonEvent, speaking: Boolean = false): RemoteAction? {
        if (acceptedKeyCodes.isNotEmpty() && event.keyCode !in acceptedKeyCodes) return null

        if (event.heldMillis >= longPressMillis) {
            // A hold cancels any tap waiting to be resolved: the user changed their mind about
            // what they were doing, and the earlier tap was part of this gesture, not a request.
            pendingTapAtMillis = null
            pendingTapKeyCode = null
            return RemoteAction.PUSH_TO_TALK
        }

        val previous = pendingTapAtMillis
        if (previous != null &&
            pendingTapKeyCode == event.keyCode &&
            event.downAtMillis - previous <= doublePressWindowMillis
        ) {
            pendingTapAtMillis = null
            pendingTapKeyCode = null
            return RemoteAction.MUTE
        }

        pendingTapAtMillis = event.upAtMillis
        pendingTapKeyCode = event.keyCode
        return null
    }

    /**
     * Resolves a tap once the double-press window has passed with no second tap.
     *
     * [speaking] decides what a lone tap means, and is read here rather than at press time because
     * this is the moment the action actually happens.
     */
    fun resolvePending(now: Long, speaking: Boolean = false): RemoteAction? {
        val tapped = pendingTapAtMillis ?: return null
        if (now - tapped < doublePressWindowMillis) return null
        pendingTapAtMillis = null
        pendingTapKeyCode = null
        return if (speaking) RemoteAction.STOP_SPEAKING else RemoteAction.COMMENT_NOW
    }

    /** True when a tap is waiting on the double-press window. */
    fun hasPendingTap(): Boolean = pendingTapAtMillis != null

    /** How long until a pending tap can be resolved, or null when nothing is waiting. */
    fun millisUntilResolvable(now: Long): Long? =
        pendingTapAtMillis?.let { (doublePressWindowMillis - (now - it)).coerceAtLeast(0) }

    fun reset() {
        pendingTapAtMillis = null
        pendingTapKeyCode = null
    }

    companion object {
        /** Long enough not to fire on a firm tap, short enough not to feel like a wait. */
        const val DEFAULT_LONG_PRESS_MILLIS = 500L

        /**
         * The cost of this window is that every single tap is delayed by it, so it is as short as
         * a deliberate double tap allows rather than as long as one might comfortably take.
         */
        const val DEFAULT_DOUBLE_PRESS_WINDOW_MILLIS = 350L
    }
}
