package io.github.youndie.chronik.sqlx4k

import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two workers, one timer, and only one of them gets it.
 *
 * **This is the case the Postgres backend needs `FOR UPDATE SKIP LOCKED` for, and this module does
 * not.** The claim here is a single `UPDATE ... WHERE id IN (SELECT ...) RETURNING`, and the
 * argument for it is SQLite's single writer: the statement either runs whole or has not started, so
 * the loser's subquery no longer selects a row the winner has stamped. That argument is worth
 * exactly as much as this test — an implementation that claimed in two statements would pass every
 * case in the conformance corpus, which runs one worker at a time, and lose timers in production.
 *
 * It runs on both targets deliberately: sqlx4k is two drivers, the locking is the driver's, and a
 * claim proven atomic on one of them is a claim proven on one of them.
 */
class ClaimIsAtomicTest {
    private val db = SqliteHarness.open("claim-atomic")
    private val store = Sqlx4kTimerStore(db)

    @Test
    fun `competing workers never claim the same timer twice`() =
        runTest {
            db.createTimers()
            val timers = 20
            db.transaction {
                repeat(timers) { n ->
                    store.insert(
                        asTimerTransaction(),
                        Timer(id = "t$n", dueAt = EpochSeconds(100), payload = "{}"),
                    )
                }
            }

            // Real threads rather than the test scheduler: `runTest` runs coroutines one at a time,
            // so a claim raced only on its dispatcher is a claim that was never raced. Dispatchers
            // .Default is where two of these actually overlap.
            val claimed =
                withContext(Dispatchers.Default) {
                    listOf("a", "b", "c", "d")
                        .map { owner ->
                            async { store.claimDue(EpochSeconds(100), EpochSeconds(140), owner, timers) }
                        }.awaitAll()
                }

            val ids = claimed.flatten().map { it.id }
            assertEquals(ids.size, ids.toSet().size, "the same timer was claimed by two workers: $ids")
            assertEquals(timers, ids.size, "some timers were claimed by nobody: ${ids.sorted()}")
            // Each returned row carries the lease of the worker that got it, not of whoever wrote
            // last: a claim that reported someone else's lease would be worse than losing the row.
            claimed.forEachIndexed { index, batch ->
                val owner = listOf("a", "b", "c", "d")[index]
                batch.forEach { assertEquals(owner, it.lockedBy, "${it.id} came back owned by someone else") }
            }
        }
}
