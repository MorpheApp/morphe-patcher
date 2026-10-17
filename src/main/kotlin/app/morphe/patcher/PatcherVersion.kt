/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

/**
 * A version of Morphe Patcher, such as `2.0.0` or `2.0.0-dev.2`, ordered the way semantic versions
 * are: a pre-release comes before the release it leads up to.
 */
internal class PatcherVersion private constructor(
    private val text: String,
    private val core: List<Int>,
    private val preRelease: List<String>,
) : Comparable<PatcherVersion> {
    override fun compareTo(other: PatcherVersion): Int {
        for (index in core.indices) {
            val compared = core[index].compareTo(other.core[index])
            if (compared != 0) return compared
        }

        // A release is newer than any of its pre-releases.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.compareTo(preRelease.size)
        }

        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val compared = compareIdentifiers(preRelease[index], other.preRelease[index])
            if (compared != 0) return compared
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?) = other is PatcherVersion && compareTo(other) == 0

    override fun hashCode() = 31 * core.hashCode() + preRelease.hashCode()

    override fun toString() = text

    internal companion object {
        private val pattern = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

        /**
         * @return The version, or null if [text] is not a version.
         */
        fun parse(text: String?): PatcherVersion? {
            val trimmed = text?.trim() ?: return null
            val match = pattern.matchEntire(trimmed) ?: return null
            val (major, minor, patch, preRelease) = match.destructured

            return PatcherVersion(
                trimmed,
                listOf(major, minor, patch).map { it.toIntOrNull() ?: return null },
                if (preRelease.isEmpty()) emptyList() else preRelease.split('.'),
            )
        }

        /**
         * Numeric identifiers compare numerically and come before alphanumeric ones, which compare as text.
         */
        private fun compareIdentifiers(first: String, second: String): Int {
            val firstNumber = first.toLongOrNull()
            val secondNumber = second.toLongOrNull()

            return when {
                firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                firstNumber != null -> -1
                secondNumber != null -> 1
                else -> first.compareTo(second)
            }
        }
    }
}
