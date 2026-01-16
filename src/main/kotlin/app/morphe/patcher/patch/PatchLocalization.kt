package app.morphe.patcher.patch

import java.util.Collections
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

object PatchLocalization {
    private const val STRING_RESOURCE_FILE = "patch-strings/strings"

    @Volatile
    private var currentLocale: Locale = Locale.getDefault()

    private val cache = Collections.synchronizedMap(
        HashMap<ClassLoader, ResourceBundle?>()
    )

    /**
     * Sets the locale for localized [Patch] and [Option] name/title/description/etc.
     */
    fun setLocale(locale: Locale) {
        currentLocale = locale
        cache.clear() // Ensure bundles reload with the new locale.
    }

    private fun getPatchStrings(clazz: Class<*>): ResourceBundle? {
        val loader = clazz.classLoader

        return cache.getOrPut(loader) {
            try {
                ResourceBundle.getBundle(STRING_RESOURCE_FILE, currentLocale, loader)
            } catch (_: MissingResourceException) {
                null
            }
        }
    }

    internal fun getLocalizedString(clazz: Class<*>, key: String): String? {
        val bundle = getPatchStrings(clazz) ?: return null

        return try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            throw IllegalArgumentException("key: $key") // FIXME
//            null
        }
    }
}
