/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.logging

import java.util.logging.Logger
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Whether [PhaseTimings] writes its lines to the log. Only for development, so it is off for releases.
 */
internal const val LOG_PHASE_TIMINGS = false

/**
 * Collects how long the steps of a phase take and logs them as one line, so that a run shows where
 * the phase spends its time without a profiler.
 *
 * Steps are listed in the order they are first measured, and a step measured more than once adds up.
 * Counters are listed among them the same way.
 *
 * @param logger The logger the line is written to.
 * @param name The name of the phase, which starts the line.
 */
internal class PhaseTimings(private val logger: Logger, private val name: String) {
    /** Nanoseconds of a step, or the value of a counter. */
    private class Value(val isCounter: Boolean) {
        var amount = 0L
    }

    private val values = LinkedHashMap<String, Value>()

    /**
     * Runs [block] and adds the time it takes to [label], also when it throws.
     */
    inline fun <T> measure(label: String, block: () -> T): T {
        if (!LOG_PHASE_TIMINGS) return block()

        // Listed when it starts, so that a step comes before the steps measured within it.
        addTime(label, 0)
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            addTime(label, System.nanoTime() - start)
        }
    }

    @PublishedApi
    @Synchronized
    internal fun addTime(label: String, nanos: Long) {
        values.getOrPut(label) { Value(false) }.amount += nanos
    }

    /**
     * Adds [amount] to the counter [label].
     */
    @Synchronized
    fun count(label: String, amount: Long = 1) {
        if (!LOG_PHASE_TIMINGS) return

        values.getOrPut(label) { Value(true) }.amount += amount
    }

    /**
     * Writes the steps and counters as one line, such as `Timings compile: encode=12ms write=1.34s`.
     * Does nothing unless [LOG_PHASE_TIMINGS] is set.
     */
    @Synchronized
    fun log() {
        if (!LOG_PHASE_TIMINGS) return

        logger.info(
            values.entries.joinToString(" ", prefix = "Timings $name: ") { (label, value) ->
                if (value.isCounter) "$label=${value.amount}" else "$label=${value.amount.nanoseconds.toLogString()}"
            },
        )
    }
}

/**
 * Runs [block] and adds the time it takes to [label] of these timings, or only runs it if there are none.
 */
internal inline fun <T> PhaseTimings?.measure(label: String, block: () -> T): T =
    if (this == null) block() else measure(label, block)
