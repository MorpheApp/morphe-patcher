/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult.PatchedDexFile
import app.morphe.patcher.dex.DexBytesInputStream
import app.morphe.patcher.logging.PhaseTimings
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class DexArchiveWriterTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private val timings = PhaseTimings(Logger.getLogger(DexArchiveWriterTest::class.java.name), "test")

    /** Contents that shrink, as DEX files do. */
    private fun compressible(seed: Int, size: Int) = ByteArray(size) { i -> ((i / 7 + seed) % 23).toByte() }

    private fun readEntries(archive: File) = ZipFile(archive).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to (entry to zip.getInputStream(entry).readBytes()) }
    }

    @Test
    fun `entries added from several threads read back as they were added`() {
        val archive = temporaryDirectory.resolve("dex.zip")
        val contents = (1..20).associate { i -> "classes$i.dex" to compressible(i, 1000 * i) }

        DexArchiveWriter(archive).use { writer ->
            contents.entries.toList().parallelStream().forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }.value
                writer.add(DexArchiveWriter.Entry(name, DexArchiveWriter.STORED, crc, bytes.size, bytes, bytes.size))
            }
        }

        val entries = readEntries(archive)
        assertEquals(contents.keys, entries.keys)
        contents.forEach { (name, bytes) -> assertContentEquals(bytes, entries.getValue(name).second, name) }
    }

    @Test
    fun `dex files are compressed, or stored when they do not shrink or must be stored`() {
        val compressible = compressible(1, 100_000)
        val random = Random(7).nextBytes(10_000)
        val dexFiles = listOf(
            PatchedDexFile("classes.dex", DexBytesInputStream(compressible)),
            PatchedDexFile("classes2.dex", random.inputStream()),
        )

        val archive = CompressedDexFiles(dexFiles, temporaryDirectory.resolve("dex.zip"), false, timings)
            .use { it.await()!!.copyTo(temporaryDirectory.resolve("copy.zip")) }
        val entries = readEntries(archive)
        assertEquals(ZipEntry.DEFLATED, entries.getValue("classes.dex").first.method)
        assertContentEquals(compressible, entries.getValue("classes.dex").second)
        assertEquals(ZipEntry.STORED, entries.getValue("classes2.dex").first.method)
        assertContentEquals(random, entries.getValue("classes2.dex").second)

        // Files are compressed as they are read, and read again to be stored if they do not shrink.
        val compressibleFile = temporaryDirectory.resolve("classes3.dex").apply { writeBytes(compressible) }
        val randomFile = temporaryDirectory.resolve("classes4.dex").apply { writeBytes(random) }
        val fromFiles = CompressedDexFiles(
            listOf(
                PatchedDexFile(compressibleFile.name, compressibleFile.inputStream()),
                PatchedDexFile(randomFile.name, randomFile.inputStream()),
            ),
            temporaryDirectory.resolve("files.zip"),
            false,
            timings,
        ).use { it.await()!!.copyTo(temporaryDirectory.resolve("files-copy.zip")) }
        val fileEntries = readEntries(fromFiles)
        assertEquals(ZipEntry.DEFLATED, fileEntries.getValue("classes3.dex").first.method)
        assertContentEquals(compressible, fileEntries.getValue("classes3.dex").second)
        assertEquals(ZipEntry.STORED, fileEntries.getValue("classes4.dex").first.method)
        assertContentEquals(random, fileEntries.getValue("classes4.dex").second)

        val stored = CompressedDexFiles(
            listOf(PatchedDexFile("classes.dex", DexBytesInputStream(compressible))),
            temporaryDirectory.resolve("stored.zip"),
            true,
            timings,
        ).use { it.await()!!.copyTo(temporaryDirectory.resolve("stored-copy.zip")) }
        assertEquals(ZipEntry.STORED, readEntries(stored).getValue("classes.dex").first.method)
    }
}
