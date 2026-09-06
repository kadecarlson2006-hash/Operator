package com.operator.core.camera

import com.operator.core.model.OperatorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraContextPolicyTest {

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun policy(clock: FakeClock = FakeClock()) = CameraContextPolicy(clock = clock) to clock

    private fun ok() = LookRequest(
        trigger = LookTrigger.USER_REQUEST,
        mode = OperatorMode.ACTIVE,
        cameraPermissionGranted = true,
        glassesReady = true,
    )

    @Test
    fun `a requested look with permission and glasses is allowed`() {
        val (p, _) = policy()
        assertNull(p.gate(ok()))
    }

    @Test
    fun `mute stops it`() {
        val (p, _) = policy()
        assertEquals(CameraRefusal.MUTED, p.gate(ok().copy(muted = true)))
    }

    @Test
    fun `OFF stops it`() {
        val (p, _) = policy()
        assertEquals(CameraRefusal.MODE_OFF, p.gate(ok().copy(mode = OperatorMode.OFF)))
    }

    @Test
    fun `no camera permission stops it before anything is captured`() {
        val (p, _) = policy()
        assertEquals(CameraRefusal.NO_PERMISSION, p.gate(ok().copy(cameraPermissionGranted = false)))
    }

    @Test
    fun `glasses that are not ready stop it`() {
        val (p, _) = policy()
        assertEquals(CameraRefusal.GLASSES_UNAVAILABLE, p.gate(ok().copy(glassesReady = false)))
    }

    @Test
    fun `mute is checked before permission, so the refusal names the user's own choice`() {
        val (p, _) = policy()
        val request = ok().copy(muted = true, cameraPermissionGranted = false, glassesReady = false)
        assertEquals(CameraRefusal.MUTED, p.gate(request))
    }

    @Test
    fun `a second look too soon is refused`() {
        val (p, clock) = policy()
        assertNull(p.gate(ok()))
        p.recordLook()
        clock.now += 1_000
        assertEquals(CameraRefusal.TOO_SOON, p.gate(ok()))
    }

    @Test
    fun `the interval passes and looking is allowed again`() {
        val (p, clock) = policy()
        p.recordLook()
        clock.now += CameraContextPolicy.DEFAULT_MIN_INTERVAL_SECONDS * 1_000L
        assertNull(p.gate(ok()))
    }

    @Test
    fun `the five minute budget is enforced`() {
        val (p, clock) = policy()
        repeat(CameraContextPolicy.DEFAULT_MAX_PER_5_MINUTES) {
            p.recordLook(clock.now)
            clock.now += CameraContextPolicy.DEFAULT_MIN_INTERVAL_SECONDS * 1_000L
        }
        assertEquals(CameraRefusal.BUDGET, p.gate(ok()))
    }

    @Test
    fun `looks older than the window stop counting`() {
        val (p, clock) = policy()
        // Spaced by the interval, or the interval check refuses first and the budget is never
        // reached - which is correct behaviour, and was what this test originally caught itself on.
        repeat(CameraContextPolicy.DEFAULT_MAX_PER_5_MINUTES) {
            p.recordLook(clock.now)
            clock.now += CameraContextPolicy.DEFAULT_MIN_INTERVAL_SECONDS * 1_000L
        }
        assertEquals(CameraRefusal.BUDGET, p.gate(ok()))

        clock.now += 6 * 60 * 1_000L
        assertNull(p.gate(ok()))
        assertEquals(0, p.recentLookCount())
    }

    @Test
    fun `the interval is checked before the budget`() {
        // Both are refusals, so which one wins only matters for the label the user is shown, and
        // "too soon" is the more useful of the two when they have just taken a picture.
        val (p, clock) = policy()
        repeat(CameraContextPolicy.DEFAULT_MAX_PER_5_MINUTES) { p.recordLook(clock.now) }
        assertEquals(CameraRefusal.TOO_SOON, p.gate(ok()))
    }

    @Test
    fun `bad configuration is refused at construction`() {
        assertTrue(runCatching { CameraContextPolicy(minIntervalSeconds = -1) }.isFailure)
        assertTrue(runCatching { CameraContextPolicy(maxLooksPer5Minutes = -1) }.isFailure)
    }

    @Test
    fun `there is exactly one trigger, and it is the user asking`() {
        // The guard against a future ambient path being added without reading ADR-048. If this
        // fails because a value was added to the enum, that is the conversation, not a fix.
        assertEquals(listOf(LookTrigger.USER_REQUEST), LookTrigger.entries.toList())
    }
}

class VisionConstraintsTest {

    @Test
    fun `the rules forbid identifying anyone`() {
        val text = VisionConstraints.RULES.joinToString(" ").lowercase()
        assertTrue(text.contains("do not identify anyone"), "identification must be forbidden outright")
        assertTrue(text.contains("appearance"), "describing appearance must be forbidden")
    }

    @Test
    fun `the rules forbid reading personal information out of the scene`() {
        val text = VisionConstraints.RULES.joinToString(" ").lowercase()
        assertTrue(text.contains("badges") || text.contains("documents"), "expected a rule about visible personal data")
    }

    @Test
    fun `the prompt block carries every rule`() {
        val block = VisionConstraints.promptBlock()
        VisionConstraints.RULES.forEach {
            assertTrue(block.contains(it), "the prompt block dropped a rule: $it")
        }
    }

    @Test
    fun `a description must say something`() {
        assertTrue(runCatching { SceneDescription(text = "  ") }.isFailure)
    }
}
