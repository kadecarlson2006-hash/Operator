package com.operator.backend.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryNeedsRewriteTest {

    @Test
    fun `a plain question is searched as it was asked`() {
        // Found the forecast before the rewrite existed; rewriting it only added a round trip.
        assertFalse(QueryNeedsRewrite.judge("what's the weather in Salina today"))
        assertFalse(QueryNeedsRewrite.judge("who won the game last night"))
        assertFalse(QueryNeedsRewrite.judge("who wears 95 for the rams"))
    }

    @Test
    fun `news passed on conversationally is rewritten`() {
        assertTrue(QueryNeedsRewrite.judge("did you see the rams aaron donald isn't traveling to AUS with the rest of the team"))
        assertTrue(QueryNeedsRewrite.judge("I heard the stadium is closing"))
        assertTrue(QueryNeedsRewrite.judge("apparently the game got moved"))
    }

    @Test
    fun `shorthand is rewritten`() {
        assertTrue(QueryNeedsRewrite.judge("is LAR playing tonight"))
    }

    @Test
    fun `a long remark is rewritten`() {
        assertTrue(QueryNeedsRewrite.judge("so my brother was saying the new bridge downtown is going to be closed for most of next month"))
    }
}
