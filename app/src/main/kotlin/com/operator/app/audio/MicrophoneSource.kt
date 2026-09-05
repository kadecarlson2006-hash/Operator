package com.operator.app.audio

import com.operator.core.audio.AudioRoute
import com.operator.core.audio.RouteSelection
import kotlinx.coroutines.flow.Flow

/**
 * An open microphone as a stream of PCM-16 frames (Milestone 8).
 *
 * A port rather than a concrete class so the listening pipeline — detection, segmentation,
 * upload, cancellation — can be tested on the JVM without a device.
 */
interface MicrophoneSource {
    /** The device the platform actually captured from, once it reports one. */
    val actualRoute: AudioRoute?

    /** Emits frames until the collector is cancelled. Cancelling stops the hardware. */
    fun listen(selection: RouteSelection): Flow<ShortArray>
}
