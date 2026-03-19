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
        // We call logger.trace() here too. That way without verbose the terminal shows 1 line only
        // WARNING: THIS PUTS ALL THE NORMAL MESSAGES FROM ARSCLIB INTO FINE TOO SINCE THIS IS THE ONLY PLACE THAT USES IT.
        // IF YOU WANT TO USE THIS IN THE FUTURE, BEWARE OF USING THIS BLINDLY.
        logger.trace(msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        logger.error(msg, tr)
    }

    override fun logVerbose(msg: String) {
        logger.trace(msg)
    }
}