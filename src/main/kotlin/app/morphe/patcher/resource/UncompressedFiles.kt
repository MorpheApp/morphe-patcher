package app.morphe.patcher.resource

import com.reandroid.json.JSONObject

/**
 * Represents the set of files that should not be compressed when building the APK.
 *
 * Parsed from `uncompressed-files.json` which ARSCLib writes during decoding.
 * The JSON contains:
 * - `extensions`: file extensions (e.g. `.png`, `.webp`) that should not be compressed.
 * - `paths`: specific file paths (e.g. `lib/x86_64/libfoo.so`) that should not be compressed.
 *
 * Implements [Set]<[String]> so it can be used directly with the `in` operator
 * for membership checks. A path is considered a member if it matches an exact
 * entry in [paths] **or** if its extension matches one in [extensions].
 */
internal class UncompressedFiles(
    uncompressedFilesJson: String
) : AbstractSet<String>() {

    private val extensions: Set<String>
    private val paths: Set<String>

    init {
        val json = JSONObject(uncompressedFilesJson)

        val extensionSet = mutableSetOf<String>()
        json.optJSONArray("extensions")?.let { array ->
            for (i in 0 until array.length()) {
                val ext = array.getString(i)
                // Normalize: always store with leading dot.
                extensionSet.add(if (ext.startsWith(".")) ext else ".$ext")
            }
        }
        extensions = extensionSet

        val pathSet = mutableSetOf<String>()
        json.optJSONArray("paths")?.let { array ->
            for (i in 0 until array.length()) {
                pathSet.add(sanitizePath(array.getString(i)))
            }
        }
        paths = pathSet
    }

    /**
     * The total number of explicit path entries. Note that extension-based
     * matches are not counted here, as they represent rules rather than
     * concrete entries.
     */
    override val size: Int get() = paths.size

    /**
     * Returns `true` if [element] is an exact path in the uncompressed set,
     * **or** if its file extension matches one of the uncompressed extensions.
     */
    override fun contains(element: String): Boolean {
        val normalized = sanitizePath(element)
        if (paths.contains(normalized)) return true
        return matchesExtension(normalized)
    }

    override fun iterator(): Iterator<String> = paths.iterator()

    private fun matchesExtension(path: String): Boolean {
        if (extensions.isEmpty()) return false

        // Extract extension from the filename portion (after the last `/`).
        val slashIndex = path.lastIndexOf('/')
        val filename = if (slashIndex >= 0) path.substring(slashIndex + 1) else path
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex <= 0) return false

        val ext = filename.substring(dotIndex)
        return extensions.contains(ext)
    }

    companion object {
        /**
         * Normalize a path to use forward slashes and strip any leading slashes.
         */
        private fun sanitizePath(path: String): String {
            var sanitized = path.replace('\\', '/').trim()
            while (sanitized.startsWith("/")) {
                sanitized = sanitized.substring(1)
            }
            return sanitized
        }
    }
}