package io.github.youndie.chronik.benchmark

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.github.youndie.chronik.ChronikClock
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.TimerSink
import io.github.youndie.chronik.TimerState
import io.github.youndie.chronik.TimerWorker
import io.github.youndie.chronik.postgres.ExposedTimerStore
import io.github.youndie.chronik.postgres.TimersTable
import kotlin.system.measureNanoTime

/**
 * The stand that produces the numbers in docs/benchmarking.md.
 *
 * Two questions, and the second exists to keep the first honest:
 *
 *  1. how late does a timer fire, at a given rate, on one core next to Postgres;
 *  2. at what number of waiting rows does the claim degrade WITHOUT the index on (state, due_at),
 *     and with it.
 *
 * The second is the positive control. A run in which every variant looks fine has not measured
 * anything — it cannot distinguish a stand that works from one that is timing the wrong thing. If
 * dropping the index does not make the claim visibly worse, the claim is not what is being timed.
 */
private fun env(
    name: String,
    default: String,
): String = System.getenv(name) ?: default

/**
 * A pooled connection, because the alternative measures the network.
 *
 * Exposed opens a connection per transaction when it is handed a URL. The first version of this
 * stand did exactly that and reported 12-22 ms for a claim whose query plan, asked of Postgres
 * directly, takes 0.072 ms — so 99% of every figure was TCP and authentication, and dropping the
 * index changed nothing measurable. The positive control is what said so: without the index the
 * claim came out FASTER at ten thousand rows, which is not a thing that can happen and therefore
 * meant the stand was timing something else.
 */
private val db by lazy {
    val config =
        HikariConfig().apply {
            jdbcUrl = env("CHRONIK_DB_URL", "jdbc:postgresql://127.0.0.1:55432/chronik")
            driverClassName = "org.postgresql.Driver"
            username = env("CHRONIK_DB_USER", "chronik")
            password = env("CHRONIK_DB_PASSWORD", "chronik")
            maximumPoolSize = 8
        }
    Database.connect(HikariDataSource(config))
}

private val clock = ChronikClock { EpochSeconds(System.currentTimeMillis() / 1000) }

