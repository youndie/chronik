package ru.workinprogress.chronik

/**
 * The caller's unit of work, which chronik never creates.
 *
 * It is an opaque handle rather than anything with methods, because chronik has no business
 * committing, rolling back, or even knowing what a transaction is made of. All it needs is to be
 * handed one and to write inside it.
 */
interface TimerTransaction

/**
 * Reading and writing timers. Everything here is storage; no policy lives in this interface.
 */
interface TimerStore {
    suspend fun findById(id: String): Timer?

    /**
     * Timers that are due and not held by a live lease, claimed for [owner] until [leaseUntil].
     *
     * Claiming is part of the query rather than a second call on purpose: between a read and a
     * separate write another instance fits, and the whole point of this method is that one does
     * not.
     */
    suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer>

    /**
     * Is anything due and unclaimed right now?
     *
     * Exists for one question a worker cannot otherwise answer: it claimed nothing — was there
     * nothing to do, or did another instance get there first? Those two look identical from here
     * and mean opposite things about whether the lease is working at all.
     *
     * Called only by a worker that was given a lost-race handler, because on a busy store this is
     * a query per idle pass.
     */
    suspend fun hasDue(now: EpochSeconds): Boolean

    suspend fun markFired(id: String)

    suspend fun markFailed(id: String)

    suspend fun markDeadLettered(id: String)
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
interface TransactionalTimerStore : TimerStore {
    /**
     * Write the timer inside [tx]. Commits nothing: the caller's commit decides whether both their
     * state change and this row become visible, or neither does.
     */
    suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    )

    /** Move an existing timer's due time inside [tx]. Returns false if there is no such timer. */
    suspend fun reschedule(
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
    suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean
}
