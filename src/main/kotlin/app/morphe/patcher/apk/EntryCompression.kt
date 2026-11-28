/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Compresses [contents], which are held in memory already, as [ApkUtils.applyTo] compresses an entry, at
 * [ApkUtils.COMPRESSION_LEVEL]. The entry is stored if [store] is true or if it does not shrink.
 */
internal fun compressedEntry(name: String, contents: ByteArray, store: Boolean = false): DexArchiveWriter.Entry {
    val crc = CRC32().apply { update(contents) }.value
    if (!store) {
        val output = DeflaterOutput(contents.size / 3)
        val deflater = Deflater(ApkUtils.COMPRESSION_LEVEL, true)
        try {
            deflater.setInput(contents)
            deflater.finish()
            output.deflateAll(deflater)
        } finally {
            deflater.end()
        }
        if (output.length < contents.size) {
            return DexArchiveWriter.Entry(name, DexArchiveWriter.DEFLATED, crc, contents.size, output.bytes, output.length)
        }
    }
    return DexArchiveWriter.Entry(name, DexArchiveWriter.STORED, crc, contents.size, contents, contents.size)
}

/**
 * Compresses a file as it is read, as [compressedEntry] compresses contents held in memory, so that only its
 * compressed contents are held, which on a small heap leaves the collector much less to do while every core
 * compresses a file. A file that does not shrink is read again to be stored.
 */
internal fun compressedEntry(name: String, stream: FileInputStream): DexArchiveWriter.Entry {
    val crc = CRC32()
    val fileSize = stream.channel.size()
    val output = DeflaterOutput((fileSize / 3).toInt())
    val deflater = Deflater(ApkUtils.COMPRESSION_LEVEL, true)
    val size = try {
        // Most files are small, so a chunk is not larger than the file. A chunk of no size would read nothing.
        val chunk = ByteArray(fileSize.coerceIn(1L, CHUNK_SIZE.toLong()).toInt())
        var size = 0L
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            crc.update(chunk, 0, count)
            size += count
            deflater.setInput(chunk, 0, count)
            output.deflateInput(deflater)
        }
        deflater.finish()
        output.deflateAll(deflater)
        Math.toIntExact(size)
    } finally {
        deflater.end()
    }
    if (output.length < size) {
        return DexArchiveWriter.Entry(name, DexArchiveWriter.DEFLATED, crc.value, size, output.bytes, output.length)
    }
    stream.channel.position(0)
    val contents = stream.readBytes()
    return DexArchiveWriter.Entry(name, DexArchiveWriter.STORED, crc.value, contents.size, contents, contents.size)
}

/** What a [Deflater] wrote, the first [length] bytes of [bytes], which grow as it writes more. */
private class DeflaterOutput(expectedSize: Int) {
    var bytes = ByteArray(expectedSize.coerceAtLeast(MIN_OUTPUT_SIZE))
        private set
    var length = 0
        private set

    /** Deflates the input the deflater was given, until it needs more. */
    fun deflateInput(deflater: Deflater) {
        while (!deflater.needsInput()) deflate(deflater)
    }

    /** Deflates the rest, once [Deflater.finish] was called. */
    fun deflateAll(deflater: Deflater) {
        while (!deflater.finished()) deflate(deflater)
    }

    private fun deflate(deflater: Deflater) {
        if (length == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        length += deflater.deflate(bytes, length, bytes.size - length)
    }
}

/** How much of a file is read at a time to be compressed, at most. */
private const val CHUNK_SIZE = 256 * 1024

/** The least room for compressed data, which grows by doubling when it is not enough. */
private const val MIN_OUTPUT_SIZE = 4 * 1024
