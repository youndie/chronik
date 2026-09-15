package io.github.youndie.chronik.sqlx4k.sqlite

import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.conformance.ConformanceKit
import io.github.youndie.chronik.conformance.TimerStoreSubject
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The corpus, against the second backend — which is what the corpus was built for.
 *
 * [B-12](../../../docs/backlog/B-12-conformance-kit.md) wrote these cases while there was one
 * implementation, on the argument that a corpus written after the second describes the intersection
 * of the two rather than the contract. This run is the first time that argument is cashed in: the
 * cases are unchanged, and SQLite is measured against what chronik promises rather than consulted
 * about what it does.
 *
 * It runs on the JVM **and** on linuxX64, from this one source. That is not symmetry for its own
 * sake — sqlx4k is two different drivers behind one API, and the neighbouring repository that
 * assumed otherwise found three behavioural differences between them.
 */
class SqliteConformanceTest {
    private val db = SqliteHarness.open("conformance")

    private val subject =
        object : TimerStoreSubject {
            override val store = SqliteTimerStore(db)

            override suspend fun committed(body: suspend (TimerTransaction) -> Unit) {
                db.transaction { body(asTimerTransaction()) }
            }

            override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) {
                // A real abandonment rather than a flag: the caller's own step refused, which is
                // the shape chronik has to survive. Nothing is recorded here because the kit case
                // that uses this is itself proven able to fail — `KitCatchesViolationsTest` runs it
                // against a subject that commits what it was asked to abandon.
                @Suppress(
                    "SwallowedResult",
                    "ktlint:kapkan:cancellation-swallowed",
                    "the throw IS the mechanism here, and nothing cancels a test scope",
                )
                val ignored =
                    runCatching {
                        db.transaction {
                            body(asTimerTransaction())
                            error("the caller's step refused")
                        }
                    }
            }

            override suspend fun reset() {
                db.execute("DELETE FROM $DEFAULT_TABLE;").getOrThrow()
            }
        }

    @Test
    fun `the SQLite store satisfies every rule in the corpus`() =
        runTest {
            db.createTimers()

            val findings = ConformanceKit().run(subject)

            assertTrue(
                findings.isEmpty(),
                "the SQLite store violates:\n" + findings.joinToString("\n") { "  ${it.rule}\n      ${it.detail}" },
            )
        }
}
