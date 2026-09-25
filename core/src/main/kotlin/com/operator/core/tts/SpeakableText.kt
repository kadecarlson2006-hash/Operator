package com.operator.core.tts

/**
 * Makes model output fit to be spoken.
 *
 * Live web search annotates answers with markdown citations -
 * `([forecast.weather.gov](https://forecast.weather.gov/zipcity.php?...))` on the first live
 * weather answer. On a screen that is a link; through the glasses it is a voice reading out
 * a URL, character by character, after the answer. The source is dropped rather than read:
 * the answer is what was asked for.
 */
object SpeakableText {

    private val LINK = """\[([^\]]*)]\((?:[^()\s]|\([^()\s]*\))*\)"""

    /** A parenthesised group made only of links: `([a](u))` or `([a](u), [b](v))`. */
    private val CITATION_GROUP = Regex("""\s*\(\s*$LINK(?:\s*[,;]\s*$LINK)*\s*\)""")
    private val INLINE_LINK = Regex(LINK)
    private val BARE_URL = Regex("""\s*\(?\s*https?://[^\s)]+\s*\)?""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([.,;:!?])""")
    private val RUNS_OF_SPACE = Regex("""[ \t]{2,}""")

    fun clean(text: String): String {
        val cleaned = text
            .replace(CITATION_GROUP, "")
            .replace(INLINE_LINK) { it.groupValues[1] }
            .replace(BARE_URL, " ")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(RUNS_OF_SPACE, " ")
            .trim()
        // Never turn something into nothing: an answer that was only a link is better read out
        // than silently dropped after the user asked for it.
        return cleaned.ifBlank { text.trim() }
    }
}
