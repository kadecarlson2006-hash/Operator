package com.operator.core.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Who said a line. Operator cannot tell people apart: the transcription provider returns text,
 * not diarisation, so anything captured from the room is [UNKNOWN]. Claiming otherwise would be
 * inventing a capability the system does not have.
 */
enum class Speaker(val label: String) {
    /** Someone in the room. Which person is not known. */
    UNKNOWN("Someone"),

    /** Operator's own spoken reply, which it does know it produced. */
    OPERATOR("Operator"),
}

/**
 * One heard utterance.
 *
 * [toString] deliberately omits the text. These objects pass through logging-adjacent code, and
 * the project rule is that transcript logging is off by default; a stray `Log.d(TAG, "$entry")`
 * should not be able to spill what was said in the room.
 */
data class TranscriptEntry(
    val text: String,
    val atMillis: Long,
    val speaker: Speaker = Speaker.UNKNOWN,
) {
    override fun toString(): String = "TranscriptEntry($speaker, ${text.length} chars, at=$atMillis)"
}

/**
 * The last few minutes of conversation, in memory only (Milestone 11).
 *
 * The brief allows a rolling buffer and forbids a permanent record, so this is bounded twice —
 * by age and by entry count — and pruned on read as well as on write. Pruning only on write
 * would let a transcript from hours ago sit in the buffer and reach a prompt simply because
 * nothing new had been heard since.
 *
 * Nothing here is written to disk. [clear] exists so the user can drop the window at any moment,
 * and dropping it is immediate rather than a flag checked later.
 */
class RollingTranscript(
    private val windowMillis: Long = 60_000,
    private val maxEntries: Int = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(windowMillis > 0) { "windowMillis must be > 0" }
        require(maxEntries > 0) { "maxEntries must be > 0" }
    }

    private val lock = ReentrantLock()
    private val entries = ArrayDeque<TranscriptEntry>()

    private val _state = MutableStateFlow<List<TranscriptEntry>>(emptyList())

    /** The live window, for the UI. Already pruned. */
    val state: StateFlow<List<TranscriptEntry>> = _state.asStateFlow()

    /** Adds a heard line. Blank text is ignored: an empty transcript is not a turn. */
    fun add(text: String, speaker: Speaker = Speaker.UNKNOWN, atMillis: Long = clock()) {
        if (text.isBlank()) return
        lock.withLock {
            entries.addLast(TranscriptEntry(text.trim(), atMillis, speaker))
            prune()
            publish()
        }
    }

    /** The current window, oldest first. */
    fun entries(): List<TranscriptEntry> = lock.withLock {
        prune()
        publish()
        entries.toList()
    }

    /**
     * The window rendered for a prompt, or null when there is nothing to say. Null rather than an
     * empty string so the context assembler omits the section entirely (ADR-028's rule that an
     * absent section is absent, not an empty heading).
     */
    fun render(): String? {
        val lines = entries()
        if (lines.isEmpty()) return null
        return lines.joinToString("\n") { "${it.speaker.label}: ${it.text}" }
    }

    /** Drops everything immediately. */
    fun clear() = lock.withLock {
        entries.clear()
        publish()
    }

    /** Milliseconds between the oldest and newest retained lines, or 0 when fewer than two. */
    fun spanMillis(): Long = lock.withLock {
        prune()
        if (entries.size < 2) 0 else entries.last().atMillis - entries.first().atMillis
    }

    val size: Int get() = entries().size

    /** Caller already holds the lock. */
    private fun prune() {
        val cutoff = clock() - windowMillis
        while (entries.isNotEmpty() && entries.first().atMillis < cutoff) entries.removeFirst()
        while (entries.size > maxEntries) entries.removeFirst()
    }

    /** Caller already holds the lock. */
    private fun publish() {
        _state.value = entries.toList()
    }
}
