package com.operator.core.camera

import com.operator.core.model.OperatorMode

/** Why a look was refused. Short diagnostic labels, never model reasoning (ADR-009). */
object CameraRefusal {
    const val MUTED = "MUTED"
    const val MODE_OFF = "MODE_OFF"
    const val NOT_REQUESTED = "NOT_REQUESTED"
    const val NO_PERMISSION = "NO_PERMISSION"
    const val GLASSES_UNAVAILABLE = "GLASSES_UNAVAILABLE"
    const val TOO_SOON = "TOO_SOON"
    const val BUDGET = "BUDGET"
}

/** What the user asked for when they asked Operator to look. */
enum class LookTrigger {
    /** The user explicitly asked. The only trigger that exists. */
    USER_REQUEST,
}

data class LookRequest(
    val trigger: LookTrigger,
    val mode: OperatorMode,
    val muted: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val glassesReady: Boolean = false,
)

/**
 * Whether Operator may take a picture (Milestone 16).
 *
 * The shape mirrors `ConversationPolicy`: it can only refuse, never permit, and it refuses before
 * anything is captured or sent. The reasons for a camera are stricter than for a microphone,
 * though, and the difference is deliberate.
 *
 * A microphone on all the time is what the rest of this system is built around, gated by a local
 * voice-activity check so silence never leaves the device. A camera has no equivalent: there is no
 * cheap local test for "this frame is worth uploading", so an always-on camera means either
 * uploading everything or guessing. Both are the surveillance device the brief is explicit about
 * not building, pointed at people who never agreed to it.
 *
 * So there is exactly one trigger — the user asking — and no ambient path at all (ADR-048). That
 * is not a gap to be filled in later. Automatic looking is the thing that turns a pair of glasses
 * into something nobody around the wearer consented to.
 */
class CameraContextPolicy(
    /** How long after a look before another is allowed. */
    private val minIntervalSeconds: Int = DEFAULT_MIN_INTERVAL_SECONDS,
    private val maxLooksPer5Minutes: Int = DEFAULT_MAX_PER_5_MINUTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(minIntervalSeconds >= 0) { "minIntervalSeconds must be >= 0" }
        require(maxLooksPer5Minutes >= 0) { "maxLooksPer5Minutes must be >= 0" }
    }

    private val lookedAt = ArrayDeque<Long>()

    /** Returns a refusal reason, or null when a look is allowed. */
    fun gate(request: LookRequest): String? {
        if (request.muted) return CameraRefusal.MUTED
        if (request.mode == OperatorMode.OFF) return CameraRefusal.MODE_OFF
        // Defensive rather than redundant: the enum has one value today, and this is the check
        // that has to fail loudly if an ambient trigger is ever added without reading ADR-048.
        if (request.trigger != LookTrigger.USER_REQUEST) return CameraRefusal.NOT_REQUESTED
        if (!request.cameraPermissionGranted) return CameraRefusal.NO_PERMISSION
        if (!request.glassesReady) return CameraRefusal.GLASSES_UNAVAILABLE

        val now = clock()
        prune(now)
        lookedAt.lastOrNull()?.let { last ->
            if (now - last < minIntervalSeconds * 1_000L) return CameraRefusal.TOO_SOON
        }
        if (lookedAt.size >= maxLooksPer5Minutes) return CameraRefusal.BUDGET
        return null
    }

    fun recordLook(atMillis: Long = clock()) {
        lookedAt.addLast(atMillis)
        prune(atMillis)
    }

    fun recentLookCount(): Int {
        prune(clock())
        return lookedAt.size
    }

    fun reset() = lookedAt.clear()

    private fun prune(now: Long) {
        while (lookedAt.isNotEmpty() && now - lookedAt.first() > WINDOW_MILLIS) lookedAt.removeFirst()
    }

    companion object {
        /** Long enough that holding the button down cannot become a burst. */
        const val DEFAULT_MIN_INTERVAL_SECONDS = 5

        /** A ceiling on cost and on how much of a room ends up described. */
        const val DEFAULT_MAX_PER_5_MINUTES = 10

        private const val WINDOW_MILLIS = 5 * 60 * 1_000L
    }
}
