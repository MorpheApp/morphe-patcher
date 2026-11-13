/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult.PatchedDexFile
import app.morphe.patcher.logging.PhaseTimings
import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.DataType
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Whether [Patcher.writeApk][app.morphe.patcher.Patcher.writeApk] compresses the patched DEX files while
 * the resources are encoded, as [CompressedDexFiles] does when `overlap` is true, or only once they are,
 * when [CompressedDexFiles.await] is called. A switch to measure what the overlap costs the encoding.
 */
internal var overlapDexCompression = true

/** The resource id of `android:useEmbeddedDex`, which ARSCLib has no constant for. */
internal const val ID_USE_EMBEDDED_DEX = 0x0101059e

/**
 * Whether the patched DEX files are stored uncompressed, which they are only for an app whose manifest in
 * [inputApk] sets `android:useEmbeddedDex`: the system installs it only with its DEX files stored, to run
 * them from the APK. Any other app gets every DEX file compressed, however the input stores them, as the
 * base of a bundle stores them for apps on Android 9 and up. The platform copies the code of an APK out of
 * it as soon as one of its DEX files is compressed, so storing only some would make the APK larger and save
 * nothing. An input that cannot be read stores none.
 */
internal fun storesDexUncompressed(inputApk: File): Boolean = try {
    ZipFile(inputApk).use { zip ->
        val entry = zip.getEntry(AndroidManifest.FILE_NAME) ?: return@use false
        val manifest = zip.getInputStream(entry).use(AndroidManifestBlock::load)
        val attribute = manifest.applicationElement?.searchAttributeByResourceId(ID_USE_EMBEDDED_DEX)
        attribute?.dataType == DataType.BOOLEAN && attribute.dataAsBoolean
    }
} catch (_: Exception) {
    false
}

/**
 * The DEX files [inputApk] stores uncompressed other than [exceptNames], extracted into [dir] to be
 * written again compressed.
 */
internal fun storedDexFilesIn(inputApk: File, exceptNames: Set<String>, dir: File): List<PatchedDexFile> =
    ZipFile(inputApk).use { zip ->
        zip.entries().asSequence()
            .filter { it.method == ZipEntry.STORED && it.name.matches(ApkUtils.dexEntryName) && it.name !in exceptNames }
            .map { entry ->
                val file = dir.resolve(entry.name).apply { parentFile.mkdirs() }
                zip.getInputStream(entry).use { input -> file.outputStream().use(input::copyTo) }
                PatchedDexFile(entry.name, file.inputStream())
            }
            .toList()
    }

/**
 * Compresses the patched DEX files into the temporary archive [archive] on a background thread, and the
 * patched APK copies the compressed entries as they are. If [overlap] is true, the thread is started at
 * once, so that the resources are encoded meanwhile. Otherwise, it is started by [await], so that nothing
 * runs while the resources are encoded.
 *
 * The DEX files are compressed in parallel as [ApkUtils.applyTo] compresses them, with
 * [ApkUtils.zFileOptions], so an entry that does not shrink is stored, or all stored if
 * [storeUncompressed], as [ApkUtils.applyTo] stores them.
 *
 * The streams of [dexFiles] are closed once they are read, or by [close] if they never are.
 *
 * @param storeUncompressed Whether to store the DEX files rather than compress them, see [storesDexUncompressed].
 * @param timings Where compressing and waiting for it are measured.
 * @param overlap Whether to start compressing at once rather than in [await].
 */
internal class CompressedDexFiles(
    dexFiles: Collection<PatchedDexFile>,
    private val archive: File,
    private val storeUncompressed: Boolean,
    private val timings: PhaseTimings,
    overlap: Boolean = true,
) : Closeable {
    private val dexFiles = dexFiles.toList()

    @Volatile
    private var failure: Throwable? = null

    private val thread: Thread? = if (this.dexFiles.isEmpty()) {
        null
    } else {
        Thread(::compress, "Morphe DEX compression").apply {
            isDaemon = true
            if (overlap) start()
        }
    }

    private fun compress() {
        try {
            timings.measure("dexCompress") {
                archive.absoluteFile.parentFile?.mkdirs()
                // Opening an existing file would add to what an earlier run left there.
                archive.delete()
                ZFile.openReadWrite(archive, ApkUtils.zFileOptions).use { zFile ->
                    dexFiles.forEach { dexFile ->
                        // The stream is read when the entry is added and compressed after.
                        dexFile.stream.use { stream -> zFile.add(dexFile.name, stream, !storeUncompressed) }
                    }
                }
            }
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            closeStreams()
        }
    }

    private fun closeStreams() = dexFiles.forEach { dexFile -> runCatching { dexFile.stream.close() } }

    private fun joinUninterruptibly() {
        val thread = thread ?: return
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Waits for the DEX files to be compressed, first starting to compress them if that has not started.
     *
     * @return The archive holding them, or null if there are none.
     * @throws Throwable What compressing them failed with.
     */
    fun await(): File? {
        if (thread == null) return null
        timings.measure("dexWait") {
            if (thread.state == Thread.State.NEW) thread.start()
            joinUninterruptibly()
        }
        failure?.let { throw it }
        return archive
    }

    /**
     * Waits for the compression to end, if it started, closes the streams that were not read and deletes
     * the archive.
     */
    override fun close() {
        joinUninterruptibly()
        closeStreams()
        archive.delete()
    }
}
