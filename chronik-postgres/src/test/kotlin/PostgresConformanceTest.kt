package io.github.youndie.chronik.postgres

import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.conformance.ConformanceKit
import io.github.youndie.chronik.conformance.TimerStoreSubject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The corpus, against the backend it was written before.
 *
 * The order matters and is the point of writing the kit while there is one implementation: this run
 * measures Postgres against what chronik promises, rather than turning whatever Postgres happens to
 * do into the promise. When a second backend arrives it meets the same corpus, unchanged.
 */
class PostgresConformanceTest {
    private val table = PostgresHarness.freshTable("conformance_timers")
    private val db = PostgresHarness.database

    private val subject =
        object : TimerStoreSubject {
            override val store = ExposedTimerStore(db, table)

            override suspend fun committed(body: suspend (TimerTransaction) -> Unit) {
                transaction(db) { runBlocking { body(asTimerTransaction()) } }
            }

            override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) {
                // The throw IS the mechanism here, and nothing else runs inside: the corpus case
                // that uses this is itself proven able to fail, by a subject that commits what it
                // was asked to abandon (KitCatchesViolationsTest). That guard is why this one does
                // not need to record anything.
                @Suppress(
                    "SwallowedResult",
                    "ktlint:kapkan:cancellation-swallowed",
                    "the throw IS the mechanism here, and nothing cancels a test scope",
                )
                val ignored =
                    runCatching {
                        transaction(db) {
                            runBlocking { body(asTimerTransaction()) }
                            // A real abandonment rather than a flag: the caller's own step refused, and
                            // that is the shape chronik has to survive.
                            error("the caller's step refused")
                        }
                    }
            }

            override suspend fun reset() {
                transaction(db) { exec("DELETE FROM conformance_timers") }
            }
        }

    @Test
    fun `the Postgres store satisfies every rule in the corpus`() =
        runTest {
            val findings = ConformanceKit().run(subject)

            assertTrue(
                findings.isEmpty(),
                "the Postgres store violates:\n" + findings.joinToString("\n") { "  ${it.rule}\n      ${it.detail}" },
            )
        }
}
