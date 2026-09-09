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
 */
object NeedsCurrentInformation {

    /** Words that mark a question about the present rather than about something settled. */
    private val TIME_SENSITIVE = listOf(
        "current", "currently", "now", "today", "tonight", "this season", "this year", "this week",
        "latest", "recent", "recently", "right now", "at the moment", "these days", "still",
        "news", "score", "weather", "forecast", "price", "cost of", "stock", "who is the",
        "who's the", "who won", "who wears", "roster", "release date", "when does", "when is",
        "schedule", "open", "closed", "update", "version",
    )

    /**
     * Subjects whose facts change even when the question does not say so. "Who plays quarterback
     * for the Rams" has no time word in it and is nonetheless about this season.
     */
    private val VOLATILE_SUBJECTS = listOf(
        "president", "prime minister", "ceo", "champion", "record", "rams", "team", "plays for",
        "signed", "traded", "election", "election result", "rate", "rates", "population",
    )

    fun judge(prompt: String): Boolean {
        val p = prompt.lowercase()
        if (TIME_SENSITIVE.any { p.contains(it) }) return true
        if (VOLATILE_SUBJECTS.any { p.contains(it) }) return true
        // A jersey or squad number is always about a particular season.
        if (Regex("\\bnumber\\s+\\d{1,2}\\b").containsMatchIn(p)) return true
        return false
    }
}
