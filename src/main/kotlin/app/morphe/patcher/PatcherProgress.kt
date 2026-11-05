/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import java.util.concurrent.atomic.AtomicLong

/**
 * Work of the patcher that takes long on a large app, apart from executing patches, which
 * [Patcher.invoke] reports one by one.
 *
 * Decoding happens when patches first need what is decoded, so the decoding tasks and
 * [INDEXING_CLASSES] are reported while patches execute, and only if a patch needs them.
 */
enum class PatcherTask {
    /** Extracting and reading the DEX files. Reported without a count. */
    DECODING_DEX,

    /** Getting ready to extract the files patches use as they are, without decoding any. Reported without a count. */
    EXTRACTING_RESOURCES,

    /**
     * Reading the resource table and decoding what patches read first. Counted in the bytes of the
     * resource table and of the framework table it links, and without a count when no table is read.
     */
    DECODING_RESOURCES,

    /** Indexing the instructions of every class for fingerprints to match against. Counted in classes. */
    INDEXING_CLASSES,

    /** Writing the patched classes to DEX files. Counted in classes. */
    COMPILING_DEX,

    /** Compiling the resources patches changed. Counted in the files each of its stages goes through. */
    COMPILING_RESOURCES,

    /** Writing the entries of the patched APK. Counted in entries. */
    WRITING_APK,
}

/**
 * Receives how far the patcher got with a [PatcherTask].
 *
 * Every task starts with `done` at 0 and ends with `done` at `total`, so a task without a count
 * reports 0 of 1 and then 1 of 1. A task can run more than once, and can raise its `total` while it
 * runs, but `done` never goes down within one run of it. Reports are spaced out, so a caller can
 * update a screen on each of them.
 *
 * Called on whatever thread does the work, including several at once, so it must be thread-safe
 * and return quickly.
 */
fun interface PatcherProgress {
    fun onProgress(task: PatcherTask, done: Long, total: Long)
}

/**
 * Counts the progress of one run of [task] and reports it to [listener], at most every
 * [REPORT_STEPS]th of the total apart, as well as when it starts and when it is done.
 *
 * @param total What the task counts up to, or 0 for work that learns it as it goes with [expand].
 */
internal class TaskProgress(
    private val listener: PatcherProgress?,
    private val task: PatcherTask,
    total: Long,
) {
    private val total = AtomicLong(total)
    private val done = AtomicLong()

    /** The step last reported, guarded by this, so reports from several threads stay in order. */
    @Volatile
    private var reportedStep = 0L

    init {
        listener?.onProgress(task, 0, total.coerceAtLeast(1))
    }

    /**
     * Adds [amount] to what is done, and reports it if it reached the next step.
     */
    fun advance(amount: Long = 1) {
        if (listener != null) report(done.addAndGet(amount))
    }

    /**
     * Counts [done] as done, for work that knows how far it got rather than how much it did.
     */
    fun advanceTo(done: Long) {
        if (listener != null) report(this.done.updateAndGet { maxOf(it, done) })
    }

    /**
     * Adds [amount] to the total, for work that turns out to be more than expected when it started.
     */
    fun expand(amount: Long) {
        val total = total.addAndGet(amount)
        // What is done is a smaller share of the larger total, which counting has to reach again
        synchronized(this) {
            if (reportedStep != FINISHED) reportedStep = done.get() * REPORT_STEPS / total.coerceAtLeast(1)
        }
    }

    /**
     * Reports the task as done, whatever was counted. Only the first call reports it.
     */
    fun finish() = synchronized(this) {
        // Counting up to the total reported it done already
        val reported = reportedStep
        reportedStep = FINISHED
        if (reported >= REPORT_STEPS) return@synchronized

        val total = total.get().coerceAtLeast(1)
        done.set(total)
        listener?.onProgress(task, total, total)
    }

    private fun report(counted: Long) {
        val total = total.get()
        val done = counted.coerceAtMost(total)
        val step = done * REPORT_STEPS / total.coerceAtLeast(1)
        if (step <= reportedStep) return

        synchronized(this) {
            if (step <= reportedStep) return
            reportedStep = step
            listener?.onProgress(task, done, total)
        }
    }

    private companion object {
        /** How many reports a task is spaced out to, which is smooth enough for a progress bar. */
        const val REPORT_STEPS = 200L

        /** Past every step, so nothing is reported once the task is done. */
        const val FINISHED = REPORT_STEPS + 1
    }
}

/**
 * Counts a run of [task] made of the stages of [S] in their order, each an equal part of the task
 * that fills as the stage gets through whatever it counts.
 */
internal class StagedProgress<S : Enum<S>>(listener: PatcherProgress?, task: PatcherTask, stageCount: Int) {
    private val progress = TaskProgress(listener, task, stageCount * STAGE_UNITS)

    /** Reports [done] of [total] of what [stage] counts. */
    fun report(stage: S, done: Long, total: Long) =
        progress.advanceTo(stage.ordinal * STAGE_UNITS + done * STAGE_UNITS / total.coerceAtLeast(1))

    fun finished(stage: S) = report(stage, 1, 1)

    /** Reports the task as done, see [TaskProgress.finish]. */
    fun finish() = progress.finish()

    companion object {
        /** Fine enough for a stage of thousands of files to move on smoothly. */
        private const val STAGE_UNITS = 1000L

        inline fun <reified S : Enum<S>> of(listener: PatcherProgress?, task: PatcherTask) =
            StagedProgress<S>(listener, task, enumValues<S>().size)
    }
}

/**
 * Runs [block] as a run of [task] that counts up to [total], and reports it done when [block] returns.
 * A task without a count leaves [total] at 1 and counts nothing.
 */
internal inline fun <T> PatcherProgress?.runTask(
    task: PatcherTask,
    total: Long = 1,
    block: (TaskProgress) -> T,
): T {
    val progress = TaskProgress(this, task, total)
    return block(progress).also { progress.finish() }
}
