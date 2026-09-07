package io.github.youndie.chronik

/**
 * The model the oracle runs against: a store with a transaction that can be committed, rolled back,
 * or dropped on the floor.
 *
 * Dropping it on the floor is the point. A crash between "the row is written" and "the event has
 * gone out" cannot be produced by closing something, throwing from a fake, or truncating a file —
 * every cheaper version of that test tests something else. Here the buffered writes are simply
 * never applied, which is what a process disappearing looks like from the storage's side.
 *
 * WHAT THIS MODEL DOES NOT COVER, and it has to be said out loud rather than discovered later: it
 * is not Postgres. `FOR UPDATE SKIP LOCKED`, the real lifetime of a row lock, and what a real
 * transaction does on a killed connection are untouched here. B-08 owns those, and neither test
 * substitutes for the other.
 */
class InMemoryTimerStore : TransactionalTimerStore {
    private val timers = mutableMapOf<String, Timer>()

    /** Everything that ever committed, for the oracle to compare against. */
    val committed: Map<String, Timer> get() = timers.toMap()

    fun begin(): InMemoryTransaction = InMemoryTransaction()

    /**
     * An overlay rather than a list of deferred writes, and the difference is not cosmetic.
     *
     * Buffered writes gave the transaction no view of itself: `cancel` then `reschedule` on one id
     * in one transaction both answered "true", and the second then quietly did nothing at commit,
     * because by then the row was no longer PENDING. A SQL store cannot behave that way — its
     * `UPDATE ... WHERE state = 'PENDING'` runs inside the transaction, sees the cancel, and
     * reports nought rows — so the model was blessing an answer the real backend will not give,
     * and the oracle runs on the model.
     *
     * Reads made THROUGH the transaction consult the overlay; the worker reads the committed map
     * directly and therefore never sees uncommitted rows. That is the isolation a real store has.
     */
    inner class InMemoryTransaction : TimerTransaction {
        private val overlay = mutableMapOf<String, Timer>()

        internal fun read(id: String): Timer? = overlay[id] ?: timers[id]

        internal fun write(timer: Timer) {
            overlay[timer.id] = timer
        }

        /** What the caller's commit does: both their change and ours become visible together. */
        fun commit() {
            timers.putAll(overlay)
            overlay.clear()
        }

        /** A rollback takes the timer with it — nothing here was applied. */
        fun rollback() = overlay.clear()
    }

    override suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    ) {
        (tx as InMemoryTransaction).write(timer)
    }

    override suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean {
        val transaction = tx as InMemoryTransaction
        val existing = transaction.read(id) ?: return false
        if (existing.state != TimerState.PENDING) return false
        // The lease goes with the due time. A timer moved into the future is not held by whoever
        // claimed it for the old one.
        transaction.write(existing.copy(dueAt = dueAt, lockedUntil = null, lockedBy = null))
        return true
    }

    override suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean {
        val transaction = tx as InMemoryTransaction
        val existing = transaction.read(id) ?: return false
        if (existing.state != TimerState.PENDING) return false
        transaction.write(existing.copy(state = TimerState.CANCELLED))
        return true
    }

    override suspend fun findById(id: String): Timer? = timers[id]

    // Deliberately NOT isClaimableAt: the lease is exactly what this must look past, or the worker
    // that just lost the race is told there was nothing to lose.
    override suspend fun hasDue(now: EpochSeconds): Boolean =
        timers.values.any { it.state == TimerState.PENDING && it.dueAt <= now }

    override suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer> {
        // Claiming inside the selection, not after it: between a read and a separate write another
        // worker fits, and not fitting is the whole job of this method.
        val due =
            timers.values
                .filter { it.isClaimableAt(now) }
                .sortedBy { it.dueAt.value }
                .take(limit)
        return due.map { timer ->
            val held = timer.copy(lockedUntil = leaseUntil, lockedBy = owner)
            timers[timer.id] = held
            held
        }
    }

    override suspend fun markFired(id: String) {
        timers[id]?.let { timers[id] = it.copy(state = TimerState.FIRED, lockedUntil = null, lockedBy = null) }
    }

    override suspend fun markFailed(
        id: String,
        retryAfter: EpochSeconds,
    ) {
        // Stays PENDING, and the hold is pushed out to the backoff deadline. Holding it here rather
        // than in the worker is what makes the wait apply to every instance and not just the one
        // that failed.
        timers[id]?.let { timers[id] = it.copy(attempts = it.attempts + 1, lockedUntil = retryAfter) }
    }

    override suspend fun markDeadLettered(id: String) {
        timers[id]?.let { timers[id] = it.copy(state = TimerState.DEAD_LETTERED, lockedUntil = null, lockedBy = null) }
    }
}

/** A clock the test moves by hand. Nothing here ever sleeps on the real one. */
class TestClock(
    private var seconds: Long = 0,
) : ChronikClock {
    override fun now(): EpochSeconds = EpochSeconds(seconds)

    fun advanceTo(second: Long) {
        require(second >= seconds) { "the clock does not go back: $seconds -> $second" }
        seconds = second
    }
}

/** Records what came out, so the oracle can compare it with what should have. */
class RecordingSink : TimerSink {
    val delivered = mutableListOf<FiredTimer>()

    override suspend fun deliver(fired: FiredTimer) {
        delivered += fired
    }
}
