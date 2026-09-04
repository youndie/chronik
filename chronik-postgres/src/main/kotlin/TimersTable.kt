package ru.workinprogress.chronik.postgres

import org.jetbrains.exposed.v1.core.Table

/**
 * The timers table, describing itself — indexes included.
 *
 * NO DDL SHIPS WITH THIS MODULE, and the indexes being declared here rather than mentioned in a
 * comment is what makes that workable. A schema generator asks Exposed what the tables need; an
 * index that exists only in prose is absent from that answer, so the generator asks the application
 * to DROP the one its migration created. That exact defect has been paid for once already in a
 * neighbouring library — three indexes described in column comments and declared nowhere — and it
 * surfaces in the consumer's repository, not here.
 */
class TimersTable(
    name: String = "chronik_timers",
) : Table(name) {
    val id = varchar("id", 255)

    /**
     * Whole seconds since the epoch, and a `bigint` rather than a timestamp.
     *
     * The unit is the library's promise (D5), and a timestamp column would quietly offer more
     * precision than the library keeps — which is how a caller ends up believing in milliseconds
     * that the poll interval cannot deliver.
     */
    val dueAt = long("due_at")

    val payload = text("payload")

    /** PENDING / FIRED / CANCELLED / DEAD_LETTERED, as the name rather than the ordinal. */
    val state = varchar("state", 32)

    val attempts = integer("attempts").default(0)

    /** When the current claim expires; null when nobody holds it. */
    val lockedUntil = long("locked_until").nullable()

    val lockedBy = varchar("locked_by", 255).nullable()

    // DERIVED FROM THE TABLE NAME, NOT FIXED. In Postgres a constraint or index name belongs to the
    // schema rather than to the table, so a hard-coded one turns the `name` parameter into a lie:
    // the first table creates, and the second fails with "relation pk_chronik_timers already
    // exists". Two schedulers in one schema — a test suite with a table per case is the small
    // version of that, an application running two independent sets of timers is the real one.
    override val primaryKey = PrimaryKey(id, name = "pk_$name")

    init {
        // The selection is "state = PENDING and due_at <= now", so the index leads with state and
        // then due_at. Without it the claim scans every timer ever written, including the terminal
        // ones, which are the ones that accumulate: a table that is mostly FIRED rows is the normal
        // steady state of this library, not an edge case.
        index("idx_${name}_state_due_at", isUnique = false, state, dueAt)
    }
}
