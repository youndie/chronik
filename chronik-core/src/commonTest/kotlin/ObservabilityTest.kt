package ru.workinprogress.chronik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What a worker tells the outside world, and what it costs to ask.
 *
 * Both halves are load-bearing. Lateness is the number that says whether the worker is keeping up,
 * and it can only be computed here. The lost race is the number that says whether the lease engages
 * at all — a lease whose losers are invisible looks exactly like a lease that never fires, and only
 * one of those is correct.
 */
class ObservabilityTest {
    @Test
    fun `timers overdue from a restart fire with the lateness they actually accrued`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "early", EpochSeconds(10), "{}")
            chronik.schedule(tx, "later", EpochSeconds(40), "{}")
            tx.commit()

            // The process was down from second 10 to second 100. Both timers came due while it was
            // gone, which is a supported outcome and not an error — but an outcome the operator has
            // to be able to see, because it is the difference between "we were down" and "we are
            // silently not firing".
            clock.advanceTo(100)

            val seen = mutableMapOf<String, Long>()
            val worker =
                TimerWorker(
                    store,
                    RecordingSink(),
                    clock,
                    owner = "after-restart",
                    onFired = { id, lateness -> seen[id] = lateness },
                )

            assertEquals(2, worker.tick())
            assertEquals(mapOf("early" to 90L, "later" to 60L), seen)
        }

    @Test
    fun `a timer that fires in its own second reports zero, which is not the same as unreported`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            val seen = mutableListOf<Pair<String, Long>>()
            val worker =
                TimerWorker(store, RecordingSink(), clock, owner = "w", onFired = { id, l -> seen += id to l })

            clock.advanceTo(10)
            worker.tick()

            assertEquals(listOf("t1" to 0L), seen)
        }

    @Test
    fun `the loser of a race says so`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            var lost = 0
            val loser =
                TimerWorker(store, RecordingSink(), clock, owner = "slow", onLostRace = { lost++ })

            clock.advanceTo(10)
            // Somebody else gets there first and holds it.
            store.claimDue(clock.now(), clock.now() + 30, owner = "fast", limit = 10)

            assertEquals(0, loser.tick())
            assertEquals(1, lost, "claimed nothing while something was due — that is a loss, not an idle pass")
        }

    @Test
    fun `an idle pass with nothing due is not reported as a loss`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()

            var lost = 0
            val worker =
                TimerWorker(store, RecordingSink(), clock, owner = "w", onLostRace = { lost++ })

            clock.advanceTo(10)
            assertEquals(0, worker.tick())
            assertEquals(0, lost, "nothing was due; nobody lost anything")
        }

    /**
     * The opt-in is real, not decorative.
     *
     * `hasDue` is a second query on every idle pass, and an idle worker polls whether or not anyone
     * is listening. The claim in the KDoc is that a worker without a lost-race handler never asks —
     * and a claim about a cost nobody measures is how the cost gets paid for ever.
     */
    @Test
    fun `a worker with no lost-race handler never asks the extra question`() =
        runTest {
            val refusing =
                object : TimerStore by InMemoryTimerStore() {
                    override suspend fun hasDue(now: EpochSeconds): Boolean =
                        fail("hasDue was queried although no lost-race handler was given")
                }

            val worker = TimerWorker(refusing, RecordingSink(), TestClock(), owner = "w")
            assertEquals(0, worker.tick())
        }

    @Test
    fun `a delivery that throws is reported and leaves the timer to be retried`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            val failures = mutableListOf<String>()
            val refusingSink = TimerSink { error("the broker is down") }
            val worker =
                TimerWorker(
                    store,
                    refusingSink,
                    clock,
                    owner = "w",
                    leaseSeconds = 5,
                    onDeliveryFailed = { id, _ -> failures += id },
                )

            clock.advanceTo(10)
            assertEquals(0, worker.tick())
            assertEquals(listOf("t1"), failures)

            // Still PENDING, still there: a failed delivery is not a fired timer.
            assertEquals(TimerState.PENDING, store.findById("t1")?.state)

            // And once the lease lapses, a working sink gets it.
            val good = RecordingSink()
            val retry = TimerWorker(store, good, clock, owner = "w2", leaseSeconds = 5)
            clock.advanceTo(20)
            assertEquals(1, retry.tick())
            assertEquals(listOf("t1"), good.delivered.map { it.id })
        }

    @Test
    fun `one failing delivery does not sink the rest of the batch`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "bad", EpochSeconds(1), "{}")
            chronik.schedule(tx, "good", EpochSeconds(2), "{}")
            tx.commit()

            val delivered = mutableListOf<String>()
            val pickySink =
                TimerSink { fired ->
                    if (fired.id == "bad") error("no") else delivered += fired.id
                }
            val worker = TimerWorker(store, pickySink, clock, owner = "w")

            clock.advanceTo(10)
            assertEquals(1, worker.tick())
            assertEquals(listOf("good"), delivered)
            assertTrue(store.findById("good")?.state == TimerState.FIRED)
            assertFalse(store.findById("bad")?.state == TimerState.FIRED)
        }

    /**
     * A storage that refuses every pass is reported, not swallowed.
     *
     * The worker surviving this is deliberate — an unfired timer is not going anywhere. But
     * surviving is not the same as being invisible: a worker whose storage has been refusing for an
     * hour looks exactly like an idle one from outside, and that is the only state in which this
     * library is quietly doing nothing at all.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a storage failure during a pass is reported rather than swallowed`() =
        runTest {
            val refusing =
                object : TimerStore by InMemoryTimerStore() {
                    override suspend fun claimDue(
                        now: EpochSeconds,
                        leaseUntil: EpochSeconds,
                        owner: String,
                        limit: Int,
                    ): List<Timer> = error("the database is gone")
                }

            val failures = mutableListOf<Pair<String, String>>()
            val worker =
                TimerWorker(
                    refusing,
                    RecordingSink(),
                    TestClock(),
                    owner = "w",
                    onWorkerFailure = { stage, cause -> failures += stage to (cause.message ?: "") },
                )

            // Through start(), because tick() lets the exception out and it is the LOOP that
            // swallows it — testing tick() would prove nothing about the thing being fixed.
            val job = worker.start(this)
            testScheduler.advanceTimeBy(2_500)
            job.cancel()

            assertTrue(failures.isNotEmpty(), "the storage refused every pass and nobody was told")
            assertEquals("poll", failures.first().first)
            assertEquals("the database is gone", failures.first().second)
        }

    @Test
    fun `a failure to record a firing is reported, since it explains a duplicate`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            val chronik = Chronik(store, clock)

            val tx = store.begin()
            chronik.schedule(tx, "t1", EpochSeconds(10), "{}")
            tx.commit()

            val refusingMark =
                object : TimerStore by store {
                    override suspend fun markFired(id: String) = error("the update failed")
                }

            val failures = mutableListOf<String>()
            val sink = RecordingSink()
            val worker =
                TimerWorker(
                    refusingMark,
                    sink,
                    clock,
                    owner = "w",
                    onWorkerFailure = { stage, _ -> failures += stage },
                )

            clock.advanceTo(10)
            worker.tick()

            // The event went out; only recording it failed. Both facts matter, and the second is
            // the one that explains the duplicate the next pass will produce.
            assertEquals(listOf("t1"), sink.delivered.map { it.id })
            assertEquals(listOf("mark-fired:t1"), failures)
        }
}
