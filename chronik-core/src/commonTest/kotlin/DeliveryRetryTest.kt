package io.github.youndie.chronik

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens to a timer whose delivery keeps failing.
 *
 * The distinction this file is really about: these are retries of a DELIVERY, not of anybody's
 * business logic. From the outside both look like "try again in a minute", and they compensate
 * differently — which is why a sink must not throw because the operation behind it failed.
 */
class DeliveryRetryTest {
    private class CountingSink(
        private val failuresBeforeSuccess: Int,
    ) : TimerSink {
        var attempts = 0
        val delivered = mutableListOf<String>()

        override suspend fun deliver(fired: FiredTimer) {
            attempts++
            if (attempts <= failuresBeforeSuccess) error("the broker is down (attempt $attempts)")
            delivered += fired.id
        }
    }

    private fun scheduled(
        store: InMemoryTimerStore,
        clock: TestClock,
        at: Long = 10,
    ) = Chronik(store, clock).let { chronik ->
        val tx = store.begin()
        kotlinx.coroutines.runBlocking { chronik.schedule(tx, "t1", EpochSeconds(at), "{}") }
        tx.commit()
    }

    @Test
    fun `a failed delivery counts an attempt and holds the timer until the backoff expires`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            scheduled(store, clock)

            val sink = CountingSink(failuresBeforeSuccess = 1)
            val worker =
                TimerWorker(
                    store,
                    sink,
                    clock,
                    owner = "w",
                    leaseSeconds = 30,
                    baseBackoffSeconds = 4,
                    maxBackoffSeconds = 60,
                )

            clock.advanceTo(10)
            assertEquals(0, worker.tick())
            assertEquals(1, store.findById("t1")?.attempts)

            // The first backoff is the base: 4 seconds from the failure at second 10.
            clock.advanceTo(13)
            assertEquals(0, worker.tick(), "still inside the backoff")
            assertEquals(1, sink.attempts, "no second attempt was made")

            clock.advanceTo(15)
            assertEquals(1, worker.tick())
            assertEquals(listOf("t1"), sink.delivered)
        }

    @Test
    fun `the wait between attempts doubles and then stops at the ceiling`() {
        val worker =
            TimerWorker(
                InMemoryTimerStore(),
                RecordingSink(),
                TestClock(),
                owner = "w",
                baseBackoffSeconds = 2,
                maxBackoffSeconds = 20,
            )

        assertEquals(listOf(2L, 4L, 8L, 16L, 20L, 20L), (1..6).map { worker.backoffSeconds(it) })
    }

    /**
     * The shift is clamped, so a very long-running failure does not wrap round to "immediately".
     *
     * `1L shl 64` is 1, not a very large number. Nothing in a normal run reaches an attempt count
     * that high, which is exactly why nothing would catch it.
     */
    @Test
    fun `an absurd attempt count still yields the ceiling, not a wrapped-around zero`() {
        val worker =
            TimerWorker(
                InMemoryTimerStore(),
                RecordingSink(),
                TestClock(),
                owner = "w",
                baseBackoffSeconds = 1,
                maxBackoffSeconds = 300,
            )

        for (attempt in listOf(60, 64, 65, 100, Int.MAX_VALUE)) {
            assertEquals(300L, worker.backoffSeconds(attempt), "attempt $attempt")
        }
    }

    @Test
    fun `after the last attempt the timer is dead lettered and never selected again`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            scheduled(store, clock)

            val sink = CountingSink(failuresBeforeSuccess = Int.MAX_VALUE)
            val deadLettered = mutableListOf<Pair<String, Int>>()
            val worker =
                TimerWorker(
                    store,
                    sink,
                    clock,
                    owner = "w",
                    maxAttempts = 3,
                    baseBackoffSeconds = 1,
                    maxBackoffSeconds = 10,
                    onDeadLettered = { id, attempts -> deadLettered += id to attempts },
                )

            var now = 10L
            repeat(3) {
                clock.advanceTo(now)
                worker.tick()
                now += 100 // well past any backoff
            }

            assertEquals(3, sink.attempts, "exactly maxAttempts attempts were made")
            assertEquals(listOf("t1" to 3), deadLettered)
            assertEquals(TimerState.DEAD_LETTERED, store.findById("t1")?.state)

            // And it is gone from every selection, however long we wait.
            clock.advanceTo(100_000)
            assertEquals(0, worker.tick())
            assertEquals(3, sink.attempts, "a dead lettered timer is not attempted again")
            assertTrue(!store.hasDue(EpochSeconds(100_000)))
        }

    @Test
    fun `a timer that succeeds before the last attempt never reaches the dead letter`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            scheduled(store, clock)

            val sink = CountingSink(failuresBeforeSuccess = 2)
            var deadLettered = 0
            val worker =
                TimerWorker(
                    store,
                    sink,
                    clock,
                    owner = "w",
                    maxAttempts = 3,
                    baseBackoffSeconds = 1,
                    maxBackoffSeconds = 10,
                    onDeadLettered = { _, _ -> deadLettered++ },
                )

            var now = 10L
            repeat(3) {
                clock.advanceTo(now)
                worker.tick()
                now += 100
            }

            assertEquals(listOf("t1"), sink.delivered)
            assertEquals(0, deadLettered)
            assertEquals(TimerState.FIRED, store.findById("t1")?.state)
        }

    /**
     * The backoff is in the row, so a DIFFERENT instance honours it too.
     *
     * This is the whole reason it is not kept in the worker's memory. With an in-memory interval,
     * the instance that failed would wait politely while every other instance walked straight past
     * it the moment the lease lapsed — and the more instances there are, the less the backoff would
     * mean, which is the opposite of what it is for.
     */
    @Test
    fun `a second worker honours the backoff written by the first`() =
        runTest {
            val store = InMemoryTimerStore()
            val clock = TestClock()
            scheduled(store, clock)

            val failing = CountingSink(failuresBeforeSuccess = Int.MAX_VALUE)
            val first =
                TimerWorker(
                    store,
                    failing,
                    clock,
                    owner = "w1",
                    leaseSeconds = 1,
                    baseBackoffSeconds = 30,
                    maxBackoffSeconds = 60,
                )

            clock.advanceTo(10)
            first.tick()

            val second = RecordingSink()
            val other = TimerWorker(store, second, clock, owner = "w2", leaseSeconds = 1)

            // Well past w1's one-second lease, well inside the thirty-second backoff.
            clock.advanceTo(20)
            assertEquals(0, other.tick(), "the backoff is the hold, and it is not w1's private note")
            assertTrue(second.delivered.isEmpty())

            clock.advanceTo(41)
            assertEquals(1, other.tick())
            assertEquals(listOf("t1"), second.delivered.map { it.id })
        }
}
