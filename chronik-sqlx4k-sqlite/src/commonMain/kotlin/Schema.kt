package io.github.youndie.chronik.sqlx4k.sqlite

/**
 * The statements that create the timers table, as text the application runs itself.
 *
 * **chronik still ships no DDL, and this is not a departure from that.** The rule is that chronik
 * does not own the schema's lifecycle — it opens no connection, runs no migration and decides no
 * version. What it must do is *state* what the queries need, because an index that lives only in
 * prose is one a migration does not create and a benchmark does not have.
 *
 * On the JVM half of chronik that statement is the Exposed table object, which a schema generator
 * can read. Kotlin/Native has no such generator, so here it is the SQL itself: append these to your
 * own migration list, next to your own tables, and keep the version numbering you already have.
 * chronik never executes them.
 *
 * @param table the table name, which must be a plain SQL identifier — see [requireIdentifier].
 */
public fun chronikTimersSchema(table: String = DEFAULT_TABLE): List<String> {
    requireIdentifier(table)
    return listOf(
        """
        CREATE TABLE IF NOT EXISTS $table (
            id TEXT PRIMARY KEY,
            due_at INTEGER NOT NULL,
            payload TEXT NOT NULL,
            state TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            locked_until INTEGER,
            locked_by TEXT
        );
        """.trimIndent(),
        // The selection is "state = PENDING and due_at <= now", so the index leads with state and
        // then due_at — the same shape as the Postgres backend's, and for the same reason: a table
        // that is mostly FIRED rows is this library's normal steady state, not an edge case, and
        // without the index the claim scans every timer ever written.
        "CREATE INDEX IF NOT EXISTS idx_${table}_state_due_at ON $table (state, due_at);",
    )
}

/** The table this store reads and writes unless it is told another name. */
public const val DEFAULT_TABLE: String = "chronik_timers"

/**
 * A table name reaches SQL as an identifier, and an identifier cannot be bound.
 *
 * Every value in this module travels as a named parameter, which is what keeps a payload with a
 * quote in it from becoming syntax. A table name cannot: there is no parameter form for an
 * identifier in any dialect, so it is interpolated — and therefore checked here, once, at the point
 * where the name is accepted rather than at each of the eight queries that carry it.
 */
internal fun requireIdentifier(name: String) {
    require(name.isNotEmpty() && name.all { it == '_' || it.isDigit() || (it in 'a'..'z') || (it in 'A'..'Z') }) {
        "a table name must be a plain SQL identifier (letters, digits and underscore); got '$name'"
    }
}
