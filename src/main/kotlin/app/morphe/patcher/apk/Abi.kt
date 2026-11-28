/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.patch.ApkArchitecture
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The ABIs native code is built for, named as Android names them: the directory under `lib/`, the
 * config split that ships it, and the entries of `android.os.Build.SUPPORTED_ABIS`.
 *
 * One vocabulary for everything that has to recognize an ABI: reading it out of an APK, matching it
 * in the name of a split module, asking what a device runs, and telling patches which architecture
 * a run produces.
 */
object Abi {
    /**
     * Every ABI, ordered by the preference a run gives them when the device itself runs none. A
     * name always precedes the ones it contains, so armeabi-v7a and x86_64 are never claimed by
     * armeabi and x86.
     */
    @JvmField
    val NAMES = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "riscv64")

    // Longest name first, so a name is matched before the shorter one it contains
    private val LONGEST_FIRST = NAMES.sortedByDescending { it.length }

    /**
     * The architecture patches declare their availability against for [name], or null for an ABI
     * that has none. Legacy armeabi counts as the 32-bit ARM target, riscv64 as no target at all.
     */
    @JvmStatic
    fun architectureOf(name: String): ApkArchitecture? = when (name.lowercase(Locale.ROOT)) {
        "arm64-v8a" -> ApkArchitecture.ARM64_V8A
        "armeabi-v7a", "armeabi" -> ApkArchitecture.ARMEABI_V7A
        "x86_64" -> ApkArchitecture.X86_64
        "x86" -> ApkArchitecture.X86
        else -> null
    }

    /** The ABI [text] names, or null if it names none. The longest match wins. */
    @JvmStatic
    fun namedIn(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return LONGEST_FIRST.firstOrNull { abi -> tokensOf(abi).any { it in lower } }
    }

    /** The spellings [name] takes in the name of a split module, which differ in their separator. */
    @JvmStatic
    fun tokensOf(name: String): Set<String> {
        val normalized = name.lowercase(Locale.ROOT)
        return setOf(normalized, normalized.replace('-', '_'), normalized.replace('_', '-'))
    }

    /** The ABI of the native library at [path] inside an APK, or null for any other entry. */
    @JvmStatic
    fun ofLibraryPath(path: String): String? {
        if (!path.startsWith("lib/")) return null
        val secondSlash = path.indexOf('/', startIndex = 4)
        if (secondSlash == -1) return null
        return path.substring(4, secondSlash)
    }

    /**
     * The ABIs [file], an APK or a [SplitArchive], ships native libraries for, named as they appear
     * under `lib/` once its modules are merged. Directories no device runs are reported as named.
     * Empty for an app without native code or a file that cannot be read.
     *
     * A split archive is answered from the names of its modules where it has ABI splits, which
     * keeps it a matter of reading the index of the archive rather than unpacking every split. A
     * bundle built for a single ABI ships no such split, so there the modules themselves are read.
     */
    @JvmStatic
    fun of(file: File): List<String> =
        runCatching {
            ZipFile(file).use { zip ->
                val modules = SplitArchive.modules(zip).toList()
                if (modules.isEmpty() || zip.getEntry("AndroidManifest.xml") != null) {
                    return@use zip.entries().asSequence().mapNotNull { ofLibraryPath(it.name) }.distinct().toList()
                }

                modules.mapNotNull { namedIn(it.name) }.distinct().ifEmpty {
                    modules.flatMap { module -> zip.getInputStream(module).use(::of) }.distinct()
                }
            }
        }.getOrDefault(emptyList())

    /**
     * [of] for an APK that arrives on [stream], which is how a module of a split archive is reached
     * when it has no file of its own to open.
     *
     * Streaming has no central directory to consult, so the walk ends with the run of `lib/` entries
     * every build tool writes them as, rather than inflating the whole APK behind it. An APK that
     * turns out unreadable is answered by whatever it yielded up to that point.
     */
    @JvmStatic
    fun of(stream: InputStream): List<String> {
        val abis = LinkedHashSet<String>()
        var insideLibs = false

        runCatching {
            ZipInputStream(stream.buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    val abi = ofLibraryPath(entry.name)
                    if (abi == null) {
                        if (insideLibs) break
                    } else {
                        insideLibs = true
                        abis += abi
                    }
                }
            }
        }

        return abis.toList()
    }
}
