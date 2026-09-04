package ru.workinprogress.chronik

/**
 * A point in time, in whole seconds since the Unix epoch.
 *
 * THE UNIT IS IN THE TYPE ON PURPOSE. chronik promises second precision and nothing finer, and a
 * promise that lives only in prose is not kept — the caller passes whatever number they happen to
 * hold, and a millisecond value read as seconds is a due date in the year 56000 that nobody
 * notices until the timer does not fire. `Long` cannot refuse that; this can.
 *
 * Why seconds and not milliseconds: millisecond precision needs a different wake-up mechanism than
 * polling, and a promise polling cannot keep is worse than no promise at all.
 */
@JvmInline
public value class EpochSeconds(
    public val value: Long,
) : Comparable<EpochSeconds> {
    override fun compareTo(other: EpochSeconds): Int = value.compareTo(other.value)

    public operator fun plus(seconds: Long): EpochSeconds = EpochSeconds(value + seconds)

    public operator fun minus(other: EpochSeconds): Long = value - other.value

    override fun toString(): String = "${value}s"
}

/**
 * The wall clock, as a parameter rather than a call to the platform.
 *
 * Two reasons, and the second is the load-bearing one. `commonMain` cannot see `java.*` at all. And
 * a test must be able to MOVE time rather than sleep through it: firing is checked by invoking a
 * pass and advancing the clock, not by sleeping on the real one and hoping the worker woke the
 * required number of times. On a loaded machine that hope does not come true.
 */
public fun interface ChronikClock {
    public fun now(): EpochSeconds
}
