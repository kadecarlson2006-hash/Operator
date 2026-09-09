package com.operator.backend.ai

/**
 * Whether a question plausibly needs looking up (ADR-052).
 *
 * Search is billed per call and adds seconds, so running it on everything is indefensible - the
 * first live test searched the web to answer "say hello in one short sentence", which cost four
 * times the price of the same call without it.
 *
 * A cheap heuristic rather than asking a model, because asking a model whether to search costs a
 * model call, which is most of what we were trying to save. It errs toward searching: a needless
 * search costs a fraction of a cent, while a missed one gives a confidently stale answer, which is
 * the failure that started all this.
 *
 * Judge the question, not the whole rolling window. A word from four lines ago is not what the
 * search will look for, and the window is long enough that almost anything in it would match.
 */
object NeedsCurrentInformation {

    /**
     * Single words that mark a question about the present rather than about something settled.
     * Matched whole, with an optional plural: substring matching made "know" contain "now", so
     * "I don't know" searched the web, and "grateful" contained "rate".
     */
    private val TIME_SENSITIVE_WORDS = listOf(
        "current", "currently", "now", "today", "tonight", "latest", "recent", "recently",
        "news", "score", "weather", "forecast", "price", "stock", "roster", "schedule",
        "open", "closed", "update", "version", "still",
    )

    /** Phrases specific enough to match anywhere in the line. */
    private val TIME_SENSITIVE_PHRASES = listOf(
        "this season", "this year", "this week", "right now", "at the moment", "these days",
        "cost of", "who is the", "who's the", "who won", "who wears", "release date",
        "when does", "when is",
    )

    /**
     * Subjects whose facts change even when the question does not say so. "Who plays quarterback
     * for the Rams" has no time word in it and is nonetheless about this season.
     */
    private val VOLATILE_WORDS = listOf(
        "president", "ceo", "champion", "record", "rams", "team", "signed", "traded",
        "election", "rate", "population",
    )

    private val VOLATILE_PHRASES = listOf("prime minister", "plays for")

    private val PHRASES = TIME_SENSITIVE_PHRASES + VOLATILE_PHRASES

    /** One pass over the line rather than one scan per word, since this runs before every answer. */
    private val WORDS = Regex(
        (TIME_SENSITIVE_WORDS + VOLATILE_WORDS).joinToString("|", prefix = "\\b(", postfix = ")s?\\b") {
            Regex.escape(it)
        },
    )

    /** A jersey or squad number is always about a particular season. */
    private val SQUAD_NUMBER = Regex("\\bnumber\\s+\\d{1,2}\\b")

    fun judge(prompt: String): Boolean {
        val p = prompt.lowercase()
        if (PHRASES.any { p.contains(it) }) return true
        if (WORDS.containsMatchIn(p)) return true
        if (SQUAD_NUMBER.containsMatchIn(p)) return true
        return false
    }
}
