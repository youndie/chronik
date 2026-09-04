package ru.workinprogress.chronik

/**
 * What a timer looks like in storage.
 *
 * [payload] is already-serialised text and chronik never looks inside it. Knowing the business type
 * would make this a part of somebody's feature rather than a portable mechanism — the same split
 * the neighbouring engine draws for its outbox rows.
 *
 * The idempotency key is deliberately NOT a field here. Delivery is at-least-once and deduplication
 * belongs to the receiver, so the key has to travel where the receiver reads it — inside the
 * payload the application composed. A field here would suggest chronik does something with it.
 */
public data class Timer(
    public val id: String,
    public val dueAt: EpochSeconds,
    public val payload: String,
    public val state: TimerState = TimerState.PENDING,
    /**
     * How many delivery attempts have failed. Kept in storage rather than in the worker's memory
     * because this is what sends a timer to the dead letter: a counter that a restart resets means
     * a systematically failing timer retries for ever and never gets there.
     */
    public val attempts: Int = 0,
    /**
     * Whose it is and until when, in whole seconds. `null` means nobody holds it.
     *
     * This is a lease and not a lock, and the difference is the whole of D3: a row lock lives until
     * the end of the transaction, so an instance killed after the claim commits releases the row
     * INSTANTLY — and the next instance would take a timer that may already have been delivered.
     * A deadline in a column is what makes "claimable again in N seconds" a thing that exists.
     */
    public val lockedUntil: EpochSeconds? = null,
    public val lockedBy: String? = null,
) {
    /** Due, and not held by a live lease. */
    public fun isClaimableAt(now: EpochSeconds): Boolean =
        state == TimerState.PENDING &&
            dueAt <= now &&
            (lockedUntil == null || lockedUntil < now)

    /**
     * How late this firing is against its due time, in seconds; never negative.
     *
     * Lateness is a supported outcome rather than an error (D7), and it is the number this project
     * publishes. Delivery latency on its own cannot tell "fired on time" from "picked up forty
     * minutes after the process came back".
     */
    public fun latenessAt(now: EpochSeconds): Long = (now - dueAt).coerceAtLeast(0)
}

public enum class TimerState {
    /** Waiting for its moment, or waiting to be retried after a failed delivery. */
    PENDING,

    /** Delivered at least once. Terminal. */
    FIRED,

    /** Cancelled before it fired. Terminal, and final: a cancelled timer never fires. */
    CANCELLED,

    /**
     * Delivery attempts are exhausted. Terminal, and unlike PENDING it is never selected again.
     *
     * Such a timer needs a person to look at it, but it must not burn CPU and log space for ever on
     * attempts that will keep failing.
     */
    DEAD_LETTERED,
}
