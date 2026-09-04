package ru.workinprogress.chronik

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE SPECIFICATION, AS ONE ASSERTION.
 *
 * For any sequence of schedule / cancel / reschedule, with commits, rollbacks and crashes injected
 * anywhere between "the row is written" and "the event has gone out":
 *
 *   1. the set that fired equals the set that reached its due time and was not cancelled;
 *   2. nothing fired before its due second;
 *   3. every firing's lateness is the difference between its due time and the moment it went out.
 *
 * Everything else in this library is an implementation under that. The scenarios in
 * docs/features/feature-durable-timer.md are readable special cases of it, and this is the general
 * statement they are cases of — written from that document rather than from the code, so that it
 * checks the specification instead of checking its own answer.
 *
 * WHAT IT DOES NOT CHECK, deliberately: `SKIP LOCKED`, the real lifetime of a row lock, and what a
 * transaction does on a killed connection. This runs on a model, where the order of operations and
 * the point of the crash can be enumerated — which a stand cannot do — and a model is not Postgres.
 * B-08 owns that half, and neither half substitutes for the other.
 */
class OracleTest {
    private companion object {
        /** Sequences per run. The acceptance criterion for B-02 is at least a thousand. */
        const val SEQUENCES = 1_200

        const val OPS_PER_SEQUENCE = 12
        const val HORIZON = 40L
    }

    private enum class Op { SCHEDULE, CANCEL, RESCHEDULE }

    /**
     * A second, independent statement of the rule — not a view of the store.
     *
     * It has to be a real model rather than a set of ids, and the first run said why: a timer that
     * has already fired cannot be cancelled, so "committed and not cancelled" is not the same set
     * as "fired". Any expectation that does not know when things fire disagrees with a correct
     * implementation, and then the disagreement has to be adjudicated by hand every time.
     *
     * So this tracks firing too, computed from the rule in the feature document — due and not
     * cancelled fires on the next pass — rather than read back out of the store. Two derivations of
     * the same answer is the whole value; one derivation checked against itself is worth nothing.
     */
    private class Expectation {
        /** id -> due time, for timers that committed, have not been cancelled and have not fired. */
        val live = mutableMapOf<String, EpochSeconds>()
        val fired = mutableSetOf<String>()
        val cancelled = mutableSetOf<String>()

        /** Everything live and due has gone out by now. */
        fun passAt(now: EpochSeconds) {
            val due = live.filterValues { it <= now }.keys.toList()
            due.forEach { live.remove(it) }
            fired += due
        }
    }

