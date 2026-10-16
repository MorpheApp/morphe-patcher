/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import app.morphe.patcher.PatcherBehavior
import app.morphe.patcher.PatcherVersion
import java.io.File
import java.io.IOException
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The patch bundle a patch was loaded from, as the manifest of the bundle describes it.
 *
 * @param file The patch bundle.
 * @param name The name of the patch bundle.
 * @param version The version of the patch bundle.
 * @param patcherVersion The version of Morphe Patcher the patch bundle was built for,
 * or null if the bundle does not declare it.
 * @param retryingFiltersCanChangeMatches If the fingerprints of the patch bundle can match differently
 * when the filters before an instruction filter that does not match are retried,
 * see [PatcherBehavior.RETRY_ALL_INSTRUCTION_FILTERS].
 */
internal data class PatchBundleInfo(
    val file: File,
    val name: String?,
    val version: String?,
    val patcherVersion: String?,
    val retryingFiltersCanChangeMatches: Boolean = true,
) {
    override fun toString() = buildString {
        append(name ?: file.name)
        version?.let { append(' ').append(it) }
    }

    internal companion object {
        const val PATCHER_VERSION_ATTRIBUTE = "Patcher-Version"

        private val logger = Logger.getLogger(PatchBundleInfo::class.java.name)

        /**
         * Names that the bytecode of a patch bundle contains only if retrying the filters before an
         * instruction filter that does not match can change what its fingerprints match. Fingerprints
         * that only use the locations MatchAfterAnywhere, MatchFirst and MatchAfterAtLeast match the
         * same instructions either way.
         *
         * Class files and DEX files both store referenced class and method names as plain text.
         */
        private val RETRY_DEPENDENT_NAMES = listOf(
            // Locations that match a limited number of instructions, the farthest instruction,
            // or before the previous match.
            "app/morphe/patcher/InstructionLocation\$MatchAfterImmediately",
            "app/morphe/patcher/InstructionLocation\$MatchAfterWithin",
            "app/morphe/patcher/InstructionLocation\$MatchAfterLast",
            "app/morphe/patcher/InstructionLocation\$MatchAfterRange",
            "app/morphe/patcher/InstructionLocation\$MatchBefore",
            // Creates MatchAfterImmediately filters inside Morphe Patcher.
            "opcodesToFilters",
            // Implemented by custom locations.
            "indexIsValidForMatching",
        )

        /**
         * Reads the manifest of a patch bundle. A bundle without a readable manifest declares nothing.
         */
        fun read(file: File): PatchBundleInfo {
            val attributes = runCatching { JarFile(file).use { it.manifest?.mainAttributes } }.getOrNull()

            fun Attributes?.value(name: String) = this?.getValue(name)?.trim()?.takeIf { it.isNotEmpty() }

            val patcherVersion = attributes.value(PATCHER_VERSION_ATTRIBUTE)
            // Only bundles built for an older version keep matching as before, so only their bytecode is checked.
            val builtForRetryingAllFilters = PatcherBehavior.RETRY_ALL_INSTRUCTION_FILTERS.appliesTo(
                PatcherVersion.parse(patcherVersion)
            )

            return PatchBundleInfo(
                file,
                attributes.value("Name"),
                attributes.value("Version"),
                patcherVersion,
                builtForRetryingAllFilters || retryingFiltersCanChangeMatches(file),
            )
        }

        /**
         * If any class or DEX file of [file] references a name of [RETRY_DEPENDENT_NAMES].
         * If the bundle cannot be read, then its fingerprints are assumed to match differently.
         */
        private fun retryingFiltersCanChangeMatches(file: File) = try {
            JarFile(file).use { jar ->
                jar.entries().asSequence().filter { entry ->
                    entry.name.endsWith(".class") || entry.name.endsWith(".dex")
                }.any { entry ->
                    // Each byte is one character, so names stored as ASCII are found as they are.
                    val bytecode = jar.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    RETRY_DEPENDENT_NAMES.any { it in bytecode }
                }
            }
        } catch (exception: IOException) {
            logger.log(Level.WARNING, "Failed to read the bytecode of the patch bundle $file", exception)
            true
        }
    }
}
