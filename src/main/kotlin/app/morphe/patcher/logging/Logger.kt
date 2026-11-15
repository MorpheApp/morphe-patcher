/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.logging

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

abstract class Logger {
    abstract fun log(level: LogLevel, message: String)

    /**
     * Log [message] together with [throwable].
     *
     * By default the stack trace of [throwable] is appended to [message] and passed to the
     * two argument [log]. Override this to handle the throwable some other way.
     */
    open fun log(level: LogLevel, message: String, throwable: Throwable?) {
        if (throwable == null) {
            log(level, message)
        } else {
            log(level, message + "\n" + throwable.stackTraceToString())
        }
    }

    fun trace(msg: String) = log(LogLevel.TRACE, msg)
    fun info(msg: String) = log(LogLevel.INFO, msg)
    fun warn(msg: String) = log(LogLevel.WARN, msg)
    fun error(msg: String, tr: Throwable? = null) = log(LogLevel.ERROR, msg, tr)

    val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val level = when (record.level) {
                Level.INFO -> LogLevel.INFO
                Level.SEVERE -> LogLevel.ERROR
                Level.WARNING -> LogLevel.WARN
                else -> LogLevel.TRACE
            }
            log(level, record.message, record.thrown)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }
}

enum class LogLevel {
    TRACE,
    INFO,
    WARN,
    ERROR,
}

object NoOpLogger : Logger() {
    override fun log(level: LogLevel, message: String) {
        // Does nothing
    }

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        // Does nothing, and skips building the stack trace.
    }
}

fun java.util.logging.Logger.toMorpheLogger(): Logger {
    val javaLogger = this
    return object : Logger() {
        override fun log(level: LogLevel, message: String) {
            javaLogger.log(level.toJavaLevel(), message)
        }

        override fun log(level: LogLevel, message: String, throwable: Throwable?) {
            javaLogger.log(level.toJavaLevel(), message, throwable)
        }
    }
}

private fun LogLevel.toJavaLevel(): Level = when (this) {
    LogLevel.TRACE -> Level.FINE
    LogLevel.INFO -> Level.INFO
    LogLevel.WARN -> Level.WARNING
    LogLevel.ERROR -> Level.SEVERE
}
