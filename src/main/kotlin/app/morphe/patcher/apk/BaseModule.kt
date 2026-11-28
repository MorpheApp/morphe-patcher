/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The base module of an APK or a split archive such as `.apkm`, `.apks` or `.xapk`, read in place
 * without unpacking the app.
 */
internal class BaseModule private constructor(
    val manifest: AndroidManifestBlock,
    private val readResources: () -> TableBlock?,
) {
    /** The resource table of the module, read only on request as it is by far its largest part. */
    fun resources(): TableBlock? = readResources()

    companion object {
        private const val MANIFEST = "AndroidManifest.xml"

        /**
         * Runs [block] on the base module of [file], which stays open for as long as [block] runs.
         *
         * @throws FileNotFoundException If [file] does not exist or is not a file.
         * @throws IOException If [file] cannot be read, or holds no manifest of a base module.
         */
        @Throws(IOException::class)
        fun <T> read(file: File, block: (BaseModule) -> T): T {
            if (!file.isFile) throw FileNotFoundException("APK not found: $file")

            return ZipFile(file).use { zip ->
                val module = plain(zip) ?: nested(zip) ?: throw IOException("No base APK manifest found in: $file")
                block(module)
            }
        }

        // A split module on its own is part of an app rather than one, whatever package it names
        private fun plain(zip: ZipFile): BaseModule? {
            val manifest = zip.getEntry(MANIFEST)
                ?.let { entry -> zip.getInputStream(entry).use { AndroidManifestBlock.load(it) } }
                ?.takeUnless { it.isSplit }
                ?: return null

            return BaseModule(manifest) {
                zip.getEntry(TableBlock.FILE_NAME)?.let { entry -> zip.getInputStream(entry).use { TableBlock.load(it) } }
            }
        }

        // An archive that holds no base answers with the module most likely to be one, which is
        // still a split and describes no app of its own
        private fun nested(zip: ZipFile): BaseModule? {
            val module = SplitArchive.baseModule(zip) ?: return null
            val manifest = zip.getInputStream(module)
                .use { nestedEntry(it, MANIFEST) { input -> AndroidManifestBlock.load(input) } }
                ?.takeUnless { it.isSplit }
                ?: return null

            return BaseModule(manifest) {
                zip.getInputStream(module).use { nestedEntry(it, TableBlock.FILE_NAME) { input -> TableBlock.load(input) } }
            }
        }

        /** The entry [name] of the APK streamed in [input], read by [load] without unpacking the APK. */
        private fun <T> nestedEntry(input: InputStream, name: String, load: (InputStream) -> T): T? {
            val apk = ZipInputStream(input)
            while (true) {
                val entry = apk.nextEntry ?: return null
                if (entry.name == name) return load(apk)
            }
        }
    }
}