fun main() {
    println("chronik benchmark")
    println("  postgres: ${env("CHRONIK_DB_URL", "jdbc:postgresql://127.0.0.1:55432/chronik")}")
    println()

    claimScan()
    println()
    lateness()
}

// ---------------------------------------------------------------------------------------------
// 1. How the claim behaves as waiting rows pile up, with and without the index.
// ---------------------------------------------------------------------------------------------

private fun claimScan() {
    val sizes = env("CHRONIK_BENCH_SIZES", "10000,100000,400000").split(",").map { it.trim().toInt() }
    val repetitions = env("CHRONIK_BENCH_REPS", "25").toInt()

    println("claim over a table of waiting timers — median and p95 of one claimDue, milliseconds")
    println("  rows        with index          without index       ratio")

    for (size in sizes) {
        val withIndex = measureClaim(size, indexed = true, repetitions)
        val without = measureClaim(size, indexed = false, repetitions)

        // Both absolutes are printed, and the ratio last. A ratio on its own decides nothing: "four
        // times slower" is a different fact at 0.2 ms and at 200 ms, and only one of them is a
        // reason to do anything.
        println(
            "  %-10d  %6.2f / %6.2f      %6.2f / %6.2f      %.1fx".format(
                size, withIndex.median, withIndex.p95, without.median, without.p95,
                without.median / withIndex.median.coerceAtLeast(0.001),
            ),
        )
    }
}

private class Sample(
    val median: Double,
    val p95: Double,
)

private fun measureClaim(
    rows: Int,
    indexed: Boolean,
    repetitions: Int,
): Sample {
    val table = TimersTable("bench_timers")
    val store = ExposedTimerStore(db, table)

    transaction(db) {
        SchemaUtils.drop(table)
        SchemaUtils.create(table)
        if (!indexed) {
            // Dropped rather than never declared: the library's table describes itself, and a
            // benchmark that built a different table would be measuring a different thing.
            exec("DROP INDEX idx_bench_timers_state_due_at")
        }
    }

    val now = clock.now().value
    // The rows that pile up are the ones nobody is going to claim: due far in the future, and
    // already-fired ones. That is the steady state of a live table, and it is exactly what a
    // sequential scan has to walk through to find the handful that are due.
    val future = (1..rows).map { it }
    transaction(db) {
        future.chunked(5_000).forEach { chunk ->
            table.batchInsert(chunk) { n ->
                this[table.id] = "waiting-$n"
                this[table.dueAt] = now + 86_400 + n
                this[table.payload] = "{}"
                this[table.state] = if (n % 2 == 0) TimerState.FIRED.name else TimerState.PENDING.name
                this[table.attempts] = 0
            }
        }
        exec("ANALYZE bench_timers")
    }

    val timings = mutableListOf<Double>()
    // One extra pass before anything is recorded. The first claim after a restart pays for the
    // connection, the plan and a cold cache, and a stand that keeps it is measuring the warm-up.
    repeat(repetitions + 1) { attempt ->
        transaction(db) {
            table.deleteAll2()
            table.batchInsert((1..20).toList()) { n ->
                this[table.id] = "due-$attempt-$n"
                this[table.dueAt] = clock.now().value - 1
                this[table.payload] = "{}"
                this[table.state] = TimerState.PENDING.name
                this[table.attempts] = 0
            }
        }

        val nanos =
            measureNanoTime {
                runBlocking { store.claimDue(clock.now(), clock.now() + 60, "bench", 20) }
            }
        if (attempt > 0) timings += nanos / 1_000_000.0
    }

    timings.sort()
    return Sample(median = timings[timings.size / 2], p95 = timings[(timings.size * 95) / 100])
}

/** Delete only the due rows the previous repetition inserted, leaving the pile-up in place. */
private fun org.jetbrains.exposed.v1.core.Table.deleteAll2() {
    org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
        .current()
        .exec("DELETE FROM bench_timers WHERE id LIKE 'due-%'")
}

// ---------------------------------------------------------------------------------------------
// 2. How late a timer fires when a batch of them comes due at once.
// ---------------------------------------------------------------------------------------------

private fun lateness() {
    val count = env("CHRONIK_BENCH_TIMERS", "5000").toInt()
    val batch = env("CHRONIK_BENCH_BATCH", "200").toInt()

    val table = TimersTable("bench_timers")
    val store = ExposedTimerStore(db, table)

    transaction(db) {
        SchemaUtils.drop(table)
        SchemaUtils.create(table)
    }

    val dueAt = clock.now().value
    transaction(db) {
        (1..count).chunked(5_000).forEach { chunk ->
            table.batchInsert(chunk) { n ->
                this[table.id] = "late-$n"
                this[table.dueAt] = dueAt
                this[table.payload] = "{}"
                this[table.state] = TimerState.PENDING.name
                this[table.attempts] = 0
            }
        }
        exec("ANALYZE bench_timers")
    }

    val lateness = mutableListOf<Long>()
    val worker =
        TimerWorker(
            store = store,
            sink = TimerSink { },
            clock = clock,
            owner = "bench",
            leaseSeconds = 120,
            batchSize = batch,
            onFired = { _, late -> lateness += late },
        )

    val elapsed =
        measureNanoTime {
            runBlocking {
                while (lateness.size < count) {
                    if (worker.tick() == 0) break
                }
            }
        } / 1_000_000_000.0

    if (lateness.isEmpty()) {
        println("lateness: nothing fired — the stand measured itself, not the library")
        return
    }

    lateness.sort()
    val rate = lateness.size / elapsed.coerceAtLeast(0.001)
    println("lateness of a batch that all comes due at once — seconds")
    println("  timers  batch  fired  elapsed s  rate/s   p50  p95  max")
    println(
        "  %-6d  %-5d  %-5d  %-9.2f  %-7.0f  %-3d  %-3d  %d".format(
            count, batch, lateness.size, elapsed, rate,
            lateness[lateness.size / 2], lateness[(lateness.size * 95) / 100], lateness.last(),
        ),
    )
    println()
    println("  Lateness is in WHOLE SECONDS because that is the unit chronik keeps (D5). A p50 of 0")
    println("  means the batch drained inside its own second, not that the measurement is missing.")
}
