/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import com.android.apksig.apk.ApkFormatException
import com.android.apksig.util.DataSources
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.android.apksig.apk.ApkUtils as ApkSigUtils

internal class ApkSigningBlockTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `a block is padded to whole pages as apksig pads it`() {
        // A block holding one pair is 44 bytes plus the value: a value of 4052 bytes fills the page.
        val pageFilled = ApkSigningBlock.build(listOf(entry(0x1234, 4052)))
        assertEquals(4096, pageFilled.size)
        assertEquals(listOf(0x1234), ApkSigningBlock.parse(ByteBuffer.wrap(pageFilled)).map { it.id }, "no padding")

        // A gap of 12 bytes takes a padding pair with an empty value.
        val emptyPadding = ApkSigningBlock.build(listOf(entry(0x1234, 4040)))
        assertEquals(4096, emptyPadding.size)
        assertEquals(0, ApkSigningBlock.parse(ByteBuffer.wrap(emptyPadding)).last().value.size)

        // A gap too small for a padding pair is widened by a page.
        val widened = ApkSigningBlock.build(listOf(entry(0x1234, 4047)))
        assertEquals(8192, widened.size)
        assertEquals(ApkSigningBlock.PADDING_ID, ApkSigningBlock.parse(ByteBuffer.wrap(widened)).last().id)

        for (valueSize in listOf(0, 1, 100, 4039, 5000)) {
            val block = ApkSigningBlock.build(listOf(entry(0x1234, valueSize)))
            assertEquals(0, block.size % 4096, "a value of $valueSize bytes")
            assertEquals(valueSize, ApkSigningBlock.parse(ByteBuffer.wrap(block)).first().value.size)
        }
    }

    @Test
    fun `a block is read back as it was built, without padding pairs carried over`() {
        val entries = listOf(entry(1, 10), entry(ApkSigningBlock.PADDING_ID, 30), entry(2, 0), entry(3, 700))
        val read = ApkSigningBlock.parse(ByteBuffer.wrap(ApkSigningBlock.build(entries)))

        assertEquals(listOf(1, 2, 3, ApkSigningBlock.PADDING_ID), read.map { it.id })
        listOf(entries[0], entries[2], entries[3]).zip(read).forEach { (written, readBack) ->
            assertContentEquals(written.value, readBack.value, "pair ${written.id}")
        }
    }

    @Test
    fun `a pair running past the pairs is malformed`() {
        val block = ApkSigningBlock.build(listOf(entry(1, 10)))
        ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putLong(8, 100_000)

        assertFailsWith<ApkFormatException> { ApkSigningBlock.parse(ByteBuffer.wrap(block)) }
    }

    @Test
    fun `the v2 signature goes first, in place of the one the block has`() {
        val block = ApkSigningBlock.build(
            listOf(entry(ApkSignatureScheme.V3.id, 40), entry(ApkSignatureScheme.V2.id, 20)),
        )
        val signature = ByteArray(1671) { it.toByte() }

        val read = ApkSigningBlock.parse(ByteBuffer.wrap(ApkSigningBlock.withV2Signature(block, signature)))

        assertEquals(
            listOf(ApkSignatureScheme.V2.id, ApkSignatureScheme.V3.id, ApkSigningBlock.PADDING_ID),
            read.map { it.id },
        )
        assertContentEquals(signature, read.first().value)
    }

    @Test
    fun `a block written to an archive replaces the block it has and leaves the entries readable`() {
        val apk = temporaryDirectory.resolve("archive.zip")
        val contents = mapOf("a.txt" to "first".toByteArray(), "b/c.bin" to ByteArray(3000) { 7 })
        ZipOutputStream(apk.outputStream()).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        ApkSigningBlock.write(apk, listOf(entry(1, 5000)))
        ApkSigningBlock.write(apk, listOf(entry(2, 10)))

        assertEquals(
            listOf(2, ApkSigningBlock.PADDING_ID),
            RandomAccessFile(apk, "r").use { ApkSigningBlock.entriesOf(DataSources.asDataSource(it)) }.map { it.id },
        )
        assertEquals(4096L, blockSizeOf(apk), "the first block is gone")
        ZipFile(apk).use { zip ->
            contents.forEach { (name, bytes) ->
                assertContentEquals(bytes, zip.getInputStream(zip.getEntry(name)).readBytes(), name)
            }
        }
    }

    @Test
    fun `an archive without a block has no pairs`() {
        val apk = temporaryDirectory.resolve("plain.zip")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("a.txt"))
            zip.closeEntry()
        }

        assertEquals(null, ApkSigningBlock.valueOf(apk, ApkSignatureScheme.V2.id))
    }

    private fun entry(id: Int, valueSize: Int) = ApkSigningBlock.Entry(id, ByteArray(valueSize) { (it % 251).toByte() })

    private fun blockSizeOf(apk: File) = RandomAccessFile(apk, "r").use { file ->
        val data = DataSources.asDataSource(file)
        ApkSigUtils.findApkSigningBlock(data, ApkSigUtils.findZipSections(data)).contents.size()
    }
}
