package com.operator.core.wake

import com.operator.core.decision.DecisionTrigger

/**
 * Recognises Operator being spoken to by name.
 *
 * Until this existed, `DIRECT_ADDRESS` could only be produced by a physical button, so saying
 * "Operator, what are the colours in the rainbow" was judged by the *uninvited* rules - strict
 * floors, silence when unsure - and Operator said nothing. Being addressed by name is the most
 * natural way to ask something aloud, and it was the one route in that nothing listened for.
 *
 * Matching is deliberately loose on the edges and strict about position. The name must open the
 * line, because "the switchboard operator called" is not somebody addressing Operator, while
 * "operator, what time is it" plainly is. Transcription will also mangle it - "operator" arrives
 * as "Operator," or "operater" or "opperator" - so near-misses of the same shape are accepted.
 * The cost of a false positive is Operator answering something nobody asked; the cost of a false
 * negative is it ignoring you when you use its name, which is worse.
 */
object WakeWord {

    const val DEFAULT_NAME = "operator"

    /**
     * How much of the line to search. A name used later is being talked about, not talked to.
     *
     * Two, not three: "the switchboard operator called" puts the name third, and three words was
     * enough to read that as somebody addressing Operator. Two still catches the natural openers,
     * "Hey Operator" and "OK operator".
     */
    private const val LEADING_WORDS = 2

    /**
     * True when [line] opens by addressing Operator.
     *
     * The speaker prefix the rolling transcript adds ("Someone: ...") is stripped first, so this
     * works on a transcript line as well as on raw text.
     */
    fun isAddressed(line: String, name: String = DEFAULT_NAME): Boolean {
        val body = line.substringAfter(": ", line).trim()
        if (body.isEmpty()) return false

        val words = body.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        return words.take(LEADING_WORDS).any { matches(it, name) }
    }

    /** Removes the address so the request reads as a question rather than a greeting. */
    fun stripAddress(line: String, name: String = DEFAULT_NAME): String {
        val body = line.substringAfter(": ", line).trim()
        val first = Regex("^\\W*(\\p{L}[\\p{L}']*)").find(body) ?: return body
        if (!matches(first.groupValues[1], name)) return body
        // Whatever follows the name, minus the punctuation that separated them.
        // Trim whatever separated the name from the request - a comma, a dash, a pause rendered
        // as an ellipsis - rather than a fixed list of the punctuation thought of at the time.
        return body.substring(first.range.last + 1)
            .trimStart { !it.isLetterOrDigit() }
            .ifBlank { body }
    }

    /** The trigger a line deserves: addressing Operator by name is asking it directly. */
    fun triggerFor(line: String, fallback: DecisionTrigger = DecisionTrigger.AMBIENT, name: String = DEFAULT_NAME): DecisionTrigger =
        if (isAddressed(line, name)) DecisionTrigger.DIRECT_ADDRESS else fallback

    /**
     * A word counts when it is the name, or a near-miss of the same shape.
     *
     * One edit of tolerance, and only for words already close in length. Transcription errors on a
     * four-syllable word are common; accepting anything looser would start catching "operate" and
     * "operation", which appear in ordinary speech about machinery.
     */
    private fun matches(word: String, name: String): Boolean {
        val w = word.lowercase().trim { !it.isLetterOrDigit() }
        if (w == name) return true
        if (kotlin.math.abs(w.length - name.length) > 1) return false
        return editDistanceAtMostOne(w, name)
    }

    /** Levenshtein distance <= 1, without building a matrix for two short words. */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (longer.length - shorter.length > 1) return false

        var i = 0
        var j = 0
        var edited = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) { i++; j++; continue }
            if (edited) return false
            edited = true
            if (shorter.length == longer.length) { i++; j++ } else { j++ }
        }
        return true
    }
}
