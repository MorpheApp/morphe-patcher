package app.morphe.patcher.environment


/**
 * Utils for the library.
 */
@Suppress("unused")
object EnvironmentUtils {
    /**
     * True if the environment is Android.
     */
    val isAndroidEnvironment = System.getProperty("java.runtime.name") == "Android Runtime"
}
