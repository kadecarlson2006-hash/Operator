package com.operator.core.camera

/**
 * What came back from looking (Milestone 16).
 *
 * Text, and nothing else. The image is described and dropped: no frame is written to disk, kept in
 * a store, or attached to a memory. That is the same rule the audio path follows - rolling
 * in-memory buffers, nothing permanent - applied to the sense that would be far worse to get
 * wrong (ADR-049).
 */
data class SceneDescription(
    val text: String,
    val model: String? = null,
    val latencyMillis: Long = 0,
    /** Bytes sent, for the usage panel. The image itself is already gone. */
    val imageBytes: Int = 0,
) {
    init {
        require(text.isNotBlank()) { "text must not be blank" }
    }
}

/**
 * Rules the vision prompt has to carry, kept next to the policy rather than buried in a prompt
 * file, because they are the reason the feature is allowed to exist at all.
 *
 * These are constraints on the *description*, which is the only artefact that survives a look. A
 * model asked to describe a scene will happily identify people, guess at their jobs, read their
 * badges aloud, and speculate about their moods, and every one of those is a detailed record of a
 * stranger who did not agree to be recorded.
 */
object VisionConstraints {
    val RULES: List<String> = listOf(
        "Describe only what is needed to answer the user's question.",
        "Do not identify anyone. Do not name people, guess who they are, or match them to anyone you know of.",
        "Do not describe anyone's appearance, clothing, race, age, health, or mood.",
        "Refer to people only as people, and only when their presence is relevant.",
        "Do not read out personal information visible in the scene, such as documents, screens, badges or licence plates.",
        "Say what you cannot see rather than guessing at it.",
    )

    /** The block appended to the vision prompt. */
    fun promptBlock(): String = buildString {
        appendLine("RULES FOR LOOKING:")
        RULES.forEach { appendLine("- $it") }
    }.trim()
}
