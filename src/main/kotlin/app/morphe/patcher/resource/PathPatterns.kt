/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import java.util.regex.Pattern

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
    private val regexes = patterns.map { toRegex(normalize(it)) }

    /**
     * The segments of each pattern, each as the regex that matches it, or null if it holds `**`, which can
     * match any number of segments. A pattern that matches every path has no segments, which is null.
     */
    private val segments = patterns.map { pattern ->
        normalize(pattern).takeIf { it.isNotEmpty() }?.split('/')?.map { segment ->
            if ("**" in segment) null else toRegex(segment)
        }
    }

    fun matches(path: String): Boolean {
        val normalized = normalize(path)
        if (regexes.any { it.matches(normalized) }) return true

        // A pattern naming a directory matches everything below it.
        var separator = normalized.lastIndexOf('/')
        while (separator > 0) {
            val directory = normalized.substring(0, separator)
            if (regexes.any { it.matches(directory) }) return true
            separator = normalized.lastIndexOf('/', separator - 1)
        }
        return false
    }

    /**
     * Whether a path below [directory] can match, so that a search for matching paths can skip a directory
     * none can be in.
     */
    fun mayMatchBelow(directory: String): Boolean {
        val directorySegments = normalize(directory).let { if (it.isEmpty()) emptyList() else it.split('/') }

        return segments.any { patternSegments ->
            if (patternSegments == null) return@any true

            directorySegments.forEachIndexed { index, directorySegment ->
                // The pattern names a directory above, which matches everything below it.
                if (index == patternSegments.size) return@any true
                val regex = patternSegments[index] ?: return@any true
                if (!regex.matches(directorySegment)) return@any false
            }
            true
        }
    }

    private companion object {
        fun normalize(path: String) = path.replace('\\', '/').trim().trim('/')

        fun toRegex(pattern: String): Regex {
            if (pattern.isEmpty()) return Regex(".*", RegexOption.DOT_MATCHES_ALL)

            val regex = StringBuilder()
            var index = 0
            while (index < pattern.length) {
                when {
                    // Also matches no directory at all, so **/strings.xml matches strings.xml.
                    pattern.startsWith("**/", index) -> {
                        regex.append("(?:.*/)?")
                        index += 3
                    }

                    pattern.startsWith("**", index) -> {
                        regex.append(".*")
                        index += 2
                    }

                    pattern[index] == '*' -> {
                        regex.append("[^/]*")
                        index++
                    }

                    pattern[index] == '?' -> {
                        regex.append("[^/]")
                        index++
                    }

                    else -> {
                        regex.append(Pattern.quote(pattern[index].toString()))
                        index++
                    }
                }
            }

            return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL)
        }
    }
}
