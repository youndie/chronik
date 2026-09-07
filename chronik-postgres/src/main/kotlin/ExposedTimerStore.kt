package io.github.youndie.chronik.postgres

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import io.github.youndie.chronik.TimerState
import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.TransactionalTimerStore

/**
 * The caller's Exposed transaction, wrapped so it can be handed to chronik.
 *
 * A token rather than a thing with behaviour. Exposed already carries the current transaction in
 * the coroutine context, so the writes below would find it anyway — which is exactly the problem
 * this type solves. Without a handle to pass, "am I inside the caller's transaction?" is answered
 * by ambient state that compiles identically whether the answer is yes or no, and the failure is
 * invisible: the state change commits, the timer does not, and nothing looks wrong until an event
 * that nobody is waiting for right now fails to happen in three days.
 */
public class ExposedTimerTransaction internal constructor(
    internal val transaction: JdbcTransaction,
) : TimerTransaction

/** Hand chronik the transaction this block is running in. */
public fun JdbcTransaction.asTimerTransaction(): TimerTransaction = ExposedTimerTransaction(this)

/**
 * Timers on Postgres.
 *
 * Ships no DDL, no driver and no pool: it takes an Exposed [Database] and does not know which
 * server is underneath. Choosing those is the application's decision, and the table describes
 * itself (see [TimersTable]) so a schema generator can produce something that matches what the
 * queries actually filter on.
 */
public class ExposedTimerStore(
    private val db: Database,
    private val table: TimersTable = TimersTable(),
) : TransactionalTimerStore {
    /**
     * Dispatchers.IO is load-bearing, not cosmetic.
     *
     * Without it the transaction runs on whichever dispatcher called it — for a route, that is
     * directly on the server's engine threads. JDBC blocks, and an engine thread stuck in it cannot
     * accept connections, so under load this shows up as connect timeouts on the clients rather
     * than as merely slow responses. Learned in a neighbouring repository rather than here.
     */
    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = db) { block() } }

    // ---- inside the caller's transaction -------------------------------------------------------

    private fun TimerTransaction.exposed(): JdbcTransaction =
        (this as? ExposedTimerTransaction)?.transaction
            ?: error(
                "this store writes through an Exposed transaction; got ${this::class.simpleName}. " +
                    "Obtain one with JdbcTransaction.asTimerTransaction() inside your own transaction.",
            )

    override suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    ) {
        tx.exposed().run {
            table.insert {
                it[id] = timer.id
                it[dueAt] = timer.dueAt.value
                it[payload] = timer.payload
                it[state] = timer.state.name
                it[attempts] = timer.attempts
                it[lockedUntil] = timer.lockedUntil?.value
                it[lockedBy] = timer.lockedBy
            }
        }
    }

    /**
     * One statement, and the row count is the answer.
     *
     * Not read-then-write: between the two another transaction fits, and — more to the point — a
     * read taken before an earlier statement in this same transaction would miss it. `cancel` then
     * `reschedule` on one id must report false for the second, because by then the row is no longer
     * PENDING. The WHERE clause is what makes that true without anybody having to remember it.
     */
    override suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean =
        tx.exposed().run {
            table.update({ (table.id eq id) and (table.state eq TimerState.PENDING.name) }) {
                it[table.dueAt] = dueAt.value
                // The lease goes with the due time: a timer moved into the future is not held by
                // whoever claimed it for the old one.
                it[table.lockedUntil] = null
                it[table.lockedBy] = null
            } > 0
        }

    override suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean =
        tx.exposed().run {
            table.update({ (table.id eq id) and (table.state eq TimerState.PENDING.name) }) {
                it[table.state] = TimerState.CANCELLED.name
                it[table.lockedUntil] = null
                it[table.lockedBy] = null
            } > 0
        }

    // ---- the worker's own transactions ---------------------------------------------------------

    override suspend fun findById(id: String): Timer? =
        dbQuery {
            table
                .selectAll()
                .where { table.id eq id }
                .singleOrNull()
                ?.toDomain()
        }

    override suspend fun hasDue(now: EpochSeconds): Boolean =
        dbQuery {
            table
                .selectAll()
                .where { (table.state eq TimerState.PENDING.name) and (table.dueAt lessEq now.value) }
                .limit(1)
                .any()
        }

    /**
     * Select what is due and claim it, in one short transaction.
     *
     * TWO MECHANISMS, AND THEY ARE NOT INTERCHANGEABLE (D3). `FOR UPDATE SKIP LOCKED` keeps two
     * instances off the same rows for the length of THIS transaction — that is all a row lock ever
     * does. The lease is the `locked_until` column written here, and it is what survives the commit:
     * an instance killed a moment later releases its row lock instantly, and without the column
     * there would be nothing to stop the next instance taking a timer that may already have gone
     * out.
     *
     * The transaction ends here rather than around the delivery. Holding it open across an external
     * call ties a database connection to whatever the sink is talking to — the same illness
     * [dbQuery]'s dispatcher guards against, only worse, because now it is a pooled connection and
     * a lock.
     */
    override suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer> =
        dbQuery {
            val claimable =
                table
                    .selectAll()
                    .where {
                        (table.state eq TimerState.PENDING.name) and
                            (table.dueAt lessEq now.value) and
                            (table.lockedUntil.isNull() or (table.lockedUntil less now.value))
                    }.orderBy(table.dueAt)
                    .limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { it.toDomain() }

            if (claimable.isEmpty()) {
                emptyList()
            } else {
                // ONE update for the whole batch, not one per row.
                //
                // Per-row was the first shape and the benchmark is what condemned it: claiming
                // twenty timers cost twenty-one round trips, which swamped everything else — the
                // selection this method is named after was 0.3% of its own cost, and the stand
                // could not see the index being dropped through the noise of the updates.
                table.update({ table.id inList claimable.map { it.id } }) {
                    it[lockedUntil] = leaseUntil.value
                    it[lockedBy] = owner
                }
                claimable.map { it.copy(lockedUntil = leaseUntil, lockedBy = owner) }
            }
        }

    override suspend fun markFired(id: String): Unit = mark(id, TimerState.FIRED)

    override suspend fun markDeadLettered(id: String): Unit = mark(id, TimerState.DEAD_LETTERED)

    private suspend fun mark(
        id: String,
        to: TimerState,
    ) {
        dbQuery {
            table.update({ table.id eq id }) {
                it[state] = to.name
                it[lockedUntil] = null
                it[lockedBy] = null
            }
        }
    }

    /**
     * A failed delivery: the attempt count moves and the hold is pushed out to [retryAfter].
     *
     * Releasing the hold here would hand the timer to another instance at once, which turns a sink
     * that is down into a stampede. Extending it is the backoff, and it lives in the row precisely
     * so that every instance honours it — an interval kept in one worker's memory is one the other
     * instances never see.
     */
    override suspend fun markFailed(
        id: String,
        retryAfter: EpochSeconds,
    ) {
        dbQuery {
            table.update({ table.id eq id }) {
                // An increment in SQL, not a read followed by a write: two workers failing to
                // deliver the same timer must not each write back the same count.
                it[attempts] = table.attempts + 1
                it[lockedUntil] = retryAfter.value
            }
        }
    }

    private fun ResultRow.toDomain(): Timer =
        Timer(
            id = this[table.id],
            dueAt = EpochSeconds(this[table.dueAt]),
            payload = this[table.payload],
            state = TimerState.valueOf(this[table.state]),
            attempts = this[table.attempts],
            lockedUntil = this[table.lockedUntil]?.let(::EpochSeconds),
            lockedBy = this[table.lockedBy],
        )
}
