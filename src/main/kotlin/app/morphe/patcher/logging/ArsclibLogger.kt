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
        logger.info(msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        logger.error(msg, tr)
    }

    override fun logVerbose(msg: String) {
        logger.trace(msg)
    }
}