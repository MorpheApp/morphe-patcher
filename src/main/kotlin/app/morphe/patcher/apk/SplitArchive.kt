/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * A split archive, such as `.apkm`, `.apks` or `.xapk`, which holds the APK modules of an app: its
 * base module and the splits that complete it.
 */
object SplitArchive {
    /** The extensions of the split archive formats. */
    @JvmField
    val EXTENSIONS = setOf("apks", "apkm", "xapk")

    private const val MANIFEST = "AndroidManifest.xml"

    /** Whether [file] is a split archive: one of [EXTENSIONS], or another archive holding modules. */
    @JvmStatic
    fun isSplitArchive(file: File): Boolean =
        // Only an archive without a known extension is opened to look for modules
        file.isFile && (file.extension.lowercase(Locale.ROOT) in EXTENSIONS || hasModules(file))

    /**
     * The APK modules of [zip]. Modules sit at the root of the archive, so an APK nested deeper,
     * such as one kept under `res/raw/`, is a file of a module rather than one of its own.
     */
    @JvmStatic
    fun modules(zip: ZipFile): Sequence<ZipEntry> =
        zip.entries().asSequence().filter { entry ->
            !entry.isDirectory && !entry.name.contains('/') && entry.name.endsWith(".apk", ignoreCase = true)
        }

    /**
     * Whether the manifest of the module in [entry] declares no split. The module is streamed rather
     * than extracted, which stops early as build tools write the manifest first.
     */
    @JvmStatic
    fun isBaseModule(zip: ZipFile, entry: ZipEntry): Boolean =
        runCatching {
            ZipInputStream(zip.getInputStream(entry)).use { module ->
                generateSequence { module.nextEntry }
                    .firstOrNull { it.name == MANIFEST }
                    ?.let { !AndroidManifestBlock.load(module).isSplit }
            }
        }.getOrNull() == true

    /**
     * The module of [zip] that describes the app, or null if it holds no modules.
     *
     * That is the base module, found by its conventional name or else by its manifest. An archive
     * whose manifests name no base is answered by the module most likely to be one.
     */
    @JvmStatic
    fun baseModule(zip: ZipFile): ZipEntry? {
        val entries = modules(zip).toList()
        if (entries.isEmpty()) return null

        val lowered = entries.associateWith { it.name.lowercase(Locale.ROOT) }
        val baseEntry = lowered.entries.firstOrNull { (_, name) ->
            name.endsWith("base.apk") || "base-master" in name || "base-main" in name
        }?.key
        if (baseEntry != null) return baseEntry

        // Past the conventional names the base is told apart by its manifest. An .xapk names it
        // after the package, and an asset pack beside it can outweigh it, so neither a name nor a
        // size can be trusted there
        val manifestBase = entries.firstOrNull { isBaseModule(zip, it) }
        if (manifestBase != null) return manifestBase

        val primaryEntry = lowered.entries.firstOrNull { (_, name) ->
            "main" in name || "master" in name
        }?.key
        if (primaryEntry != null) return primaryEntry

        val largestNonConfig = lowered.entries
            .filter { (_, name) -> !name.startsWith("config") && !name.contains("split_config") && !name.contains("config.") }
            .map { it.key }
            .filter { it.size >= 0 }
            .maxByOrNull { it.size }
        if (largestNonConfig != null) return largestNonConfig

        return entries.minWithOrNull(
            compareBy<ZipEntry> { entry ->
                val lower = entry.name.lowercase(Locale.ROOT)
                when {
                    "base" in lower -> 0
                    "main" in lower || "master" in lower -> 1
                    lower.startsWith("config") -> 99
                    else -> 2
                }
            }.thenBy { it.name.length }
        )
    }

    private fun hasModules(file: File): Boolean =
        runCatching { ZipFile(file).use { zip -> modules(zip).any() } }.getOrDefault(false)
}