    @Test
    fun `for any sequence, what fired is exactly what came due and was not cancelled`() =
        runTest {
            val random = Random(20260904)

            repeat(SEQUENCES) { seed ->
                val store = InMemoryTimerStore()
                val clock = TestClock()
                val sink = RecordingSink()
                val chronik = Chronik(store, clock)
                val worker = TimerWorker(store, sink, clock, owner = "w", leaseSeconds = 5)
                val expected = Expectation()

                var nextId = 0
                var now = 0L
                repeat(OPS_PER_SEQUENCE) {
                    val tx = store.begin()

                    when (Op.entries[random.nextInt(Op.entries.size)]) {
                        Op.SCHEDULE -> {
                            val id = "t${nextId++}"
                            val due = EpochSeconds(random.nextLong(HORIZON))
                            chronik.schedule(tx, id, due, """{"k":"$id"}""")
                            // The outcome of the caller's transaction, which is the only thing that
                            // decides whether this timer exists. A crash is a rollback the caller
                            // never asked for, and from storage they are indistinguishable — which
                            // is precisely the guarantee being tested.
                            if (random.nextInt(3) != 0) {
                                tx.commit()
                                expected.live[id] = due
                            } else {
                                tx.rollback()
                            }
                        }

                        Op.CANCEL -> {
                            val id = expected.live.keys.randomOrNull(random) ?: return@repeat
                            chronik.cancel(tx, id)
                            if (random.nextBoolean()) {
                                tx.commit()
                                expected.live.remove(id)
                                expected.cancelled += id
                            } else {
                                tx.rollback()
                            }
                        }

                        Op.RESCHEDULE -> {
                            val id = expected.live.keys.randomOrNull(random) ?: return@repeat
                            val due = EpochSeconds(random.nextLong(HORIZON))
                            chronik.reschedule(tx, id, due)
                            if (random.nextBoolean()) {
                                tx.commit()
                                expected.live[id] = due
                            } else {
                                tx.rollback()
                            }
                        }
                    }

                    // A pass at a random moment, so firing interleaves with the operations
                    // rather than happening tidily after all of them. Forward only: a clock that
                    // goes back is not a case this library has to survive, and generating one would
                    // be the harness testing itself.
                    now += random.nextLong(HORIZON / 4)
                    clock.advanceTo(now)
                    worker.tick()
                    expected.passAt(EpochSeconds(now))
                }

                // Past every due time, and past any lease that a mid-sequence pass took out.
                val end = maxOf(now, HORIZON) + HORIZON
                clock.advanceTo(end)
                repeat(3) { worker.tick() }
                expected.passAt(EpochSeconds(end))

                val firedIds = sink.delivered.map { it.id }.toSet()
                val shouldHaveFired = expected.fired

                assertEquals(
                    shouldHaveFired,
                    firedIds,
                    "seed $seed: fired set differs from what came due and was not cancelled",
                )

                for (fired in sink.delivered) {
                    assertTrue(
                        fired.firedAt >= fired.dueAt,
                        "seed $seed: ${fired.id} fired at ${fired.firedAt}, before its due ${fired.dueAt}",
                    )
                    assertEquals(
                        fired.firedAt - fired.dueAt,
                        fired.lateness,
                        "seed $seed: ${fired.id} reports lateness ${fired.lateness}",
                    )
                }

                for (id in expected.cancelled) {
                    assertTrue(id !in firedIds, "seed $seed: cancelled $id fired anyway")
                }
            }
        }

    /**
     * A rolled-back transaction leaves no timer, however far the clock then runs.
     *
     * Called out on its own although the property above covers it, because this is the one failure
     * that is invisible from every other angle: the caller's state change commits, the operation
     * succeeds, and only an event nobody is waiting for right now never happens.
     */
    @Test
    fun `a rollback takes the timer with it`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val sink = RecordingSink()
            val chronik = Chronik(store, clock)
            val worker = TimerWorker(store, sink, clock, owner = "w")

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.rollback()

            clock.advanceTo(100)
            worker.tick()

            assertTrue(sink.delivered.isEmpty())
            assertEquals(null, store.findById("t1"))
        }

    /** A crash between the row and the event: the lease lapses and somebody else delivers it once. */
    @Test
    fun `a worker that dies holding a timer delays it by the lease and no more`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val liveSink = RecordingSink()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            clock.advanceTo(10)
            // The doomed worker claims the timer and disappears before delivering: no unwinding, no
            // release, nothing written back.
            store.claimDue(clock.now(), clock.now() + 5, owner = "doomed", limit = 10)

            val survivor = TimerWorker(store, liveSink, clock, owner = "survivor", leaseSeconds = 5)

            clock.advanceTo(13)
            assertEquals(0, survivor.tick(), "the lease is still live at 13; nobody may take it")

            clock.advanceTo(16)
            assertEquals(1, survivor.tick(), "the lease lapsed at 15; the survivor takes it")
            assertEquals(listOf("t1"), liveSink.delivered.map { it.id })
            assertEquals(6, liveSink.delivered.single().lateness)

            clock.advanceTo(100)
            survivor.tick()
            assertEquals(1, liveSink.delivered.size, "delivered once, not once per pass")
        }

    private fun <T> Collection<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else elementAt(random.nextInt(size))
}
