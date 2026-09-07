package io.github.youndie.chronik

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one strict promise, pinned at its boundary: never before the due second.
 *
 * Every other promise chronik makes is weaker than this one — at-least-once allows a repeat, the
 * lease allows a delay — so this is the boundary where being wrong is not recoverable by any
 * later behaviour.
 */
class TimerBoundaryTest {
    private val timer = Timer(id = "t1", dueAt = EpochSeconds(100), payload = "{}")

    @Test
    fun `not claimable one second before its due time`() {
        assertFalse(timer.isClaimableAt(EpochSeconds(99)))
    }

    @Test
    fun `claimable in its own due second`() {
        assertTrue(timer.isClaimableAt(EpochSeconds(100)))
    }

    @Test
    fun `claimable after its due time`() {
        assertTrue(timer.isClaimableAt(EpochSeconds(101)))
    }

    @Test
    fun `a live lease hides it, an expired one does not`() {
        val held = timer.copy(lockedUntil = EpochSeconds(200), lockedBy = "worker-0")

        assertFalse(held.isClaimableAt(EpochSeconds(150)))
        // In the second the lease expires it is still held: the holder had that whole second.
        assertFalse(held.isClaimableAt(EpochSeconds(200)))
        assertTrue(held.isClaimableAt(EpochSeconds(201)))
    }

    @Test
    fun `a terminal timer is never claimable, however far past its due time`() {
        for (state in listOf(TimerState.FIRED, TimerState.CANCELLED, TimerState.DEAD_LETTERED)) {
            assertFalse(timer.copy(state = state).isClaimableAt(EpochSeconds(10_000)), "$state")
        }
    }

    @Test
    fun `lateness is zero in the due second and never negative`() {
        assertEquals(0, timer.latenessAt(EpochSeconds(100)))
        assertEquals(5, timer.latenessAt(EpochSeconds(105)))
        // Firing before the due time cannot happen; if it somehow did, it is not negative lateness.
        assertEquals(0, timer.latenessAt(EpochSeconds(90)))
    }
}
