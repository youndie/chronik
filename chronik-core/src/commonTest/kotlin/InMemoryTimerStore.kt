package ru.workinprogress.chronik

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

    inner class InMemoryTransaction : TimerTransaction {
        private val writes = mutableListOf<() -> Unit>()

        internal fun stage(write: () -> Unit) {
            writes += write
        }

        /** What the caller's commit does: both their change and ours become visible together. */
        fun commit() {
            writes.forEach { it() }
            writes.clear()
        }

        /** A rollback takes the timer with it — nothing here was applied. */
        fun rollback() = writes.clear()
    }

    override suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    ) {
        (tx as InMemoryTransaction).stage { timers[timer.id] = timer }
    }

    override suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean {
        val existing = timers[id] ?: return false
        if (existing.state != TimerState.PENDING) return false
        (tx as InMemoryTransaction).stage {
            timers[id]?.takeIf { it.state == TimerState.PENDING }?.let {
                // The lease goes with the due time. A timer moved into the future is not held by
                // whoever claimed it for the old one.
                timers[id] = it.copy(dueAt = dueAt, lockedUntil = null, lockedBy = null)
            }
        }
        return true
    }

    override suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean {
        val existing = timers[id] ?: return false
        if (existing.state != TimerState.PENDING) return false
        (tx as InMemoryTransaction).stage {
            timers[id]?.takeIf { it.state == TimerState.PENDING }?.let {
                timers[id] = it.copy(state = TimerState.CANCELLED)
            }
        }
        return true
    }

    override suspend fun findById(id: String): Timer? = timers[id]

    override suspend fun hasDue(now: EpochSeconds): Boolean = timers.values.any { it.isClaimableAt(now) }

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

    override suspend fun markFailed(id: String) {
        // Stays PENDING and stays leased: the retry is what the lapsing lease buys. Only the
        // attempt count moves.
        timers[id]?.let { timers[id] = it.copy(attempts = it.attempts + 1) }
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
