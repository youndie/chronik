package ru.workinprogress.chronik.conformance

import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.Timer
import ru.workinprogress.chronik.TimerState
import ru.workinprogress.chronik.TimerTransaction
import ru.workinprogress.chronik.TransactionalTimerStore

/**
 * A correct in-memory implementation, used only to test the KIT.
 *
 * It is deliberately not shipped: an implementer wants the corpus, not a second implementation to
 * copy. Here it plays one role — the subject the kit must find nothing wrong with, so that a
 * finding against a real backend means something about the backend rather than about the kit.
 */
class ReferenceStore : TransactionalTimerStore {
    val timers = mutableMapOf<String, Timer>()

    inner class Tx : TimerTransaction {
        val overlay = mutableMapOf<String, Timer>()

        fun read(id: String): Timer? = overlay[id] ?: timers[id]

        fun write(timer: Timer) {
            overlay[timer.id] = timer
        }
    }

    override suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    ) {
        (tx as Tx).write(timer)
    }

    override suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean {
        val t = (tx as Tx).read(id) ?: return false
        if (t.state != TimerState.PENDING) return false
        tx.write(t.copy(dueAt = dueAt, lockedUntil = null, lockedBy = null))
        return true
    }

    override suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean {
        val t = (tx as Tx).read(id) ?: return false
        if (t.state != TimerState.PENDING) return false
        tx.write(t.copy(state = TimerState.CANCELLED))
        return true
    }

    override suspend fun findById(id: String): Timer? = timers[id]

    override suspend fun hasDue(now: EpochSeconds): Boolean =
        timers.values.any { it.state == TimerState.PENDING && it.dueAt <= now }

    override suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer> =
        timers.values
            .filter { it.isClaimableAt(now) }
            .sortedBy { it.dueAt.value }
            .take(limit)
            .map { t ->
                val held = t.copy(lockedUntil = leaseUntil, lockedBy = owner)
                timers[t.id] = held
                held
            }

    override suspend fun markFired(id: String) = mark(id, TimerState.FIRED)

    override suspend fun markDeadLettered(id: String) = mark(id, TimerState.DEAD_LETTERED)

    private fun mark(
        id: String,
        to: TimerState,
    ) {
        timers[id]?.let { timers[id] = it.copy(state = to, lockedUntil = null, lockedBy = null) }
    }

    override suspend fun markFailed(
        id: String,
        retryAfter: EpochSeconds,
    ) {
        timers[id]?.let { timers[id] = it.copy(attempts = it.attempts + 1, lockedUntil = retryAfter) }
    }
}

/** The subject wrapper: a transaction that can be committed or abandoned. */
class ReferenceSubject(
    override val store: ReferenceStore = ReferenceStore(),
) : TimerStoreSubject {
    override suspend fun committed(body: suspend (TimerTransaction) -> Unit) {
        val tx = store.Tx()
        body(tx)
        store.timers.putAll(tx.overlay)
    }

    override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) {
        val tx = store.Tx()
        body(tx)
        // The overlay is simply dropped, which is what a rollback and a lost process look like from
        // storage's side.
    }

    override suspend fun reset() = store.timers.clear()
}
