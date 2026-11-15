/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.logging

import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class LoggerTest {
    private class RecordingLogger : Logger() {
        val messages = mutableListOf<Pair<LogLevel, String>>()

        override fun log(level: LogLevel, message: String) {
            messages += level to message
        }
    }

    @Test
    fun `error includes the stack trace of the throwable`() {
        val logger = RecordingLogger()

        logger.error("failed", IllegalStateException("cause message"))

        val (level, message) = logger.messages.single()
        assertEquals(LogLevel.ERROR, level)
        assertTrue(message.startsWith("failed\n"), message)
        assertTrue(message.contains("java.lang.IllegalStateException: cause message"), message)
        assertTrue(message.contains("at app.morphe.patcher.logging.LoggerTest"), message)
    }

    @Test
    fun `error without a throwable logs only the message`() {
        val logger = RecordingLogger()

        logger.error("failed")

        assertEquals(listOf(LogLevel.ERROR to "failed"), logger.messages)
    }

    @Test
    fun `handler passes on the throwable of a record`() {
        val logger = RecordingLogger()
        val record = LogRecord(Level.SEVERE, "failed").apply { thrown = IllegalStateException("cause message") }

        logger.handler.publish(record)

        val (level, message) = logger.messages.single()
        assertEquals(LogLevel.ERROR, level)
        assertTrue(message.contains("java.lang.IllegalStateException: cause message"), message)
    }

    @Test
    fun `java logger receives the throwable itself`() {
        val records = mutableListOf<LogRecord>()
        val javaLogger = java.util.logging.Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : java.util.logging.Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val throwable = IllegalStateException("cause message")

        javaLogger.toMorpheLogger().error("failed", throwable)

        val record = records.single()
        assertEquals(Level.SEVERE, record.level)
        assertEquals("failed", record.message)
        assertSame(throwable, record.thrown)
    }
}
