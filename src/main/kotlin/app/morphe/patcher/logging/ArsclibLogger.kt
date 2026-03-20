/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.logging

import com.reandroid.apk.APKLogger

internal class ArsclibLogger(
    private val logger: Logger = NoOpLogger,
) : APKLogger {
    override fun logMessage(msg: String) {
        // Category all ARSCLib messages as trace to keep patching logs more tidy.
        logger.trace(msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        logger.error(msg, tr)
    }

    override fun logVerbose(msg: String) {
        logger.trace(msg)
    }
}