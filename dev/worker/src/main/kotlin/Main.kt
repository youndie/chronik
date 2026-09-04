package ru.workinprogress.chronik.dev

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.workinprogress.chronik.Chronik
import ru.workinprogress.chronik.ChronikClock
import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.TimerSink
import ru.workinprogress.chronik.TimerWorker
import ru.workinprogress.chronik.postgres.ExposedTimerStore
import ru.workinprogress.chronik.postgres.TimersTable
import ru.workinprogress.chronik.postgres.asTimerTransaction

/**
 * Append-only, and that is the whole measuring instrument.
 *
 * "Delivered" is easy to observe and useless: the claim being tested is delivered EXACTLY ONCE in
 * this scenario, and only a table that keeps every delivery can tell one from two. A row per
 * delivery, never updated, so a second delivery cannot hide behind the first.
 */
object DeliveriesTable : Table("handover_deliveries") {
    val timerId = varchar("timer_id", 255)
    val workerId = varchar("worker_id", 255)
    val atMillis = long("at_millis")
}

private fun env(
    name: String,
    default: String,
): String = System.getenv(name) ?: default

fun main(args: Array<String>) {
    val db =
        Database.connect(
            url = env("CHRONIK_DB_URL", "jdbc:postgresql://127.0.0.1:55432/chronik"),
            driver = "org.postgresql.Driver",
            user = env("CHRONIK_DB_USER", "chronik"),
            password = env("CHRONIK_DB_PASSWORD", "chronik"),
        )
    val table = TimersTable()
    val store = ExposedTimerStore(db, table)
    // The real clock here, unlike everywhere else in this repository: the stand's whole point is
    // that time passes for real while a process is dead.
    val clock = ChronikClock { EpochSeconds(System.currentTimeMillis() / 1000) }

    when (args.firstOrNull()) {
        "seed" -> seed(db, table, store, clock)
        "work" -> work(store, clock)
        else -> error("usage: seed | work")
    }
}

/**
 * Create the schema and put one timer in it, due immediately.
 *
 * chronik ships no DDL, so this is the application's job — and doing it here rather than in the
 * shell script keeps the table definition as the single description of itself.
 */
private fun seed(
    db: Database,
    table: TimersTable,
    store: ExposedTimerStore,
    clock: ChronikClock,
) {
    transaction(db) {
        SchemaUtils.drop(DeliveriesTable, table)
        SchemaUtils.create(table, DeliveriesTable)
    }

    val chronik = Chronik(store, clock)
    transaction(db) {
        runBlocking {
            chronik.schedule(asTimerTransaction(), "handover", clock.now(), """{"key":"handover"}""")
        }
    }
    println("seeded: one timer due at ${clock.now()}")
}

private fun work(
    store: ExposedTimerStore,
    clock: ChronikClock,
) {
    val id = env("CHRONIK_WORKER_ID", "worker")
    val lease = env("CHRONIK_LEASE_SECONDS", "8").toLong()

    /**
     * The sink stalls before it records anything, and that is what makes the kill point
     * deterministic.
     *
     * Without it the window between "claimed" and "delivered" is microseconds and the stand would
     * be a race the shell has no chance of winning — it would kill a worker that had already
     * delivered, and then pass for the wrong reason. The stall lives in the APPLICATION's sink, not
     * in the library: a slow receiver is an ordinary thing, and chronik must behave the same way
     * whether or not one is present.
     */
    val stallMillis = env("CHRONIK_SINK_STALL_MS", "6000").toLong()

    val sink =
        TimerSink { fired ->
            Thread.sleep(stallMillis)
            transaction {
                DeliveriesTable.insert {
                    it[timerId] = fired.id
                    it[workerId] = id
                    it[atMillis] = System.currentTimeMillis()
                }
            }
            println("$id delivered ${fired.id}, late by ${fired.lateness}s")
        }

    val worker =
        TimerWorker(
            store = store,
            sink = sink,
            clock = clock,
            owner = id,
            leaseSeconds = lease,
            onFired = { timerId, lateness -> println("$id fired $timerId late=$lateness") },
            onDeliveryFailed = { timerId, cause -> println("$id failed $timerId: ${cause.message}") },
            onLostRace = { println("$id lost the race") },
        )

    println("$id up, lease ${lease}s, sink stall ${stallMillis}ms")
    runBlocking {
        worker.start(CoroutineScope(Dispatchers.Default + SupervisorJob())).join()
    }
}
