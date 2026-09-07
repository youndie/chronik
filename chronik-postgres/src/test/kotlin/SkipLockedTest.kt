package io.github.youndie.chronik.postgres

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.github.youndie.chronik.Chronik
import io.github.youndie.chronik.ChronikClock
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.TimerState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SKIP LOCKED` itself, with two transactions open at once.
 *
 * WHY THIS EXISTS SEPARATELY. The rest of the store's tests claim two workers on one table, and
 * they do pass — but they claim in sequence, so what keeps the second one off the row is the LEASE
 * COLUMN, not the row lock. Replacing `FOR UPDATE SKIP LOCKED` with a plain `FOR UPDATE` left that
 * whole suite green. A test that survives the removal of the thing it is named after is not a test
 * of it, and the only way to find that out was to try the removal.
 *
 * The difference is only visible while somebody else's transaction is still open, which is what the
 * thread and the latches below are for.
 *
 * `lock_timeout` rather than a stopwatch: with `SKIP LOCKED` the second claim never waits, so it
 * returns; without it the claim blocks on the held row and Postgres raises the timeout. That is a
 * deterministic difference, where "it took less than a second" is a race on a busy machine.
 */
class SkipLockedTest {
    private companion object {
        const val LOCK_TIMEOUT_MS = 250
    }

    /** A second connection whose statements refuse to wait for a lock at all. */
    private val impatient: Database =
        Database.connect(
            url = PostgresHarness.jdbcUrl + "?options=-c%20lock_timeout%3D" + LOCK_TIMEOUT_MS,
            driver = "org.postgresql.Driver",
            user = PostgresHarness.username,
            password = PostgresHarness.password,
        )

    @Test
    fun `a claim steps over rows another transaction is holding instead of waiting for them`() {
        val table = PostgresHarness.freshTable("t_skiplocked")
        val patient = ExposedTimerStore(PostgresHarness.database, table)
        val hurried = ExposedTimerStore(impatient, table)
        val chronik = Chronik(patient, ChronikClock { EpochSeconds(0) })

        transaction(PostgresHarness.database) {
            runBlocking {
                chronik.schedule(asTimerTransaction(), "t0", EpochSeconds(10), "{}")
                chronik.schedule(asTimerTransaction(), "t1", EpochSeconds(11), "{}")
            }
        }

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)

        // Somebody else selects t0 FOR UPDATE and keeps the transaction open.
        val holder =
            thread(name = "holder") {
                transaction(PostgresHarness.database) {
                    val locked =
                        table
                            .selectAll()
                            .where {
                                (table.state eq TimerState.PENDING.name) and (table.dueAt lessEq 20L)
                            }.orderBy(table.dueAt)
                            .limit(1)
                            .forUpdate(
                                ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED),
                            ).map { it[table.id] }
                    check(locked == listOf("t0")) { "the holder was meant to lock t0, got $locked" }
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "the test never released the holder" }
                }
            }

        try {
            assertTrue(holding.await(30, TimeUnit.SECONDS), "the holder never took its lock")

            // With SKIP LOCKED this returns t1 at once. With a plain FOR UPDATE it would queue
            // behind t0 and die on lock_timeout instead.
            val claimed =
                runBlocking {
                    hurried.claimDue(EpochSeconds(20), EpochSeconds(50), owner = "second", limit = 10)
                }

            assertEquals(
                listOf("t1"),
                claimed.map { it.id },
                "the held row must be stepped over, not waited for, and not taken",
            )
        } finally {
            release.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(30))
        }
    }
}
