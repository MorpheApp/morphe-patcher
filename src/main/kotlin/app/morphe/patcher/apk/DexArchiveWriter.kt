/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a zip archive of entries that were compressed before they are added, in the order they are
 * added, for [CompressedDexFiles]. Entries can be added from several threads at a time. The archive is
 * only read by the writer of the patched APK, which copies the entries as they are, so it is not aligned
 * and has no ZIP64 records.
 *
 * @param file The file to write, replacing what is there.
 */
internal class DexArchiveWriter(file: File) : Closeable {
    /**
     * An entry as it is stored in the archive.
     *
     * @param method [STORED] or [DEFLATED].
     * @param crc The CRC-32 of the uncompressed contents.
     * @param size The size of the uncompressed contents.
     * @param data The stored or compressed contents, the first [dataLength] bytes of the array.
     */
    class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val size: Int,
        val data: ByteArray,
        val dataLength: Int,
    )

    private val output = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val centralDirectory = ByteArrayOutputStream()
    private var count = 0
    private var closed = false

    /** Writes [entry] after the entries written so far. */
    @Synchronized
    fun add(entry: Entry) {
        check(!closed) { "The archive is closed" }
        check(count < MAX_ENTRIES) { "Too many entries for an archive without ZIP64 records" }

        val offset = output.filePointer
        check(offset + LOCAL_HEADER_SIZE + entry.dataLength < MAX_OFFSET) { "The archive is too large without ZIP64 records" }

        val name = entry.name.toByteArray(Charsets.UTF_8)
        val flags = if (name.size != entry.name.length) UTF8_FLAG else 0
        val version = if (entry.method == STORED) STORED_VERSION else DEFLATED_VERSION

        val local = littleEndian(LOCAL_HEADER_SIZE + name.size)
            .putInt(LOCAL_HEADER_SIGNATURE).putShort(version).putShort(flags.toShort())
            .putShort(entry.method.toShort()).putShort(DOS_TIME).putShort(DOS_DATE)
            .putInt(entry.crc.toInt()).putInt(entry.dataLength).putInt(entry.size)
            .putShort(name.size.toShort()).putShort(0).put(name)
        output.write(local.array())
        output.write(entry.data, 0, entry.dataLength)

        val central = littleEndian(CENTRAL_HEADER_SIZE + name.size)
            .putInt(CENTRAL_HEADER_SIGNATURE).putShort(DEFLATED_VERSION).putShort(version).putShort(flags.toShort())
            .putShort(entry.method.toShort()).putShort(DOS_TIME).putShort(DOS_DATE)
            .putInt(entry.crc.toInt()).putInt(entry.dataLength).putInt(entry.size)
            .putShort(name.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0)
            .putInt(0).putInt(offset.toInt()).put(name)
        centralDirectory.write(central.array())
        count++
    }

    /** Writes the central directory after the entries and closes the file. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.use { file ->
            val offset = file.filePointer
            file.write(centralDirectory.toByteArray())
            val end = littleEndian(END_SIZE)
                .putInt(END_SIGNATURE).putShort(0).putShort(0)
                .putShort(count.toShort()).putShort(count.toShort())
                .putInt(centralDirectory.size()).putInt(offset.toInt()).putShort(0)
            file.write(end.array())
        }
    }

    private fun littleEndian(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val STORED = 0
        const val DEFLATED = 8

        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22

        private const val STORED_VERSION: Short = 10
        private const val DEFLATED_VERSION: Short = 20
        private const val UTF8_FLAG = 0x0800

        // 1981-01-01 01:01:02, the time apkzlib gives the entries it writes, so that the patched APK is
        // the same as when the DEX files were compressed by it.
        private const val DOS_TIME: Short = 0x0821
        private const val DOS_DATE: Short = 0x0221

        private const val MAX_ENTRIES = 0xFFFF
        private const val MAX_OFFSET = 0xFFFFFFFFL
    }
}
