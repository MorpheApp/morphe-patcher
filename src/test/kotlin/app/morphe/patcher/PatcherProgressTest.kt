/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PatcherProgressTest {
    private val reports = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
    private val listener = PatcherProgress { task, done, total ->
        assertEquals(PatcherTask.INDEXING_CLASSES, task)
        reports += done to total
    }

    @Test
    fun `reports the start, spaced steps and the end once`() {
        val total = 100_000L
        listener.runTask(PatcherTask.INDEXING_CLASSES, total) { task ->
            repeat(total.toInt()) { task.advance() }
        }

        assertEquals(0L to total, reports.first())
        assertEquals(total to total, reports.last())
        assertEquals(1, reports.count { it.first == total }, "The end is reported once")
        assertTrue(reports.size <= 202, "Reports are spaced out, got ${reports.size}")
        assertTrue(reports.zipWithNext().all { (a, b) -> a.first < b.first }, "Done only goes up")
    }

    @Test
    fun `a task without a count reports 0 of 1 and then 1 of 1`() {
        listener.runTask(PatcherTask.INDEXING_CLASSES) { }

        assertEquals(listOf(0L to 1L, 1L to 1L), reports)
    }

    @Test
    fun `counting goes on after the total grows`() {
        listener.runTask(PatcherTask.INDEXING_CLASSES, total = 0) { task ->
            task.expand(10)
            repeat(10) { task.advance() }
            task.expand(10)
            repeat(5) { task.advance() }
        }

        assertTrue(15L to 20L in reports, "Progress past the first total is reported, got $reports")
        assertEquals(20L to 20L, reports.last())
    }

    @Test
    fun `reports stay in order when several threads count`() {
        val total = 400_000L
        listener.runTask(PatcherTask.INDEXING_CLASSES, total) { task ->
            (1..8).map { thread { repeat((total / 8).toInt()) { task.advance() } } }.forEach { it.join() }
        }

        assertTrue(reports.zipWithNext().all { (a, b) -> a.first < b.first }, "Done only goes up: $reports")
        assertEquals(total to total, reports.last())
    }

    @Test
    fun `work that turns out to be none still ends done`() {
        listener.runTask(PatcherTask.INDEXING_CLASSES, total = 0) { }

        assertEquals(listOf(0L to 1L, 1L to 1L), reports)
    }

    @Test
    fun `nothing is counted without a listener`() {
        val result = (null as PatcherProgress?).runTask(PatcherTask.INDEXING_CLASSES, 10) { task ->
            task.advance(10)
            "done"
        }

        assertEquals("done", result)
    }
}
