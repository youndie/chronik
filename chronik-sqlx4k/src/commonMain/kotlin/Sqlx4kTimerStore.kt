package io.github.youndie.chronik.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import io.github.youndie.chronik.TimerState
import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.TransactionalTimerStore

/**
 * The caller's sqlx4k transaction, wrapped so it can be handed to chronik.
 *
 * A token rather than a thing with behaviour, exactly as on the Exposed side. sqlx4k can also carry
 * the current transaction in the `CoroutineContext`, and that is precisely why the handle exists:
 * with ambient state, "am I inside the caller's transaction?" is answered by code that compiles
 * identically whether the answer is yes or no. The failure that guards against is invisible — the
 * business row commits, the timer does not, everything reports success, and an event nobody is
 * waiting for right now fails to happen in three days.
 */
public class Sqlx4kTimerTransaction internal constructor(
    internal val transaction: Transaction,
) : TimerTransaction

/**
 * Hand chronik the transaction this block is running in.
 *
 * ```
 * db.transaction {
 *     execute(insertMyBusinessRow).getOrThrow()
 *     chronik.schedule(asTimerTransaction(), id = "...", at = ..., payload = "...")
 * }
 * ```
 */
public fun Transaction.asTimerTransaction(): TimerTransaction = Sqlx4kTimerTransaction(this)

/**
 * Timers on SQLite, through sqlx4k — the store that runs where the JVM does not.
 *
 * Ships no driver, no pool and no DDL: it takes a [Driver] the application opened and does not know
 * where the database file is, how many connections there are, or how the schema got there
 * ([chronikTimersSchema] states what it needs).
 *
 * **SQLITE, NOT sqlx4k IN GENERAL.** The dependency is the database-agnostic half of sqlx4k, but
 * the SQL below is SQLite's: `UPDATE ... RETURNING` where the Postgres backend takes
 * `SELECT ... FOR UPDATE SKIP LOCKED`, and no advisory locking anywhere. That difference is not a
 * shortcut — see [claimDue].
 */
