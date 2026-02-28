package app.morphe.patcher.environment


/**
 * Utils for the library.
 */
@Suppress("unused")
object EnvironmentUtils {
    /**
     * True if the environment is Android.
     */
    val isAndroidEnvironment =
        try {
            Class.forName("android.app.Application")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
}
