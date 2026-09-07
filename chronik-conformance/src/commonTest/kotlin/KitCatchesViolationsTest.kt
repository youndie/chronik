package io.github.youndie.chronik.conformance

import kotlinx.coroutines.test.runTest
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import io.github.youndie.chronik.TimerState
import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.TransactionalTimerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * THE VACUITY GUARD, and it is per case rather than per corpus.
 *
 * A single "the kit notices a completely broken store" check is worth almost nothing: one case
 * catching everything looks identical to seventeen cases each catching their own thing, and the day
 * a case stops checking anything, the corpus stays green and says so with authority.
 *
 * So every case gets its own violation, injected by a decorator over a correct store, and the kit
 * must report THAT rule and not merely some rule. A case with no violation listed here is a case
 * nobody has shown to be capable of failing — which is why the last test refuses to let one exist.
 */
class KitCatchesViolationsTest {
    private val kit = ConformanceKit()

    /** A correct store must produce nothing, or every finding elsewhere is suspect. */
    @Test
    fun `a correct implementation yields no findings`() =
        runTest {
            val findings = kit.run(ReferenceSubject())
            assertTrue(findings.isEmpty(), "the kit accuses a correct store: $findings")
        }

    /**
     * Each entry breaks one rule. The value is the rule the kit is expected to name.
     *
     * The decorators are deliberately small and plausible — they are the shapes a real
     * implementation gets wrong, not absurdities. A store that returns nothing for everything would
     * trip every case at once and prove nothing about any of them.
     */
    private fun violations(): Map<String, (ReferenceStore) -> TransactionalTimerStore> =
        mapOf(
            "a committed timer exists and is claimable once it is due" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = emptyList<Timer>()
                    }
                },
            "nothing is claimable before its due second" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // The classic off-by-one: due strictly before, so a timer becomes claimable a
                        // second early.
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.claimDue(EpochSeconds(now.value + 1), leaseUntil, owner, limit)
                    }
                },
            "a live lease hides the timer from everybody else" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.timers.values
                            .filter { it.state == TimerState.PENDING && it.dueAt <= now }
                            .take(limit)
                            .map { t ->
                                val held = t.copy(lockedUntil = leaseUntil, lockedBy = owner)
                                s.timers[t.id] = held
                                held
                            }
                    }
                },
            "a lapsed lease makes the timer claimable again" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // A lock rather than a lease: once held, held for ever.
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.timers.values
                            .filter { it.state == TimerState.PENDING && it.dueAt <= now && it.lockedUntil == null }
                            .take(limit)
                            .map { t ->
                                val held = t.copy(lockedUntil = leaseUntil, lockedBy = owner)
                                s.timers[t.id] = held
                                held
                            }
                    }
                },
            "a cancelled timer never fires" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun cancel(
                            tx: TimerTransaction,
                            id: String,
                        ) = true
                    }
                },
            "cancel and reschedule report false for a timer that does not exist" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // A reschedule that inserts: the shape that quietly resurrects cancelled work.
                        override suspend fun reschedule(
                            tx: TimerTransaction,
                            id: String,
                            dueAt: EpochSeconds,
                        ): Boolean {
                            if (s.reschedule(tx, id, dueAt)) return true
                            s.insert(tx, Timer(id = id, dueAt = dueAt, payload = "{}"))
                            return true
                        }
                    }
                },
            "an operation sees an earlier operation in the same transaction" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // Reads the committed map instead of the transaction, which is exactly what a
                        // buffered-writes implementation does.
                        override suspend fun reschedule(
                            tx: TimerTransaction,
                            id: String,
                            dueAt: EpochSeconds,
                        ): Boolean {
                            val t = s.timers[id] ?: return false
                            if (t.state != TimerState.PENDING) return false
                            s.insert(tx, t.copy(dueAt = dueAt, lockedUntil = null, lockedBy = null))
                            return true
                        }
                    }
                },
            "a failed delivery counts an attempt and holds the timer until its backoff" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // Counts the attempt and forgets the wait: the backoff lives in the worker's
                        // memory, so no other instance honours it.
                        override suspend fun markFailed(
                            id: String,
                            retryAfter: EpochSeconds,
                        ) {
                            s.timers[id]?.let {
                                s.timers[it.id] =
                                    it.copy(attempts = it.attempts + 1, lockedUntil = null)
                            }
                        }
                    }
                },
            "a dead lettered timer is never selected again" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun markDeadLettered(id: String) = Unit
                    }
                },
            "hasDue looks past the lease, or the loser of a race is told nothing was due" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun hasDue(now: EpochSeconds) = s.timers.values.any { it.isClaimableAt(now) }
                    }
                },
            "claimDue respects its limit and takes the earliest first" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.claimDue(now, leaseUntil, owner, limit).reversed()
                    }
                },
            "the lease belongs to the second it expires in" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // Off by one on the lease: the second it expires in is treated as free.
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.timers.values
                            .filter {
                                it.state == TimerState.PENDING && it.dueAt <= now &&
                                    (it.lockedUntil == null || it.lockedUntil!! <= now)
                            }.take(limit)
                            .map { t ->
                                val held = t.copy(lockedUntil = leaseUntil, lockedBy = owner)
                                s.timers[t.id] = held
                                held
                            }
                    }
                },
            "cancelling after the due second but before the event still works" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // A plausible guard that is wrong: "too late to cancel, it is already due".
                        // That window is exactly when a compensating saga needs the cancel.
                        override suspend fun cancel(
                            tx: TimerTransaction,
                            id: String,
                        ): Boolean {
                            val t = s.timers[id] ?: return false
                            if (t.lockedUntil != null || t.dueAt <= EpochSeconds(10)) return false
                            return s.cancel(tx, id)
                        }
                    }
                },
            "reschedule moves the existing timer rather than adding a second" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // Cancel-and-insert instead of a move: the anti-pattern the API exists to avoid.
                        override suspend fun reschedule(
                            tx: TimerTransaction,
                            id: String,
                            dueAt: EpochSeconds,
                        ): Boolean {
                            if (!s.cancel(tx, id)) return false
                            s.insert(tx, Timer(id = "$id-again", dueAt = dueAt, payload = "{}"))
                            return true
                        }
                    }
                },
            "a fired timer is terminal and cannot be reopened" to
                { s ->
                    object : TransactionalTimerStore by s {
                        // Records the firing by releasing the lease and leaving the row PENDING.
                        override suspend fun markFired(id: String) {
                            s.timers[id]?.let { s.timers[id] = it.copy(lockedUntil = null, lockedBy = null) }
                        }
                    }
                },
            "a claim reports the lease it took, not the one it found" to
                { s ->
                    object : TransactionalTimerStore by s {
                        override suspend fun claimDue(
                            now: EpochSeconds,
                            leaseUntil: EpochSeconds,
                            owner: String,
                            limit: Int,
                        ) = s.claimDue(now, leaseUntil, owner, limit).map { it.copy(lockedBy = null) }
                    }
                },
        )

    @Test
    fun `every injected violation is reported, and by name`() =
        runTest {
            for ((rule, break_) in violations()) {
                val reference = ReferenceStore()
                val broken = break_(reference)
                val subject =
                    object : TimerStoreSubject {
                        override val store = broken

                        override suspend fun committed(body: suspend (TimerTransaction) -> Unit) {
                            val tx = reference.Tx()
                            body(tx)
                            reference.timers.putAll(tx.overlay)
                        }

                        override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) {
                            body(reference.Tx())
                        }

                        override suspend fun reset() = reference.timers.clear()
                    }

                val reported = kit.run(subject).map { it.rule }
                assertTrue(
                    rule in reported,
                    "breaking \"$rule\" was not reported under that rule; the kit said $reported",
                )
            }
        }

    /**
     * No case may exist without a violation proving it can fail.
     *
     * This is the part that keeps the guard honest as the corpus grows: adding a case is easy and
     * adding one that checks nothing is just as easy, and both look the same in a green run.
     */
    @Test
    fun `every case in the corpus has a violation that proves it can fail`() {
        val covered = violations().keys
        val uncovered = kit.cases.map { it.rule }.filterNot { it in covered }

        // EXACTLY ONE EXEMPTION, and it is not a shortcut.
        //
        // The rollback rule is about what the SUBJECT does with a transaction, and no decorator over
        // the store can break it — the subject is what abandons the work. It has its own test below
        // instead, with a subject that commits what it was asked to abandon.
        //
        // The first draft of this guard exempted five cases on the grounds that they were "through
        // the subject", and four of those were plain wrong: a lease off by one, a cancel refused
        // once due, a reschedule that inserts, a markFired that leaves the row pending — every one
        // of them is a store decorator, and every one is a shape a real implementation gets wrong.
        // An exemption in a completeness guard hides precisely the gap the guard is for, so this
        // one names its reason and the test that replaces it.
        val provenElsewhere = setOf("a timer written in an abandoned transaction does not exist")

        val orphans = uncovered.filterNot { it in provenElsewhere }
        if (orphans.isNotEmpty()) {
            fail(
                "these cases have nothing proving they can fail: $orphans. A case that has never " +
                    "been seen to fail is indistinguishable from one that checks nothing.",
            )
        }
        assertEquals(
            kit.cases.size,
            kit.cases
                .map { it.rule }
                .toSet()
                .size,
            "two cases share a rule name",
        )
    }

    /**
     * The one rule a store decorator cannot break: the subject is what abandons the transaction.
     *
     * Broken here by a subject that commits what it was asked to roll back — which is what a
     * storage layer does when it writes outside the caller's transaction, and the exact failure
     * chronik's whole reason for existing is to prevent.
     */
    @Test
    fun `a subject that commits an abandoned transaction is caught`() =
        runTest {
            val reference = ReferenceStore()
            val lying =
                object : TimerStoreSubject {
                    override val store = reference

                    override suspend fun committed(body: suspend (TimerTransaction) -> Unit) = apply(body)

                    override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) = apply(body)

                    private suspend fun apply(body: suspend (TimerTransaction) -> Unit) {
                        val tx = reference.Tx()
                        body(tx)
                        reference.timers.putAll(tx.overlay)
                    }

                    override suspend fun reset() = reference.timers.clear()
                }

            val reported = kit.run(lying).map { it.rule }
            assertTrue(
                "a timer written in an abandoned transaction does not exist" in reported,
                "the kit did not notice a rollback that committed; it said $reported",
            )
        }
}
