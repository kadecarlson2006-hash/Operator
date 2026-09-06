package com.operator.app.remote

import com.operator.core.remote.RemoteAction
import com.operator.core.remote.RemoteButtonMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOTE: `:app` uses JUnit 4, where an assertion's message is the FIRST argument. `:core` and
 * `:backend` use kotlin.test, where it is last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlCoordinatorTest {

    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        clock: Clock,
        speaking: () -> Boolean = { false },
        actions: MutableList<RemoteAction>,
    ) = RemoteControlCoordinator(
        scope = scope,
        mapper = RemoteButtonMapper(),
        speaking = speaking,
        onAction = { actions += it },
        clock = clock,
    )

    @Test
    fun `a hold fires push to talk without waiting`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        c.onKeyDown(79)
        clock.now += 900
        c.onKeyUp(79)

        assertEquals("a hold should not wait on the double-press window", listOf(RemoteAction.PUSH_TO_TALK), actions)
    }

    @Test
    fun `a lone tap becomes a comment request after the window`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        c.onKeyDown(79)
        clock.now += 40
        c.onKeyUp(79)
        assertTrue("nothing should fire yet", actions.isEmpty())

        clock.now += 400
        advanceUntilIdle()
        assertEquals(listOf(RemoteAction.COMMENT_NOW), actions)
    }

    @Test
    fun `a lone tap stops Operator when it is talking`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, speaking = { true }, actions = actions)

        c.onKeyDown(79)
        clock.now += 40
        c.onKeyUp(79)
        clock.now += 400
        advanceUntilIdle()

        assertEquals(listOf(RemoteAction.STOP_SPEAKING), actions)
    }

    @Test
    fun `two quick taps mute and the first never fires`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        c.onKeyDown(79); clock.now += 40; c.onKeyUp(79)
        clock.now += 100
        c.onKeyDown(79); clock.now += 40; c.onKeyUp(79)
        clock.now += 500
        advanceUntilIdle()

        assertEquals("the pending tap must be cancelled, not fired alongside the mute", listOf(RemoteAction.MUTE), actions)
    }

    @Test
    fun `reset drops a half finished gesture`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        c.onKeyDown(79); clock.now += 40; c.onKeyUp(79)
        c.reset()
        clock.now += 500
        advanceUntilIdle()

        assertTrue("a reset gesture must not fire later", actions.isEmpty())
        assertFalse(c.state.value.awaitingSecondPress)
    }

    @Test
    fun `the state reports that a remote has been seen`() = runTest {
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        assertFalse("nothing has been pressed yet", c.state.value.seen)
        c.onKeyDown(79); clock.now += 900; c.onKeyUp(79)
        advanceUntilIdle()

        assertTrue(c.state.value.seen)
        assertEquals(79, c.state.value.lastKeyCode)
        assertEquals("PUSH_TO_TALK", c.state.value.lastAction)
        assertEquals(1, c.state.value.presses)
    }

    @Test
    fun `a key up without a key down is still classified`() = runTest {
        // Bluetooth remotes drop events. A missing down should produce a tap, not a crash.
        val clock = Clock()
        val actions = mutableListOf<RemoteAction>()
        val c = coordinator(this, clock, actions = actions)

        c.onKeyUp(79)
        clock.now += 500
        advanceUntilIdle()

        assertEquals(listOf(RemoteAction.COMMENT_NOW), actions)
    }
}
