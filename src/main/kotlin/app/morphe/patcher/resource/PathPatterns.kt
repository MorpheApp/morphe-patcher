/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

/**
 * Glob patterns that match paths separated by `/`.
 *
 * `*` matches any characters within one segment of a path, `**` matches any characters across
 * segments, and `?` matches one character within a segment. A pattern that matches a directory
 * matches every path below it, so `res/xml` matches `res/xml/main.xml`, `res/values*` matches
 * every values file, and `**` matches every path. An empty pattern names the root directory and so
 * matches every path as well.
 */
internal class PathPatterns(patterns: Collection<String>) {
    private val globs = patterns.map { Glob(normalize(it)) }

    /**
     * The segments of each pattern, each as the glob that matches it, or null if it holds `**`, which can
     * match any number of segments. A pattern that matches every path has no segments, which is null.
     */
    private val segments = patterns.map { pattern ->
        normalize(pattern).takeIf { it.isNotEmpty() }?.split('/')?.map { segment ->
            if ("**" in segment) null else Glob(segment)
        }
    }

    fun matches(path: String): Boolean {
        val normalized = normalize(path)
        if (globs.any { it.matches(normalized) }) return true

        // A pattern naming a directory matches everything below it.
        var separator = normalized.lastIndexOf('/')
        while (separator > 0) {
            val directory = normalized.substring(0, separator)
            if (globs.any { it.matches(directory) }) return true
            separator = normalized.lastIndexOf('/', separator - 1)
        }
        return false
    }

    /**
     * Whether a path below [directory] can match, so that a search for matching paths can skip a directory
     * none can be in.
     */
    fun mayMatchBelow(directory: String): Boolean {
        val directorySegments = segmentsOf(directory)
        return segments.any { mayMatchBelow(it, directorySegments) }
    }

    /**
     * A test of the paths directly in [directory], equal to [matches] but faster for many paths, since the
     * directory and the patterns that can match in it are settled once.
     */
    fun matcherIn(directory: String): (String) -> Boolean {
        // A pattern naming the directory or one above it matches everything in it.
        if (matches(directory)) return { true }

        val directorySegments = segmentsOf(directory)

        // A pattern one segment below the directory and without `**` already matched the directory, so only
        // its last segment is left, which is matched against the file name. Patches decode a few files by
        // name at a time out of directories of thousands, such as res/drawable*/icon.*, so a name without
        // wildcards is looked up instead.
        val names = HashSet<String>()
        val nameGlobs = ArrayList<Glob>()
        val pathGlobs = ArrayList<Glob>()
        globs.forEachIndexed { index, glob ->
            val patternSegments = segments[index]
            if (!mayMatchBelow(patternSegments, directorySegments)) return@forEachIndexed
            if (patternSegments == null || patternSegments.size != directorySegments.size + 1 ||
                patternSegments.any { it == null }
            ) {
                pathGlobs += glob
                return@forEachIndexed
            }
            val nameGlob = patternSegments.last()!!
            val literal = nameGlob.literal
            if (literal != null) names += literal else nameGlobs += nameGlob
        }
        if (names.isEmpty() && nameGlobs.isEmpty() && pathGlobs.isEmpty()) return { false }

        return { path ->
            val name = path.substring(path.lastIndexOf('/') + 1)
            name in names || nameGlobs.any { it.matches(name) } ||
                    (pathGlobs.isNotEmpty() && normalize(path).let { normalized -> pathGlobs.any { it.matches(normalized) } })
        }
    }

    private fun mayMatchBelow(patternSegments: List<Glob?>?, directorySegments: List<String>): Boolean {
        if (patternSegments == null) return true

        directorySegments.forEachIndexed { index, directorySegment ->
            // The pattern names a directory above, which matches everything below it.
            if (index == patternSegments.size) return true
            val glob = patternSegments[index] ?: return true
            if (!glob.matches(directorySegment)) return false
        }
        return true
    }

    private companion object {
        fun normalize(path: String) = path.replace('\\', '/').trim().trim('/')

        fun segmentsOf(path: String) = normalize(path).let { if (it.isEmpty()) emptyList() else it.split('/') }
    }
}

/**
 * One glob pattern of [PathPatterns], matched without a regex, as a regex is slow on Android where it
 * calls into native code for every match.
 */
private class Glob(private val pattern: String) {
    // A pattern without wildcards matches only itself.
    private val isLiteral = pattern.none { it == '*' || it == '?' }

    /** The pattern if it has no wildcards and so matches only itself, or null. */
    val literal: String? get() = pattern.takeIf { isLiteral }

    fun matches(path: String) = when {
        pattern.isEmpty() -> true
        isLiteral -> pattern == path
        else -> matches(0, path, 0)
    }

    private fun matches(patternStart: Int, path: String, pathStart: Int): Boolean {
        var patternIndex = patternStart
        var pathIndex = pathStart
        while (patternIndex < pattern.length) {
            when {
                // Any directories, or none at all, so **/strings.xml matches strings.xml.
                pattern.startsWith("**/", patternIndex) -> {
                    if (matches(patternIndex + 3, path, pathIndex)) return true
                    for (index in pathIndex until path.length) {
                        if (path[index] == '/' && matches(patternIndex + 3, path, index + 1)) return true
                    }
                    return false
                }

                pattern.startsWith("**", patternIndex) -> {
                    for (index in pathIndex..path.length) {
                        if (matches(patternIndex + 2, path, index)) return true
                    }
                    return false
                }

                // Any characters within one segment.
                pattern[patternIndex] == '*' -> {
                    var index = pathIndex
                    while (true) {
                        if (matches(patternIndex + 1, path, index)) return true
                        if (index == path.length || path[index] == '/') return false
                        index++
                    }
                }

                pattern[patternIndex] == '?' -> {
                    if (pathIndex == path.length || path[pathIndex] == '/') return false
                    patternIndex++
                    pathIndex++
                }

                else -> {
                    if (pathIndex == path.length || path[pathIndex] != pattern[patternIndex]) return false
                    patternIndex++
                    pathIndex++
                }
            }
        }
        return pathIndex == path.length
    }
}
