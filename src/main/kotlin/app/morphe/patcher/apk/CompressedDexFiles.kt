/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult.PatchedDexFile
import app.morphe.patcher.logging.PhaseTimings
import com.android.tools.build.apkzlib.zip.ZFile
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

/**
 * The names of [dexFileNames] that [inputApk] stores uncompressed, as an app that loads its code from the APK
 * must store its DEX files. An input the platform reader cannot read has none.
 */
internal fun dexFilesStoredIn(inputApk: File, dexFileNames: Collection<String>): Set<String> = try {
    ZipFile(inputApk).use { zip ->
        dexFileNames.filterTo(HashSet()) { name -> zip.getEntry(name)?.method == ZipEntry.STORED }
    }
} catch (_: Exception) {
    emptySet()
}

/**
 * Compresses the patched DEX files into the temporary archive [archive] on a background thread, and the
 * patched APK copies the compressed entries as they are. If [overlap] is true, the thread is started at
 * once, so that the resources are encoded meanwhile. Otherwise, it is started by [await], so that nothing
 * runs while the resources are encoded.
 *
 * The DEX files are compressed in parallel as [ApkUtils.applyTo] compresses them, with
 * [ApkUtils.zFileOptions], so an entry that does not shrink is stored. A DEX file the input APK stores
 * uncompressed, as an app that loads its code from the APK must, is stored as well, as [ApkUtils.applyTo]
 * stores it.
 *
 * The streams of [dexFiles] are closed once they are read, or by [close] if they never are.
 *
 * @param inputApk The input APK, which tells which DEX files to store.
 * @param timings Where compressing and waiting for it are measured.
 * @param overlap Whether to start compressing at once rather than in [await].
 */
internal class CompressedDexFiles(
    dexFiles: Collection<PatchedDexFile>,
    private val archive: File,
    private val inputApk: File,
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
                val storedInInput = dexFilesStoredIn(inputApk, dexFiles.map { it.name })
                archive.absoluteFile.parentFile?.mkdirs()
                // Opening an existing file would add to what an earlier run left there.
                archive.delete()
                ZFile.openReadWrite(archive, ApkUtils.zFileOptions).use { zFile ->
                    dexFiles.forEach { dexFile ->
                        // The stream is read when the entry is added and compressed after.
                        dexFile.stream.use { stream -> zFile.add(dexFile.name, stream, dexFile.name !in storedInInput) }
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
