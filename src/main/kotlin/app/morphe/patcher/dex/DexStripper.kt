/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Binary DEX file editor that removes class_def entries without re-encoding the file.
 *
 * This operates directly on memory-mapped files, removing class definitions by shifting
 * remaining entries and updating only the header, map_list, and checksums.
 * All other sections (string_ids, type_ids, method_ids, code items, etc.)
 * remain byte-identical, since class_def entries are never referenced by index.
 */
internal object DexStripper {

    // DEX header field offsets (little-endian uint32 unless noted).
    private const val CHECKSUM_OFF = 8          // uint: Adler32 checksum
    private const val SIGNATURE_OFF = 12        // ubyte[20]: SHA-1 signature
    private const val SIGNATURE_SIZE = 20
    private const val FILE_SIZE_OFF = 32        // uint: total file size
    private const val MAP_OFF_OFF = 52          // uint: offset to map_list
    private const val STRING_IDS_SIZE_OFF = 56  // uint: count of string_ids
    private const val STRING_IDS_OFF_OFF = 60   // uint: offset to string_ids
    private const val TYPE_IDS_OFF_OFF = 68     // uint: offset to type_ids
    private const val CLASS_DEFS_SIZE_OFF = 96  // uint: count of class_defs
    private const val CLASS_DEFS_OFF_OFF = 100  // uint: offset to class_defs

    private const val CLASS_DEF_ITEM_SIZE = 32  // each class_def_item is 32 bytes

    // map_item type code for class_def_item
    private const val TYPE_CLASS_DEF_ITEM: Short = 0x0006

    // map_item struct: ushort type, ushort unused, uint size, uint offset = 12 bytes
    private const val MAP_ITEM_SIZE = 12

    /**
     * Strips the given class definitions from a DEX file on disk, editing it in-place
     * via a memory-mapped buffer. No heap-allocated copy of the full file is needed.
     *
     * @param dexFile The DEX file to edit in-place.
     * @param classDescriptorsToRemove Set of class descriptors to remove (e.g., "Lcom/example/Foo;").
     * @return true if any classes were removed, false otherwise.
     */
    fun stripInPlace(dexFile: File, classDescriptorsToRemove: Set<String>): Boolean {
        if (classDescriptorsToRemove.isEmpty()) return false

        RandomAccessFile(dexFile, "rw").use { raf ->
            val channel = raf.channel
            val mappedBuf = channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length())
            val buf = mappedBuf.order(ByteOrder.LITTLE_ENDIAN)

            val stringIdsOff = buf.getInt(STRING_IDS_OFF_OFF)
            val typeIdsOff = buf.getInt(TYPE_IDS_OFF_OFF)
            val classDefsSize = buf.getInt(CLASS_DEFS_SIZE_OFF)
            val classDefsOff = buf.getInt(CLASS_DEFS_OFF_OFF)

            if (classDefsSize == 0) return false

            // Identify which class_def indices to remove.
            val indicesToRemove = mutableListOf<Int>()
            for (i in 0 until classDefsSize) {
                val entryOff = classDefsOff + i * CLASS_DEF_ITEM_SIZE
                val classIdx = buf.getInt(entryOff)  // type_ids index
                val descriptor = resolveDescriptor(buf, classIdx, typeIdsOff, stringIdsOff)
                if (descriptor in classDescriptorsToRemove) {
                    indicesToRemove.add(i)
                }
            }

            if (indicesToRemove.isEmpty()) return false

            // Remove class_def entries by compacting: shift remaining entries left.
            val newClassDefsSize = classDefsSize - indicesToRemove.size

            // Build compacted class_defs in-place.
            // Use a temporary 32-byte array for copying entries within the mapped buffer.
            val temp = ByteArray(CLASS_DEF_ITEM_SIZE)
            var writeIdx = 0
            for (readIdx in 0 until classDefsSize) {
                if (readIdx in indicesToRemove) continue
                if (writeIdx != readIdx) {
                    val srcOff = classDefsOff + readIdx * CLASS_DEF_ITEM_SIZE
                    val dstOff = classDefsOff + writeIdx * CLASS_DEF_ITEM_SIZE
                    buf.position(srcOff)
                    buf.get(temp)
                    buf.position(dstOff)
                    buf.put(temp)
                }
                writeIdx++
            }

            // Zero-fill the freed space at the end of the class_defs section.
            val freedStart = classDefsOff + newClassDefsSize * CLASS_DEF_ITEM_SIZE
            val freedEnd = classDefsOff + classDefsSize * CLASS_DEF_ITEM_SIZE
            val zeros = ByteArray(freedEnd - freedStart)
            buf.position(freedStart)
            buf.put(zeros)

            // Update header: class_defs_size.
            buf.putInt(CLASS_DEFS_SIZE_OFF, newClassDefsSize)

            // Update map_list entry for TYPE_CLASS_DEF_ITEM.
            updateMapListClassDefsSize(buf, newClassDefsSize)

            // Recompute checksums.
            recomputeSignature(buf, raf.length().toInt())
            recomputeChecksum(buf, raf.length().toInt())

            mappedBuf.force()
        }