public class Sqlx4kTimerStore(
    private val db: Driver,
    private val table: String = DEFAULT_TABLE,
) : TransactionalTimerStore {
    init {
        requireIdentifier(table)
    }

    // ---- inside the caller's transaction -------------------------------------------------------

    private fun TimerTransaction.sqlx4k(): Transaction =
        (this as? Sqlx4kTimerTransaction)?.transaction
            ?: error(
                "this store writes through a sqlx4k transaction; got ${this::class.simpleName}. " +
                    "Obtain one with Transaction.asTimerTransaction() inside your own db.transaction { }.",
            )

    override suspend fun insert(
        tx: TimerTransaction,
        timer: Timer,
    ) {
        tx.sqlx4k().update(
            sql(
                "INSERT INTO $table (id, due_at, payload, state, attempts, locked_until, locked_by) " +
                    "VALUES (:id, :dueAt, :payload, :state, :attempts, :lockedUntil, :lockedBy)",
            ).bind("id", timer.id)
                .bind("dueAt", timer.dueAt.value)
                .bind("payload", timer.payload)
                .bind("state", timer.state.name)
                .bind("attempts", timer.attempts)
                .bind("lockedUntil", timer.lockedUntil?.value)
                .bind("lockedBy", timer.lockedBy),
        )
    }

    /**
     * One statement, and the row count is the answer.
     *
     * Not read-then-write, for the same reason as on the Postgres side: a read taken before an
     * earlier statement in this same transaction would miss it, so `cancel` then `reschedule` on
     * one id has to report false for the second. The WHERE clause makes that true without anybody
     * having to remember it.
     */
    override suspend fun reschedule(
        tx: TimerTransaction,
        id: String,
        dueAt: EpochSeconds,
    ): Boolean =
        tx.sqlx4k().update(
            // The lease goes with the due time: a timer moved into the future is not held by
            // whoever claimed it for the old one.
            sql(
                "UPDATE $table SET due_at = :dueAt, locked_until = NULL, locked_by = NULL " +
                    "WHERE id = :id AND state = :pending",
            ).bind("dueAt", dueAt.value)
                .bind("id", id)
                .bind("pending", TimerState.PENDING.name),
        ) > 0

    override suspend fun cancel(
        tx: TimerTransaction,
        id: String,
    ): Boolean =
        tx.sqlx4k().update(
            sql(
                "UPDATE $table SET state = :cancelled, locked_until = NULL, locked_by = NULL " +
                    "WHERE id = :id AND state = :pending",
            ).bind("cancelled", TimerState.CANCELLED.name)
                .bind("id", id)
                .bind("pending", TimerState.PENDING.name),
        ) > 0

    // ---- the worker's own statements -----------------------------------------------------------

    override suspend fun findById(id: String): Timer? =
        db
            .rows(sql("SELECT $COLUMNS FROM $table WHERE id = :id").bind("id", id))
            .firstOrNull()
            ?.toDomain()

    override suspend fun hasDue(now: EpochSeconds): Boolean =
        db
            .rows(
                // "Regardless of who holds it" is the definition of this question, not an
                // oversight: the worker asking has just claimed nothing and wants to know whether
                // there was nothing to do or somebody else got there first. A predicate that also
                // excluded held rows would answer "nothing due" to the loser of every race.
                sql(
                    "SELECT 1 FROM $table WHERE state = :pending AND due_at <= :now LIMIT 1",
                ).bind("pending", TimerState.PENDING.name)
                    .bind("now", now.value),
            ).isNotEmpty()

    /**
     * Claim what is due — one statement, and no `SKIP LOCKED` anywhere.
     *
     * **This is where the two backends genuinely differ.** Postgres needs `FOR UPDATE SKIP LOCKED`
     * because several sessions write at once and two of them would otherwise select the same rows.
     * SQLite has one writer at a time by construction: this `UPDATE` either runs whole or has not
     * started, so a second worker's identical statement sees the rows already stamped with the first
     * one's lease and its own subquery no longer selects them.
     *
     * What does NOT change is the lease itself. A row lock of any kind ends with the statement that
     * took it; `locked_until` is what survives a worker being killed a moment later, and without it
     * the next instance would take a timer that may already have gone out.
     *
     * `RETURNING` rather than a second SELECT, and that is not tidiness: between an `UPDATE` and a
     * `SELECT` of the rows it claimed, another worker's `UPDATE` fits, and this method would report
     * timers it does not hold.
     */
    override suspend fun claimDue(
        now: EpochSeconds,
        leaseUntil: EpochSeconds,
        owner: String,
        limit: Int,
    ): List<Timer> =
        db
            .rows(
                sql(
                    "UPDATE $table SET locked_until = :leaseUntil, locked_by = :owner " +
                        "WHERE id IN (" +
                        "SELECT id FROM $table " +
                        "WHERE state = :pending AND due_at <= :now " +
                        "AND (locked_until IS NULL OR locked_until < :now) " +
                        "ORDER BY due_at LIMIT :limit" +
                        ") RETURNING $COLUMNS",
                ).bind("leaseUntil", leaseUntil.value)
                    .bind("owner", owner)
                    .bind("pending", TimerState.PENDING.name)
                    .bind("now", now.value)
                    .bind("limit", limit),
            ).map { it.toDomain() }
            // RETURNING does not promise an order, and the worker's batch is supposed to be the
            // oldest timers first: the ORDER BY inside the subquery picks WHICH rows, not the order
            // they come back in.
            .sortedBy { it.dueAt.value }

    override suspend fun markFired(id: String): Unit = mark(id, TimerState.FIRED)

    override suspend fun markDeadLettered(id: String): Unit = mark(id, TimerState.DEAD_LETTERED)

    private suspend fun mark(
        id: String,
        to: TimerState,
    ) {
        db.update(
            sql(
                "UPDATE $table SET state = :state, locked_until = NULL, locked_by = NULL WHERE id = :id",
            ).bind("state", to.name)
                .bind("id", id),
        )
    }

    /**
     * A failed delivery: the attempt count moves and the hold is pushed out to [retryAfter].
     *
     * Releasing the hold here would hand the timer to another instance at once, which turns a sink
     * that is down into a stampede. The backoff lives in the row precisely so that every instance
     * honours it — an interval kept in one worker's memory is one the others never see.
     */
    override suspend fun markFailed(
        id: String,
        retryAfter: EpochSeconds,
    ) {
        db.update(
            // An increment in SQL, not a read followed by a write: two workers failing to deliver
            // the same timer must not each write back the same count.
            sql(
                "UPDATE $table SET attempts = attempts + 1, locked_until = :retryAfter WHERE id = :id",
            ).bind("retryAfter", retryAfter.value)
                .bind("id", id),
        )
    }

    private fun ResultSet.Row.toDomain(): Timer =
        Timer(
            id = get("id").asString(),
            dueAt = EpochSeconds(get("due_at").asLong()),
            payload = get("payload").asString(),
            state = TimerState.valueOf(get("state").asString()),
            attempts = get("attempts").asInt(),
            lockedUntil = get("locked_until").asLongOrNull()?.let(::EpochSeconds),
            lockedBy = get("locked_by").asStringOrNull(),
        )

    private companion object {
        /**
         * Named rather than `*`, because `RETURNING *` and a row reader that asks for columns by
         * name is a pair that keeps working while the table gains a column and stops the day it
         * loses one — with an error naming the column and not the cause.
         */
        const val COLUMNS = "id, due_at, payload, state, attempts, locked_until, locked_by"
    }
}

/**
 * Parameters are **named only**.
 *
 * sqlx4k takes positional ones too, and in a statement with seven fields getting the order wrong is
 * a matter of time — silently, because the types line up and the values land in the wrong columns.
 */
private fun sql(text: String): Statement = Statement.create(text)

/** How many rows the statement changed. */
private suspend fun QueryExecutor.update(statement: Statement): Long = execute(statement).getOrThrow()

/** The rows the statement produced. */
private suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
    fetchAll(statement)
        .getOrThrow()
        .rows
