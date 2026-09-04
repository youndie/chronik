package ru.workinprogress.chronik

/**
 * The caller's unit of work, which chronik never creates.
 *
 * It is an opaque handle rather than anything with methods, because chronik has no business
 * committing, rolling back, or even knowing what a transaction is made of. All it needs is to be
 * handed one and to write inside it.
 */
public interface TimerTransaction

/**
 * Reading and writing timers. Everything here is storage; no policy lives in this interface.
 */
public interface TimerStore {
    public suspend fun findById(id: String): Timer?

    /**
     * Timers that are due and not held by a live lease, claimed for [owner] until [leaseUntil].
     *
     * Claiming is part of the query rather than a second call on purpose: between a read and a
     * separate write another instance fits, and the whole point of this method is that one does
     * not.
     */
    public suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer>

    /**
     * Is any timer past its due time and not yet terminal — REGARDLESS of who holds it?
     *
     * "Regardless of who holds it" is the whole definition, and getting it wrong makes the method
     * useless in the one case it exists for. It answers a question a worker cannot otherwise
     * answer: it claimed nothing — was there nothing to do, or did another instance get there
     * first? A worker that has just lost the race is looking at rows that ARE claimed, so a
     * predicate reading "due and unclaimed" answers "nothing due" to the loser and reports no loss.
     * That was the first shape of this method, and the test for the loser is what said so.
     *
     * Called only by a worker that was given a lost-race handler, because on a busy store this is
     * a query per idle pass.
     */
    public suspend fun hasDue(now: EpochSeconds): Boolean

    public suspend fun markFired(id: String)

    /**
     * A delivery attempt failed: count it, and hold the timer until [retryAfter].
     *
     * THE HOLD IS THE BACKOFF, and it lives in the row rather than in the worker's memory. That is
     * a departure from how the neighbouring outbox relay does it, and the reason is a difference
     * between the two: the outbox relay is the only reader of its rows, so remembering "not before
     * 10:04" in a map is enough. Here several instances compete for the same row, and an interval
     * one of them is privately observing is one the others walk straight past the moment the lease
     * lapses. A backoff that only the failing instance honours is not a backoff.
     */
    public suspend fun markFailed(
        id: String,
        retryAfter: EpochSeconds,
    )

    /**
     * Attempts are exhausted. Terminal, and unlike a failure this one is never selected again.
     *
     * Such a timer needs a person. What it must not do is keep burning attempts, connections and
     * log space on a delivery that has already failed the same way five times.
     */
    public suspend fun markDeadLettered(id: String)
}

/**
 * A store that can write a timer inside a transaction the caller already opened.
 *
 * A SEPARATE INTERFACE, AND THAT IS THE DESIGN. Putting these methods on [TimerStore] would force
 * every test double to implement them while exactly one consumer needs them — and, far more
 * important, it would make the capability invisible: an application would wire up a store that
 * cannot join its transaction, everything would compile, every test would pass, and the guarantee
 * chronik exists for would silently not hold.
 *
 * In the type instead, the refusal happens where it can still be acted on.
 */
public interface TransactionalTimerStore : TimerStore {
    /**
     * Write the timer inside [tx]. Commits nothing: the caller's commit decides whether both their
     * state change and this row become visible, or neither does.
     */
    public suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    )

    /** Move an existing timer's due time inside [tx]. Returns false if there is no such timer. */
    public suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean

    /**
     * Cancel inside [tx]. Returns false if there is no such timer, or if it is already terminal.
     *
     * Cancelling a timer whose moment has passed but whose event has not gone out must still work:
     * that window is precisely when a saga rolling back needs it.
     */
    public suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean
}
