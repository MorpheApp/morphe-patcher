/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.environment

@Suppress("unused")
object EnvironmentUtils {
    /**
     * True if the environment is Android.
     */
    val isAndroidEnvironment = System.getProperty("java.runtime.name") == "Android Runtime"
}
