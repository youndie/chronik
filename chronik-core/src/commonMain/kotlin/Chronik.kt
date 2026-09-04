package ru.workinprogress.chronik

/**
 * The three operations.
 *
 * THE CONSTRUCTOR IS THE REFUSAL. It takes a [TransactionalTimerStore] and nothing weaker, so a
 * store that cannot join the caller's transaction cannot reach this class at all — the compiler
 * says so, at the wiring site, before anything runs.
 *
 * That is stronger than the runtime check this was specified as, and worth stating plainly: the
 * failure being guarded against is invisible by construction. The timer is not written, the
 * caller's state change is, the operation reports success, and only an event nobody is waiting for
 * right now fails to happen — in three days. Every assertion anybody naturally writes about that
 * run passes. A guard that fires at wiring time is the only kind that helps.
 *
 * [chronik] covers the case the type system cannot: a store that arrives as a plain [TimerStore]
 * from a factory or a container.
 *
 * Every operation takes the caller's transaction and commits nothing. Whether the timer and the
 * business row both become visible is the caller's commit to decide, which is the entire point.
 */
public class Chronik(
    private val store: TransactionalTimerStore,
    private val clock: ChronikClock,
) {
    /**
     * Record a timer due at [at]. Scheduling in the past is legal and fires on the next pass with
     * the lateness recorded: refusing it would push the caller into rounding times up, and a due
     * time that has just passed is the normal case after any pause.
     */
    public suspend fun schedule(
        tx: TimerTransaction,
        id: String,
        at: EpochSeconds,
        payload: String,
    ) {
        store.insert(tx, Timer(id = id, dueAt = at, payload = payload))
    }

    /**
     * Move an existing timer's due time. Returns false when there is no such timer, or when it is
     * already terminal.
     *
     * A FIRST-CLASS OPERATION, and not sugar over cancel-then-schedule. Extending a deadline is one
     * change to one row; expressed as two operations it has a window in which the timer does not
     * exist, and a worker passing through that window sees nothing to fire.
     */
    public suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        at: EpochSeconds,
    ): Boolean = store.reschedule(tx, id, at)

    /**
     * Cancel. Returns false when there is no such timer, or when it is already terminal.
     *
     * Cancelling after the due time but before the event has gone out must work: that window is
     * exactly when a saga rolling back needs it.
     */
    public suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean = store.cancel(tx, id)

    /** The clock this scheduler reads, so a caller can express "in an hour" without a second one. */
    public fun now(): EpochSeconds = clock.now()
}

/**
 * Build a [Chronik] from a store whose transactional capability is not known statically — one that
 * came out of a container or a factory typed as [TimerStore].
 *
 * Throws [IllegalArgumentException] at the wiring site rather than at the first schedule. The
 * distinction matters: a failure at the first schedule happens under load, in a request, in a
 * transaction that is about to commit the business change regardless.
 */
public fun chronik(
    store: TimerStore,
    clock: ChronikClock,
): Chronik {
    require(store is TransactionalTimerStore) {
        "chronik needs a TransactionalTimerStore: a timer that cannot be written inside the " +
            "caller's transaction is a timer that can go missing while the state change it " +
            "belongs to commits. ${store::class.simpleName} implements TimerStore only."
    }
    return Chronik(store, clock)
}
