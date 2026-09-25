package com.operator.backend.ai

/**
 * Whether what was said needs rewriting before it can be searched.
 *
 * The rewrite is a whole model round trip spent before the search can even start, and it ran on
 * every searched question. It exists for one kind of speech: news passed on conversationally -
 * "did you see the rams aaron donald isn't traveling to AUS" - where filler and shorthand stop a
 * search engine finding the story. "What's the weather in Salina today" is already a good query;
 * it found the forecast before the rewrite existed. Rewriting it bought nothing and cost a round
 * trip on the question where waiting is most noticeable.
 *
 * A heuristic, for the same reason NeedsCurrentInformation is one: asking a model whether to ask
 * a model is the cost being avoided. It errs toward rewriting - a needless rewrite costs about a
 * second, a missed one can cost the answer.
 */
object QueryNeedsRewrite {

    /** How people pass news on. Matched at the start, where they sit in speech. */
    private val CONVERSATIONAL_OPENINGS = listOf(
        "did you see", "did you hear", "have you seen", "have you heard", "i heard", "i saw",
        "i read", "apparently", "guess what", "you know", "hey", "so apparently", "check this",
        "can you believe", "turns out", "looks like",
    )

    /** Two to five capitals standing alone: AUS, LAR, SF, NFL - shorthand a search can misread. */
    private val ABBREVIATION = Regex("""\b[A-Z]{2,5}\b""")

    /** Past this, it is a remark with a question in it rather than a question. */
    private const val MAX_PLAIN_WORDS = 12

    fun judge(question: String): Boolean {
        val lower = question.trim().lowercase()
        if (CONVERSATIONAL_OPENINGS.any { lower.startsWith(it) }) return true
        if (ABBREVIATION.containsMatchIn(question)) return true
        return lower.split(Regex("""\s+""")).count { it.isNotBlank() } > MAX_PLAIN_WORDS
    }
}
