package ru.workinprogress.chronik

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Claim what is due, deliver it, record that it fired.
 *
 * Several of these run at once against one store, with no leader and no coordinator: they compete
 * for rows, and the store's claim is what keeps them off each other. The worker itself holds no
 * state that a restart would need — [lease] in the row is what survives.
 *
 * Resilience is deliberate and copied from a worker that has been in production: one timer failing
 * does not sink the batch, and a storage failure between passes does not sink the worker. An
 * unfired timer is not going anywhere and the next pass picks it up, so "log and carry on" loses
 * nothing here.
 */
public class TimerWorker(
    private val store: TimerStore,
    private val sink: TimerSink,
    private val clock: ChronikClock,
    /**
     * Who this worker is, in the lease. Must differ between instances, or a claim tells you nothing
     * about who is holding the row.
     */
    private val owner: String,
    /**
     * How long a claim holds, in seconds.
     *
     * This is the delay a killed worker costs, and the only bound on it. Too short and a slow sink
     * gets its timer taken away mid-delivery — harmless under at-least-once, wasteful in practice.
     * Too long and a crash parks the timer for that whole time.
     */
    private val leaseSeconds: Long = 30,
    private val pollInterval: Duration = 1.seconds,
    private val batchSize: Int = 50,
    /**
     * Every firing, with how late it was in seconds.
     *
     * Lateness leaves here or nowhere: only at this point are both the due time and the moment of
     * firing known. It is also the number that says whether the worker is keeping up — delivery
     * latency on its own cannot tell "fired in its own second" from "picked up forty minutes after
     * the process came back".
     */
    private val onFired: (id: String, lateness: Long) -> Unit = { _, _ -> },
    /**
     * How many delivery attempts a timer gets before it goes to the dead letter.
     *
     * A systematic failure — a misconfigured transport, a receiver that has been removed — must not
     * keep an event burning for ever.
     */
    private val maxAttempts: Int = 5,
    /** The first retry waits this long; each subsequent one doubles, up to [maxBackoffSeconds]. */
    private val baseBackoffSeconds: Long = 1,
    /**
     * The ceiling on the wait between attempts.
     *
     * Without it the delay grows without bound for a timer that fails long and systematically,
     * which means the last few attempts before the dead letter happen days apart and the operator
     * sees a timer that is neither delivered nor given up on.
     */
    private val maxBackoffSeconds: Long = 300,
    /** A delivery that threw. The timer is held until its backoff expires, then retried. */
    private val onDeliveryFailed: (id: String, cause: Throwable) -> Unit = { _, _ -> },
    /**
     * Attempts exhausted; the timer is terminal and will not be tried again.
     *
     * Reported because this is the one outcome nobody finds by looking at what fired: the timer
     * simply stops existing as far as every other query is concerned.
     */
    private val onDeadLettered: (id: String, attempts: Int) -> Unit = { _, _ -> },
    /**
     * Something failed that is not one timer's delivery: the storage refused a pass, or recording
     * an outcome did not go through.
     *
     * These used to be swallowed with a `TODO: log this`. The worker survives them by design — an
     * unfired timer is not going anywhere — but surviving is not the same as being invisible: a
     * worker whose storage has been refusing every pass for an hour looks EXACTLY like an idle one
     * from outside, and that is the only state where this library is silently doing nothing.
     *
     * chronik has no logger of its own and will not grow one; whoever wires it up has one.
     */
    private val onWorkerFailure: (stage: String, cause: Throwable) -> Unit = { _, _ -> },
    /**
     * A pass that claimed nothing while timers were due — this worker lost the race to another.
     *
     * Reported because a lease whose losers are invisible cannot be told from a lease that never
     * engages: both look like "one worker does all the work", and only one of them is correct.
     *
     * NULL BY DEFAULT, AND THAT IS NOT A STYLE. Answering it costs a second query, and an idle
     * worker polls whether or not anybody is listening. A no-op lambda would hide that cost behind
     * a default; null makes it explicit that the query happens only for someone who asked.
     */
    private val onLostRace: (() -> Unit)? = null,
) {
    public fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A transient storage failure, not a failure of any one timer; the next pass
                    // tries again — and somebody is told, because a worker that has been failing
                    // every pass looks idle from outside.
                    onWorkerFailure("poll", e)
                }
                delay(pollInterval)
            }
        }

    /**
     * One pass. Returns how many timers fired.
     *
     * Exposed separately from [start] because that is what makes this testable at all: firing is
     * checked by invoking a pass and moving the clock, never by sleeping on the real one and hoping
     * the worker woke the required number of times. On a loaded machine that hope does not come
     * true, and the test that relies on it fails for reasons that have nothing to do with the code.
     */
    public suspend fun tick(): Int {
        val now = clock.now()
        val claimed = store.claimDue(now, now + leaseSeconds, owner, batchSize)

        if (claimed.isEmpty()) {
            if (onLostRace != null && store.hasDue(now)) onLostRace.invoke()
            return 0
        }

        var fired = 0
        for (timer in claimed) {
            // Read again per timer: delivery of the previous one may have taken a while, and the
            // lateness of this one is what it is at the moment it actually goes out, not at the
            // moment the batch was claimed.
            val firedAt = clock.now()
            try {
                sink.deliver(
                    FiredTimer(
                        id = timer.id,
                        payload = timer.payload,
                        dueAt = timer.dueAt,
                        firedAt = firedAt,
                        lateness = timer.latenessAt(firedAt),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDeliveryFailed(timer.id, e)
                recordFailure(timer, firedAt, e)
                continue
            }

            try {
                store.markFired(timer.id)
                fired++
                onFired(timer.id, timer.latenessAt(firedAt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The event went out and recording it did not. The lease lapses and another pass
                // delivers it again — which is exactly what at-least-once means and why the
                // receiver must deduplicate on the key in the payload. Losing the timer here would
                // be the worse trade, and staying quiet about it would hide a duplicate that has
                // an explanation.
                onWorkerFailure("mark-fired:${timer.id}", e)
            }
        }
        return fired
    }

    private suspend fun recordFailure(
        timer: Timer,
        now: EpochSeconds,
        cause: Throwable,
    ) {
        val attempts = timer.attempts + 1
        try {
            if (attempts >= maxAttempts) {
                store.markDeadLettered(timer.id)
                onDeadLettered(timer.id, attempts)
            } else {
                store.markFailed(timer.id, now + backoffSeconds(attempts))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Recording the failure failed too. The timer keeps the lease it already has and comes
            // back when that lapses, so nothing is lost — only the backoff for this one attempt.
            // Both causes go out: the delivery's and this one's, or the second hides the first.
            onWorkerFailure("record-failure:${timer.id}:${cause::class.simpleName}", e)
        }
    }

    /**
     * Exponential from [baseBackoffSeconds], capped at [maxBackoffSeconds].
     *
     * The shift is clamped before it is taken: `1L shl 64` is not a very large number, it is 1, and
     * a backoff that wraps round to "immediately" on the sixty-fifth attempt is the kind of defect
     * that only appears in a system that has been failing for a very long time.
     */
    internal fun backoffSeconds(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 40)
        val scaled = baseBackoffSeconds * (1L shl exponent)
        return if (scaled > maxBackoffSeconds || scaled < 0) maxBackoffSeconds else scaled
    }
}
