package io.github.youndie.chronik

/**
 * Where the fact "it is time" goes.
 *
 * The contract lives in the library; the wire does not. An outbox row, an event on a broker, an
 * HTTP callback, a direct call in-process — all of them are the application's to implement, and
 * chronik must not be able to tell which it got.
 *
 * A THROW MEANS DELIVERY FAILED and the timer is retried with backoff. It does NOT mean the
 * business operation failed: the retries here deliver an event, and retrying business logic belongs
 * to whoever owns that logic. The two look identical from the outside — both are "try again in a
 * minute" — and they compensate differently, which is why the distinction is stated here rather
 * than left to be inferred.
 */
public fun interface TimerSink {
    public suspend fun deliver(fired: FiredTimer)
}

/**
 * A timer that has come due, as the sink sees it.
 *
 * Carries [lateness] because that number is worth nothing once it leaves this point: only here is
 * both the due time and the moment of firing known.
 */
public data class FiredTimer(
    public val id: String,
    public val payload: String,
    public val dueAt: EpochSeconds,
    public val firedAt: EpochSeconds,
    /** Seconds between the due time and this firing; zero when it fired in its own second. */
    public val lateness: Long,
)
