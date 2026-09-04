package ru.workinprogress.chronik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The observable outcome of every operation, including the ones nobody thinks about until they
 * happen in production at three in the morning.
 *
 * "What does cancel do to a timer that already fired" has to have an answer written down, because
 * the alternative is not "no answer" — it is a different answer in each implementation, discovered
 * by whoever hits it first.
 */
class ChronikOperationsTest {
    /** A store with no transactional capability: exactly what the refusal exists to catch. */
    private class NonTransactionalStore : TimerStore {
        override suspend fun findById(id: String): Timer? = null

        override suspend fun hasDue(now: EpochSeconds): Boolean = false

        override suspend fun claimDue(
            now: EpochSeconds,
            leaseUntil: EpochSeconds,
            owner: String,
            limit: Int,
        ): List<Timer> = emptyList()

        override suspend fun markFired(id: String) = Unit

        override suspend fun markFailed(
            id: String,
            retryAfter: EpochSeconds,
        ) = Unit

        override suspend fun markDeadLettered(id: String) = Unit
    }

    /**
     * The refusal happens at wiring, before anything has been scheduled.
     *
     * This is the one guard that has to fire early, because the failure it prevents is invisible
     * afterwards: the caller's state change commits, the operation succeeds, and only an event
     * nobody is waiting for right now never happens.
     */
    @Test
    fun `building on a store that cannot join a transaction fails at the wiring, not at the first schedule`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                chronik(NonTransactionalStore(), ChronikClock { EpochSeconds(0) })
            }

        // The message has to name the store, or the reader gets "requirement failed" and a stack
        // trace through a factory that could have been called from anywhere.
        assertTrue("NonTransactionalStore" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `building on a transactional store succeeds`() {
        chronik(InMemoryTimerStore(), ChronikClock { EpochSeconds(0) })
    }

    @Test
    fun `reschedule of an id that was never scheduled reports false and creates nothing`() =
        runTest {
            val store = InMemoryTimerStore()
            val chronik = Chronik(store, ChronikClock { EpochSeconds(0) })

            val tx = store.begin()
            assertFalse(chronik.reschedule(tx, "never-existed", EpochSeconds(10)))
            tx.commit()

            // False and no row: a reschedule must never be a disguised insert. A caller extending a
            // deadline for something that was already cancelled would otherwise silently bring it
            // back.
            assertNull(store.findById("never-existed"))
        }

    @Test
    fun `cancel of an id that was never scheduled reports false`() =
        runTest {
            val store = InMemoryTimerStore()
            val chronik = Chronik(store, ChronikClock { EpochSeconds(0) })

            val tx = store.begin()
            assertFalse(chronik.cancel(tx, "never-existed"))
            tx.commit()
        }

    @Test
    fun `cancel of a timer that already fired reports false and does not unfire it`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            clock.advanceTo(10)
            assertEquals(1, worker.tick())

            val late = store.begin()
            assertFalse(chronik.cancel(late, "t1"), "a fired timer is terminal; cancel cannot undo it")
            late.commit()

            assertEquals(TimerState.FIRED, store.findById("t1")?.state)
            assertEquals(1, sink.delivered.size)
        }

    @Test
    fun `reschedule of a timer that already fired reports false and does not resurrect it`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            clock.advanceTo(10)
            worker.tick()

            val later = store.begin()
            assertFalse(chronik.reschedule(later, "t1", EpochSeconds(100)))
            later.commit()

            clock.advanceTo(200)
            worker.tick()

            // Still one delivery. A reschedule that reopened a terminal timer would turn "fired
            // once" into "fires again whenever somebody touches it".
            assertEquals(1, sink.delivered.size)
        }

    @Test
    fun `cancel after the due time but before the event has gone out still works`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            // Past the due time, and no pass has run yet — the window a saga rolling back needs.
            clock.advanceTo(50)
            val cancelTx = store.begin()
            assertTrue(chronik.cancel(cancelTx, "t1"))
            cancelTx.commit()

            assertEquals(0, worker.tick())
            assertTrue(sink.delivered.isEmpty())
        }

    @Test
    fun `scheduling in the past is legal and fires on the next pass with the lateness recorded`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            clock.advanceTo(100)
            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(40), "{}")
            tx.commit()

            assertEquals(1, worker.tick())
            assertEquals(60, sink.delivered.single().lateness)
        }

    /**
     * Two operations on one id in one transaction: the second sees the first.
     *
     * Written after the model got this wrong. With writes merely buffered until commit, both calls
     * answered "true" and the second silently did nothing — while a SQL store, whose
     * `UPDATE ... WHERE state = 'PENDING'` runs inside the transaction, would have reported nought
     * rows. The oracle runs on the model, so a model that answers differently from the backend is
     * not a weaker check but a wrong one.
     */
    @Test
    fun `an operation sees an earlier operation in the same transaction`() =
        runTest {
            val store = InMemoryTimerStore()
            val chronik = Chronik(store, ChronikClock { EpochSeconds(0) })

            val setup = store.begin()
            chronik.schedule(setup, "t1", EpochSeconds(10), "{}")
            setup.commit()

            val tx = store.begin()
            assertTrue(chronik.cancel(tx, "t1"))
            assertFalse(
                chronik.reschedule(tx, "t1", EpochSeconds(99)),
                "the cancel earlier in this transaction has already made it terminal",
            )
            tx.commit()

            assertEquals(TimerState.CANCELLED, store.findById("t1")?.state)
            assertEquals(EpochSeconds(10), store.findById("t1")?.dueAt)
        }

    /** An uncommitted timer is invisible to a worker: the isolation a real store has. */
    @Test
    fun `a worker does not see a timer whose transaction has not committed`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")

            clock.advanceTo(50)
            assertEquals(0, worker.tick(), "nothing has committed yet")

            tx.commit()
            assertEquals(1, worker.tick())
        }
}
