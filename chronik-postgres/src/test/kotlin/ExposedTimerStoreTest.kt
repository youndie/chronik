package ru.workinprogress.chronik.postgres

import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.chronik.Chronik
import ru.workinprogress.chronik.ChronikClock
import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.TimerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store against a real Postgres.
 *
 * These are the claims the in-memory model cannot make. `SKIP LOCKED`, the lifetime of a row lock,
 * and what an `UPDATE ... WHERE state = 'PENDING'` does when an earlier statement in the same
 * transaction already changed the row — all of them are the database's behaviour, not chronik's,
 * and a model that imitates them is only ever a guess about them.
 */
class ExposedTimerStoreTest {
    private val db = PostgresHarness.database

    private fun store(name: String) = ExposedTimerStore(db, PostgresHarness.freshTable(name))

    private fun clockAt(second: Long) = ChronikClock { EpochSeconds(second) }

    @Test
    fun `a timer written in a rolled back transaction does not exist`() =
        runTest {
            val store = store("t_rollback")
            val chronik = Chronik(store, clockAt(0))

            runCatching {
                transaction(db) {
                    kotlinx.coroutines.runBlocking {
                        chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                    }
                    // The caller's own failure, after the timer was written and before the commit.
                    error("the business step refused")
                }
            }

            assertNull(store.findById("t1"), "the rollback had to take the timer with it")
        }

    @Test
    fun `a timer written in a committed transaction is claimable once it is due`() =
        runTest {
            val store = store("t_commit")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }

            assertFalse(store.hasDue(EpochSeconds(9)))
            assertTrue(store.hasDue(EpochSeconds(10)))

            val claimed = store.claimDue(EpochSeconds(10), EpochSeconds(40), owner = "w", limit = 10)
            assertEquals(listOf("t1"), claimed.map { it.id })
        }

    /**
     * THE CLAIM THIS MODULE EXISTS TO MAKE. Two workers, one due timer, one of them gets it.
     *
     * Not a model of two workers: two selections against the same table, both with
     * `FOR UPDATE SKIP LOCKED`, on a real server.
     */
    @Test
    fun `two workers claiming at once do not both get the same timer`() =
        runTest {
            val store = store("t_race")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    repeat(4) { i ->
                        chronik.schedule(asTimerTransaction(), "t$i", EpochSeconds(10), "{}")
                    }
                }
            }

            val first = store.claimDue(EpochSeconds(10), EpochSeconds(40), owner = "w1", limit = 10)
            val second = store.claimDue(EpochSeconds(10), EpochSeconds(40), owner = "w2", limit = 10)

            assertEquals(4, first.size, "the first pass takes everything that is free")
            assertTrue(second.isEmpty(), "the lease of the first holds them all")
            assertEquals(first.map { it.id }.toSet().size, first.size, "no id claimed twice")
        }

    @Test
    fun `an expired lease makes the timer claimable again, a live one does not`() =
        runTest {
            val store = store("t_lease")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }

            // A worker takes it and dies: nothing is written back, the lease simply stands.
            store.claimDue(EpochSeconds(10), EpochSeconds(15), owner = "doomed", limit = 10)

            assertTrue(
                store.claimDue(EpochSeconds(14), EpochSeconds(44), "survivor", 10).isEmpty(),
                "at 14 the lease is still live",
            )
            assertTrue(
                store.claimDue(EpochSeconds(16), EpochSeconds(46), "survivor", 10).map { it.id } == listOf("t1"),
                "at 16 it has lapsed and the survivor takes it",
            )
        }

    @Test
    fun `hasDue looks past the lease, or the loser of a race is told nothing was due`() =
        runTest {
            val store = store("t_hasdue")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }

            store.claimDue(EpochSeconds(10), EpochSeconds(99), owner = "fast", limit = 10)

            assertTrue(store.hasDue(EpochSeconds(10)), "held by someone else is still due")
        }

    /**
     * Two operations on one id in one transaction: the second sees the first.
     *
     * The case the in-memory model got wrong, checked here against the behaviour that decides it —
     * an `UPDATE ... WHERE state = 'PENDING'` running inside the transaction that just cancelled
     * the row.
     */
    @Test
    fun `cancel then reschedule in one transaction reports false for the second`() =
        runTest {
            val store = store("t_selfread")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    val tx = asTimerTransaction()
                    assertTrue(chronik.cancel(tx, "t1"))
                    assertFalse(
                        chronik.reschedule(tx, "t1", EpochSeconds(99)),
                        "the cancel earlier in this transaction already made it terminal",
                    )
                }
            }

            val stored = store.findById("t1")
            assertEquals(TimerState.CANCELLED, stored?.state)
            assertEquals(EpochSeconds(10), stored?.dueAt)
        }

    @Test
    fun `cancel and reschedule of an unknown id report false`() =
        runTest {
            val store = store("t_unknown")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    val tx = asTimerTransaction()
                    assertFalse(chronik.cancel(tx, "nope"))
                    assertFalse(chronik.reschedule(tx, "nope", EpochSeconds(10)))
                }
            }

            assertNull(store.findById("nope"), "a reschedule must never be a disguised insert")
        }

    @Test
    fun `a cancelled timer is never claimed, however late`() =
        runTest {
            val store = store("t_cancelled")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }
            transaction(db) {
                kotlinx.coroutines.runBlocking { chronik.cancel(asTimerTransaction(), "t1") }
            }

            assertTrue(store.claimDue(EpochSeconds(10_000), EpochSeconds(10_030), "w", 10).isEmpty())
            assertFalse(store.hasDue(EpochSeconds(10_000)))
        }

    @Test
    fun `a failed delivery increments the attempt count in SQL and keeps the timer pending`() =
        runTest {
            val store = store("t_attempts")
            val chronik = Chronik(store, clockAt(0))

            transaction(db) {
                kotlinx.coroutines.runBlocking {
                    chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(10), "{}")
                }
            }

            store.markFailed("t1")
            store.markFailed("t1")

            val stored = store.findById("t1")
            assertEquals(2, stored?.attempts)
            assertEquals(TimerState.PENDING, stored?.state)
        }

    @Test
    fun `the index the claim depends on is part of the table definition`() {
        val table = PostgresHarness.freshTable("t_index")
        val indexed = table.indices.single()

        // Declared, not merely created by a migration somewhere: a schema generator asks the table
        // what it needs, and an index that is only in a comment is one the generator asks the
        // application to drop.
        assertEquals(listOf("state", "due_at"), indexed.columns.map { it.name })
    }
}
