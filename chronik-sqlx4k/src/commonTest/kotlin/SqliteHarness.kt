package io.github.youndie.chronik.sqlx4k

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * A real SQLite, in a file, opened once per test class.
 *
 * **A FILE AND NOT `:memory:`, on purpose.** sqlx4k is two drivers behind one API — Rust on
 * Kotlin/Native, Xerial's sqlite-jdbc on the JVM — and they disagree about in-memory databases: the
 * JVM half refuses a pool larger than one ("each connection creates a separate in-memory database
 * instance"), and a pool pinned to one deadlocks the moment a transaction holds the single
 * connection while the code inside asks for a second. A file is also the shape an application runs
 * in, which is the stronger argument of the two.
 *
 * Not a mock, for the reason the Postgres harness gives: a mock returns what the test put in it, so
 * it proves nothing about SQL — and the defect a store test exists to catch lives in the SQL.
 */
object SqliteHarness {
    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "chronik-sqlx4k-tests"

    /**
     * Two connections, because the store needs two at once.
     *
     * The corpus holds the caller's transaction open and the store's own statements run outside it
     * — which is chronik's whole design, not a quirk of the test. With one connection those two
     * wait on each other for ever.
     */
    private const val POOL = 2

    /**
     * A database per test class, named by the caller.
     *
     * Deleted first rather than after: a run that failed leaves its file behind for reading, and
     * the next run starts from an empty one either way.
     */
    fun open(name: String): Driver {
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(directory)
        val path = directory / "$name.db"
        // The journal and the write-ahead log are separate files, and a stale one alongside a fresh
        // database is a different database than the one this test means to open.
        listOf(path, "$path-wal".toPath(), "$path-journal".toPath(), "$path-shm".toPath())
            .forEach { fileSystem.delete(it, mustExist = false) }
        // An empty file is a valid empty database, and the driver does not create the file itself.
        fileSystem.write(path) { }

        return sqlite(
            url = "sqlite://$path",
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(POOL)
                    .build(),
        )
    }
}

/** Apply the schema this module states, the way an application's own migration would. */
suspend fun Driver.createTimers(table: String = DEFAULT_TABLE) {
    chronikTimersSchema(table).forEach { execute(it).getOrThrow() }
}
