package com.operator.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakableTextTest {

    @Test
    fun `the first live weather answer loses its citation`() {
        // Verbatim from the first live run, apart from the encoding the console mangled.
        val live = "In Salina, Kansas, today—Friday, September 25—expect showers, then a chance of " +
            "thunderstorms, with a high near 79°F. " +
            "([forecast.weather.gov](https://forecast.weather.gov/zipcity.php?inputstring=Salina%2CKS))"
        assertEquals(
            "In Salina, Kansas, today—Friday, September 25—expect showers, then a chance of " +
                "thunderstorms, with a high near 79°F.",
            SpeakableText.clean(live),
        )
    }

    @Test
    fun `several sources in one group all go`() {
        assertEquals(
            "Myles Garrett wears 95.",
            SpeakableText.clean("Myles Garrett wears 95 ([espn.com](https://espn.com/x), [nfl.com](https://nfl.com/y))."),
        )
    }

    @Test
    fun `an inline link keeps its words`() {
        assertEquals("According to ESPN he is out.", SpeakableText.clean("According to [ESPN](https://espn.com/a) he is out."))
    }

    @Test
    fun `bare urls are not read out`() {
        assertEquals("Details are online.", SpeakableText.clean("Details are online (https://example.com/a?b=c)."))
    }

    @Test
    fun `urls with parentheses inside are removed whole`() {
        assertEquals("It rained.", SpeakableText.clean("It rained ([wiki](https://en.wikipedia.org/wiki/Rain_(weather)))."))
    }

    @Test
    fun `ordinary text is untouched`() {
        val plain = "Sixty-eight and clear (for now), then rain."
        assertEquals(plain, SpeakableText.clean(plain))
    }

    @Test
    fun `an answer that was only a link is kept rather than silenced`() {
        assertEquals("([a.com](https://a.com))", SpeakableText.clean("([a.com](https://a.com))"))
    }
}
