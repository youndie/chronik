package ru.workinprogress.chronik.conformance

import ru.workinprogress.chronik.TimerTransaction
import ru.workinprogress.chronik.TransactionalTimerStore

/**
 * A storage implementation, plus the two things the corpus cannot do for itself: run a transaction
 * to completion, and abandon one.
 *
 * Those are not part of [TransactionalTimerStore] and must not be. chronik never opens a
 * transaction — the whole guarantee is that the caller's is used — so how a transaction begins,
 * commits and rolls back belongs to whoever is being tested.
 */
interface TimerStoreSubject {
    val store: TransactionalTimerStore

    /** Run [body] inside a transaction and commit it. */
    suspend fun committed(body: suspend (TimerTransaction) -> Unit)

    /**
     * Run [body] inside a transaction and abandon it.
     *
     * A rollback, a crash and a connection dropped mid-flight are the same thing from storage's
     * side, which is why the corpus asks for only this one.
     */
    suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit)

    /** Leave no timers behind. Called between cases so one case cannot explain another's result. */
    suspend fun reset()
}

/**
 * One violation. Carries the rule rather than a case number, because a number tells the person
 * fixing it nothing — they have to find the case to learn what was expected.
 */
data class Finding(
    /** The rule from the feature document, in the words it is written in there. */
    val rule: String,
    val detail: String,
)
