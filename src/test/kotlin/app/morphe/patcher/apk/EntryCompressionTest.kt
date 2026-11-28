/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class EntryCompressionTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private fun compressible(size: Int) = ByteArray(size) { i -> ((i / 7) % 23).toByte() }

    private fun compress(contents: ByteArray): DexArchiveWriter.Entry {
        val file = temporaryDirectory.resolve("file").apply { writeBytes(contents) }
        return FileInputStream(file).use { stream -> compressedEntry("file", stream) }
    }

    private fun DexArchiveWriter.Entry.contents(): ByteArray {
        if (method == DexArchiveWriter.STORED) return data.copyOf(dataLength)
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, 0, dataLength)
            val output = ByteArray(size)
            var read = 0
            while (read < size && !inflater.finished()) read += inflater.inflate(output, read, size - read)
            return output
        } finally {
            inflater.end()
        }
    }

    @Test
    fun `files of every size read back as they were`() {
        // Below, at and above the chunk a file is read in, and a file with nothing in it.
        for (size in listOf(0, 1, 1000, 256 * 1024 - 1, 256 * 1024, 256 * 1024 + 1, 700 * 1024)) {
            val contents = compressible(size)
            val entry = compress(contents)

            assertEquals(size, entry.size, "size $size")
            assertEquals(CRC32().apply { update(contents) }.value, entry.crc, "crc $size")
            assertContentEquals(contents, entry.contents(), "contents $size")
        }
    }

    @Test
    fun `a file that does not shrink is stored`() {
        val contents = Random(1).nextBytes(10_000)

        val entry = compress(contents)

        assertEquals(DexArchiveWriter.STORED, entry.method)
        assertContentEquals(contents, entry.contents())
    }

    @Test
    fun `a file that shrinks is deflated`() {
        assertEquals(DexArchiveWriter.DEFLATED, compress(compressible(10_000)).method)
    }
}
