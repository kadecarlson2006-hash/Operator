package com.operator.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Milestone 15. Nobody has the hardware (risk 10), so these tests are the only thing exercising
 * any of this. They are written to pin the timing rules rather than the implementation.
 */
class RemoteButtonMapperTest {

    private val key = 79 // KEYCODE_HEADSETHOOK, the code a plain headset button sends.

    private fun tap(at: Long, keyCode: Int = key) = ButtonEvent(keyCode, at, at + 40)
    private fun hold(at: Long, keyCode: Int = key) = ButtonEvent(keyCode, at, at + 900)

    @Test
    fun `a hold is push to talk and resolves immediately`() {
        val m = RemoteButtonMapper()
        assertEquals(RemoteAction.PUSH_TO_TALK, m.onPress(hold(1_000)))
    }

    @Test
    fun `a single tap waits, because it might be the first half of a double`() {
        val m = RemoteButtonMapper()
        assertNull(m.onPress(tap(1_000)), "a lone tap cannot be classified the moment it happens")
        assertTrue(m.hasPendingTap())
    }

    @Test
    fun `a lone tap becomes a comment request once the window passes`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        assertNull(m.resolvePending(1_100), "too early: the second tap could still arrive")
        assertEquals(RemoteAction.COMMENT_NOW, m.resolvePending(1_500))
    }

    @Test
    fun `a lone tap stops Operator when it is talking`() {
        // Read at resolve time rather than press time, because this is the moment it takes effect.
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        assertEquals(RemoteAction.STOP_SPEAKING, m.resolvePending(1_500, speaking = true))
    }

    @Test
    fun `two quick taps mute`() {
        val m = RemoteButtonMapper()
        assertNull(m.onPress(tap(1_000)))
        assertEquals(RemoteAction.MUTE, m.onPress(tap(1_200)))
        assertFalse(m.hasPendingTap(), "the double press should consume the pending tap")
    }

    @Test
    fun `two slow taps are two separate requests, not a mute`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        assertEquals(RemoteAction.COMMENT_NOW, m.resolvePending(1_500))
        assertNull(m.onPress(tap(2_000)))
        assertEquals(RemoteAction.COMMENT_NOW, m.resolvePending(2_500))
    }

    @Test
    fun `mute is not on the long press`() {
        // A long press is indistinguishable from a button snagged on a sleeve, so the most
        // serious action is deliberately not there.
        val m = RemoteButtonMapper()
        assertEquals(RemoteAction.PUSH_TO_TALK, m.onPress(hold(1_000)))
    }

    @Test
    fun `a hold cancels a tap that was waiting`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        assertEquals(RemoteAction.PUSH_TO_TALK, m.onPress(hold(1_100)))
        assertFalse(m.hasPendingTap())
        assertNull(m.resolvePending(9_999), "the cancelled tap must not fire later")
    }

    @Test
    fun `taps from different buttons do not combine into a mute`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000, keyCode = 79))
        assertNull(
            m.onPress(tap(1_100, keyCode = 85)),
            "two different buttons pressed together is not a double press of either",
        )
    }

    @Test
    fun `keys the remote does not own are ignored`() {
        val m = RemoteButtonMapper(acceptedKeyCodes = setOf(79))
        assertNull(m.onPress(hold(1_000, keyCode = 24)))
        assertFalse(m.hasPendingTap(), "an ignored key should not leave state behind")
    }

    @Test
    fun `an empty accepted set accepts any key`() {
        val m = RemoteButtonMapper()
        assertEquals(RemoteAction.PUSH_TO_TALK, m.onPress(hold(1_000, keyCode = 12345)))
    }

    @Test
    fun `resolving with nothing pending does nothing`() {
        assertNull(RemoteButtonMapper().resolvePending(5_000))
    }

    @Test
    fun `the caller can be told how long to wait`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        assertEquals(310L, m.millisUntilResolvable(1_080))
        assertEquals(0L, m.millisUntilResolvable(9_999), "never negative")
        assertNull(RemoteButtonMapper().millisUntilResolvable(1_000))
    }

    @Test
    fun `reset clears a pending tap`() {
        val m = RemoteButtonMapper()
        m.onPress(tap(1_000))
        m.reset()
        assertFalse(m.hasPendingTap())
        assertNull(m.resolvePending(9_999))
    }

    @Test
    fun `an out of order event does not produce a negative hold`() {
        // Bluetooth timestamps arriving out of order should not read as a hold of minus one second.
        val event = ButtonEvent(key, downAtMillis = 2_000, upAtMillis = 1_000)
        assertEquals(0L, event.heldMillis)
    }

    @Test
    fun `bad configuration is refused at construction`() {
        assertTrue(runCatching { RemoteButtonMapper(longPressMillis = 0) }.isFailure)
        assertTrue(runCatching { RemoteButtonMapper(doublePressWindowMillis = -1) }.isFailure)
    }
}
