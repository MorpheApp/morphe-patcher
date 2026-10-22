/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.util.proxy.mutableTypes

import java.util.concurrent.atomic.AtomicLong

/**
 * Increasing stamps of changes to the methods of mutable classes and to their instructions,
 * so the instruction indexes can tell which methods changed since they were indexed.
 */
internal object ChangeStamps {
    private val lastStamp = AtomicLong()

    /**
     * The stamp of the last change, or 0 if nothing changed.
     */
    val current: Long
        get() = lastStamp.get()

    /**
     * A stamp for a new change, greater than all stamps before it.
     */
    fun next(): Long = lastStamp.incrementAndGet()
}
