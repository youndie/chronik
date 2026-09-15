package io.github.youndie.chronik.sqlx4k.sqlite

import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * What the values do on their way into SQL, and what a table name is allowed to be.
 *
 * Both questions exist because of one property of sqlx4k: a `Statement` is **rendered into SQL
 * text** rather than prepared, so the escaping is the library's and the quoting of anything that is
 * not a value is ours.
 */
class PayloadAndTableTest {
    private val db = SqliteHarness.open("payload")

    /**
     * A payload is a caller's string, and callers write JSON with quotes in it.
     *
     * Not a security exercise: the point is that a rendered statement can be broken by ordinary
     * data, and this is the ordinary data. `'` is what ends a string literal in SQL, `--` is what
     * starts a comment, and a payload containing both would end the statement early if the binding
     * did not escape it.
     */
    @Test
    fun `a payload with quotes and a comment marker survives the round trip`() =
        runTest {
            db.createTimers()
            val store = SqliteTimerStore(db)
            val payload = """{"note":"it's 'quoted', -- and it isn't a comment","semi":";"}"""

            db.transaction {
                store.insert(asTimerTransaction(), Timer(id = "t1", dueAt = EpochSeconds(10), payload = payload))
            }

            assertEquals(payload, assertNotNull(store.findById("t1")).payload)
        }

    /** The same, through the claim — a different statement renders it, and it is the one a sink reads. */
    @Test
    fun `a claimed timer carries the payload it was written with`() =
        runTest {
            db.createTimers(TABLE)
            val store = SqliteTimerStore(db, TABLE)
            val payload = "don't '; DROP TABLE $TABLE; --"

            db.transaction {
                store.insert(asTimerTransaction(), Timer(id = "t1", dueAt = EpochSeconds(10), payload = payload))
            }
            val claimed = store.claimDue(EpochSeconds(10), EpochSeconds(40), "w", 10)

            assertEquals(listOf(payload), claimed.map { it.payload })
            // The table is still there, which is the other half of the claim being made.
            assertNotNull(store.findById("t1"))
        }

    /**
     * A table name is an identifier, and an identifier is interpolated because no dialect has a
     * parameter form for one. So it is refused rather than escaped — at the point it is accepted.
     */
    @Test
    fun `a table name that is not a plain identifier is refused`() {
        assertFailsWith<IllegalArgumentException> { SqliteTimerStore(db, "timers; DROP TABLE x") }
        assertFailsWith<IllegalArgumentException> { chronikTimersSchema("\"timers\"") }
        assertFailsWith<IllegalArgumentException> { chronikTimersSchema("") }
    }

    private companion object {
        /** A second table in the same database: the store must honour the name it is given. */
        const val TABLE = "other_timers"
    }
}
