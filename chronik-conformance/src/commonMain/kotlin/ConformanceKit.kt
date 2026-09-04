package ru.workinprogress.chronik.conformance

import ru.workinprogress.chronik.EpochSeconds
import ru.workinprogress.chronik.Timer
import ru.workinprogress.chronik.TimerState

/**
 * Every rule a storage implementation has to satisfy, as cases that can be run against it.
 *
 * WHY THIS EXISTS WHILE THERE IS ONE IMPLEMENTATION. A corpus written after the second backend
 * describes the intersection of the two, not the contract: whatever both happen to do becomes the
 * rule, including whatever both happen to get wrong. Written first, it is a statement about what
 * chronik promises, and the second implementation is measured against it rather than consulted.
 *
 * CASES ARE NAMED BY THE RULE, not numbered. Somebody reading a failure needs to know what was
 * expected, and "case 7" sends them looking for the case to find out.
 *
 * FINDINGS ARE COLLECTED, NOT THROWN. A run reaches the end and shows everything, because a new
 * backend otherwise gets fixed one finding per run.
 */
class ConformanceKit {
    /** Every case, in the order they are run. Public so a reader can see the corpus without running it. */
    val cases: List<Case> = corpus()

    class Case internal constructor(
        val rule: String,
        internal val check: suspend (TimerStoreSubject) -> String?,
    )

