/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.util

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool

/**
 * Transforms every element on the common pool, each in a task of its own, and returns the results
 * in the order of the elements.
 *
 * A parallel stream splits a list into runs of neighbouring elements, so that the largest elements,
 * when they lie next to each other, end up in a single task. A task each spreads them out, and the
 * pool starts them in the order given, so put the largest first.
 *
 * @throws Throwable What the first failing transform threw, once every task is done.
 */
internal fun <T, R> List<T>.mapInParallel(transform: (T) -> R): List<R> {
    val futures = ForkJoinPool.commonPool().invokeAll(map { element -> Callable { transform(element) } })
    return futures.map { future ->
        try {
            future.get()
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }
}

/**
 * Runs [action] for every element on the common pool, as [mapInParallel] does.
 */
internal fun <T> List<T>.forEachInParallel(action: (T) -> Unit) {
    mapInParallel(action)
}
