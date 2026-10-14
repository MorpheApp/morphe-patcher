/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * The patch bundle a patch was loaded from, as the manifest of the bundle describes it.
 *
 * @param file The patch bundle.
 * @param name The name of the patch bundle.
 * @param version The version of the patch bundle.
 * @param patcherVersion The version of Morphe Patcher the patch bundle was built for,
 * or null if the bundle does not declare it.
 */
internal data class PatchBundleInfo(
    val file: File,
    val name: String?,
    val version: String?,
    val patcherVersion: String?,
) {
    override fun toString() = buildString {
        append(name ?: file.name)
        version?.let { append(' ').append(it) }
    }

    internal companion object {
        const val PATCHER_VERSION_ATTRIBUTE = "Patcher-Version"

        /**
         * Reads the manifest of a patch bundle. A bundle without a readable manifest declares nothing.
         */
        fun read(file: File): PatchBundleInfo {
            val attributes = runCatching { JarFile(file).use { it.manifest?.mainAttributes } }.getOrNull()

            fun Attributes?.value(name: String) = this?.getValue(name)?.trim()?.takeIf { it.isNotEmpty() }

            return PatchBundleInfo(
                file,
                attributes.value("Name"),
                attributes.value("Version"),
                attributes.value(PATCHER_VERSION_ATTRIBUTE),
            )
        }
    }
}