    /**
     * Run the whole corpus. An empty list means the implementation satisfies every rule.
     *
     * A case that throws is a finding too, and its message says so: an implementation that fails
     * with an exception has not passed, and swallowing that would be the kit lying on its behalf.
     */
    suspend fun run(subject: TimerStoreSubject): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (case in cases) {
            subject.reset()
            val detail =
                try {
                    case.check(subject)
                } catch (e: Throwable) {
                    "the implementation threw: ${e::class.simpleName}: ${e.message}"
                }
            if (detail != null) findings += Finding(case.rule, detail)
        }
        return findings
    }

    private fun case(
        rule: String,
        check: suspend (TimerStoreSubject) -> String?,
    ) = Case(rule, check)

    private fun corpus(): List<Case> =
        listOf(
            case("a committed timer exists and is claimable once it is due") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                val claimed = s.store.claimDue(at(10), at(40), "w", 10)
                when {
                    s.store.findById("t1") == null -> "the timer is not in storage after its transaction committed"

                    claimed.map { it.id } !=
                        listOf(
                            "t1",
                        )
                    -> "claimDue at the due second returned ${claimed.map { it.id }}"

                    else -> null
                }
            },
            case("a timer written in an abandoned transaction does not exist") { s ->
                s.rolledBack { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                when {
                    s.store.findById("t1") != null -> "the timer survived a rollback"
                    s.store.claimDue(at(100), at(130), "w", 10).isNotEmpty() -> "an abandoned timer was claimed"
                    else -> null
                }
            },
            case("nothing is claimable before its due second") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                val early = s.store.claimDue(at(9), at(39), "w", 10)
                if (early.isNotEmpty()) "claimDue at 9 returned a timer due at 10" else null
            },
            case("a live lease hides the timer from everybody else") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(40), "first", 10)
                val second = s.store.claimDue(at(20), at(50), "second", 10)
                if (second.isNotEmpty()) "a second claim took a timer whose lease runs to 40" else null
            },
            case("a lapsed lease makes the timer claimable again") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(15), "doomed", 10)
                val after = s.store.claimDue(at(16), at(46), "survivor", 10)
                if (after.map { it.id } != listOf("t1")) {
                    "after the lease lapsed at 15, a claim at 16 returned ${after.map { it.id }}"
                } else {
                    null
                }
            },
            case("the lease belongs to the second it expires in") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(15), "holder", 10)
                val onTheSecond = s.store.claimDue(at(15), at(45), "other", 10)
                if (onTheSecond.isNotEmpty()) "a claim at 15 took a timer whose lease runs to 15" else null
            },
            case("a cancelled timer never fires") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.committed { tx -> s.store.cancel(tx, "t1") }
                when {
                    s.store.claimDue(at(10_000), at(10_030), "w", 10).isNotEmpty() -> {
                        "a cancelled timer was claimed"
                    }

                    s.store.hasDue(at(10_000)) -> {
                        "a cancelled timer still counts as due"
                    }

                    else -> {
                        null
                    }
                }
            },
            case("cancelling after the due second but before the event still works") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                var reported = false
                s.committed { tx -> reported = s.store.cancel(tx, "t1") }
                when {
                    !reported -> "cancel reported false for a timer that is due but has not fired"
                    s.store.claimDue(at(50), at(80), "w", 10).isNotEmpty() -> "the cancelled timer was claimed"
                    else -> null
                }
            },
            case("cancel and reschedule report false for a timer that does not exist") { s ->
                var cancelled = true
                var rescheduled = true
                s.committed { tx ->
                    cancelled = s.store.cancel(tx, "never-existed")
                    rescheduled = s.store.reschedule(tx, "never-existed", at(10))
                }
                when {
                    cancelled -> "cancel reported true for an id that was never scheduled"
                    rescheduled -> "reschedule reported true for an id that was never scheduled"
                    s.store.findById("never-existed") != null -> "reschedule created a timer; it is not an insert"
                    else -> null
                }
            },
            case("reschedule moves the existing timer rather than adding a second") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.committed { tx -> s.store.reschedule(tx, "t1", at(100)) }
                val atOldTime = s.store.claimDue(at(10), at(40), "w", 10)
                val atNewTime = s.store.claimDue(at(100), at(130), "w", 10)
                when {
                    atOldTime.isNotEmpty() -> "the timer was still claimable at its old due second"

                    atNewTime.map { it.id } !=
                        listOf(
                            "t1",
                        )
                    -> "at the new due second the claim returned ${atNewTime.map { it.id }}"

                    else -> null
                }
            },
            case("an operation sees an earlier operation in the same transaction") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                var second = true
                s.committed { tx ->
                    s.store.cancel(tx, "t1")
                    second = s.store.reschedule(tx, "t1", at(99))
                }
                if (second) {
                    "reschedule reported true after a cancel earlier in the same transaction; " +
                        "the row was already terminal by then"
                } else {
                    null
                }
            },
            case("a failed delivery counts an attempt and holds the timer until its backoff") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(20), "w", 10)
                s.store.markFailed("t1", at(60))
                val stored = s.store.findById("t1")
                when {
                    stored?.attempts != 1 -> {
                        "attempts is ${stored?.attempts} after one failure"
                    }

                    stored.state != TimerState.PENDING -> {
                        "a failed delivery left the timer ${stored.state}"
                    }

                    s.store.claimDue(at(30), at(60), "other", 10).isNotEmpty() -> {
                        "another worker claimed the timer inside its backoff; the wait is not in the row"
                    }

                    s.store.claimDue(at(61), at(91), "other", 10).isEmpty() -> {
                        "the timer stayed held after its backoff expired"
                    }

                    else -> {
                        null
                    }
                }
            },
            case("a dead lettered timer is never selected again") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.markDeadLettered("t1")
                when {
                    s.store.claimDue(at(10_000), at(10_030), "w", 10).isNotEmpty() -> {
                        "a dead lettered timer was claimed"
                    }

                    s.store.hasDue(at(10_000)) -> {
                        "a dead lettered timer still counts as due"
                    }

                    else -> {
                        null
                    }
                }
            },
            case("a fired timer is terminal and cannot be reopened") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(40), "w", 10)
                s.store.markFired("t1")
                var reopened = true
                s.committed { tx -> reopened = s.store.reschedule(tx, "t1", at(200)) }
                when {
                    reopened -> "reschedule reported true for a fired timer"
                    s.store.claimDue(at(500), at(530), "w", 10).isNotEmpty() -> "a fired timer was claimed again"
                    else -> null
                }
            },
            case("hasDue looks past the lease, or the loser of a race is told nothing was due") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                s.store.claimDue(at(10), at(99), "fast", 10)
                if (!s.store.hasDue(at(10))) {
                    "hasDue reported nothing due while a due timer was held by another worker; " +
                        "a worker that just lost the race would be told there was nothing to lose"
                } else {
                    null
                }
            },
            case("claimDue respects its limit and takes the earliest first") { s ->
                s.committed { tx ->
                    s.store.insert(tx, timer("late", due = 30))
                    s.store.insert(tx, timer("early", due = 10))
                    s.store.insert(tx, timer("middle", due = 20))
                }
                val claimed = s.store.claimDue(at(100), at(130), "w", 2)
                when {
                    claimed.size != 2 -> {
                        "a limit of 2 returned ${claimed.size} timers"
                    }

                    claimed.map { it.id } != listOf("early", "middle") -> {
                        "the claim returned ${claimed.map { it.id }}; the earliest due must go first"
                    }

                    else -> {
                        null
                    }
                }
            },
            case("a claim reports the lease it took, not the one it found") { s ->
                s.committed { tx -> s.store.insert(tx, timer("t1", due = 10)) }
                val claimed = s.store.claimDue(at(10), at(40), "worker-7", 10).singleOrNull()
                when {
                    claimed == null -> "the claim returned nothing"
                    claimed.lockedUntil != at(40) -> "the returned timer carries lease ${claimed.lockedUntil}"
                    claimed.lockedBy != "worker-7" -> "the returned timer carries owner ${claimed.lockedBy}"
                    else -> null
                }
            },
        )

    private fun timer(
        id: String,
        due: Long,
    ) = Timer(id = id, dueAt = EpochSeconds(due), payload = """{"id":"$id"}""")

    private fun at(second: Long) = EpochSeconds(second)
}