        return true
    }

    /**
     * Resolves a type_ids index to its class descriptor string.
     */
    private fun resolveDescriptor(
        buf: ByteBuffer,
        typeIdx: Int,
        typeIdsOff: Int,
        stringIdsOff: Int,
    ): String {
        // type_id_item is just a uint descriptor_idx (index into string_ids).
        val descriptorIdx = buf.getInt(typeIdsOff + typeIdx * 4)

        // string_id_item is just a uint string_data_off.
        val stringDataOff = buf.getInt(stringIdsOff + descriptorIdx * 4)

        return readMutf8(buf, stringDataOff)
    }

    /**
     * Reads a MUTF-8 string from the DEX data section.
     * The format is: ULEB128 length (in UTF-16 code units), then MUTF-8 bytes, then null terminator.
     */
    private fun readMutf8(buf: ByteBuffer, offset: Int): String {
        // Skip the ULEB128 utf16_size prefix — we just read until the null terminator.
        var pos = offset
        while (buf.get(pos).toInt() and 0x80 != 0) pos++  // skip ULEB128 bytes
        pos++  // skip the last ULEB128 byte

        val sb = StringBuilder()
        while (true) {
            val b = buf.get(pos++).toInt() and 0xFF
            if (b == 0) break
            if (b and 0x80 == 0) {
                // Single byte: 0xxxxxxx
                sb.append(b.toChar())
            } else if (b and 0xE0 == 0xC0) {
                // Two bytes: 110xxxxx 10xxxxxx
                val b2 = buf.get(pos++).toInt() and 0x3F
                sb.append(((b and 0x1F shl 6) or b2).toChar())
            } else if (b and 0xF0 == 0xE0) {
                // Three bytes: 1110xxxx 10xxxxxx 10xxxxxx
                val b2 = buf.get(pos++).toInt() and 0x3F
                val b3 = buf.get(pos++).toInt() and 0x3F
                sb.append(((b and 0x0F shl 12) or (b2 shl 6) or b3).toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Finds and updates the size field of the TYPE_CLASS_DEF_ITEM entry in the map_list.
     */
    private fun updateMapListClassDefsSize(buf: ByteBuffer, newSize: Int) {
        val mapOff = buf.getInt(MAP_OFF_OFF)
        val mapSize = buf.getInt(mapOff)

        for (i in 0 until mapSize) {
            val itemOff = mapOff + 4 + i * MAP_ITEM_SIZE  // 4 bytes for the map size uint
            val type = buf.getShort(itemOff)
            if (type == TYPE_CLASS_DEF_ITEM) {
                // map_item: ushort type (0), ushort unused (2), uint size (4), uint offset (8)
                buf.putInt(itemOff + 4, newSize)
                return
            }
        }
    }

    /**
     * Recomputes the SHA-1 signature over bytes 32 through end of file.
     */
    private fun recomputeSignature(buf: ByteBuffer, fileSize: Int) {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val startOff = SIGNATURE_OFF + SIGNATURE_SIZE
        val chunk = ByteArray(8192)
        var remaining = fileSize - startOff
        var pos = startOff
        while (remaining > 0) {
            val toRead = minOf(remaining, chunk.size)
            buf.position(pos)
            buf.get(chunk, 0, toRead)
            sha1.update(chunk, 0, toRead)
            pos += toRead
            remaining -= toRead
        }
        val signature = sha1.digest()
        buf.position(SIGNATURE_OFF)
        buf.put(signature)
    }

    /**
     * Recomputes the Adler32 checksum over bytes 12 through end of file.
     */
    private fun recomputeChecksum(buf: ByteBuffer, fileSize: Int) {
        val adler = Adler32()
        val chunk = ByteArray(8192)
        var remaining = fileSize - SIGNATURE_OFF
        var pos = SIGNATURE_OFF
        while (remaining > 0) {
            val toRead = minOf(remaining, chunk.size)
            buf.position(pos)
            buf.get(chunk, 0, toRead)
            adler.update(chunk, 0, toRead)
            pos += toRead
            remaining -= toRead
        }
        buf.putInt(CHECKSUM_OFF, adler.value.toInt())
    }
}
