/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Binary DEX file editor that strips class definitions by compacting them out of the
 * class_defs array and their class_data_items out of the data section.
 *
 * For each matched class_def:
 * 1. The class_def entry is removed from the class_defs array (subsequent entries
 *    shift up) and the class_defs count in both the header and map_list is decremented.
 * 2. The corresponding class_data_item (if any) is compacted out of the class_data
 *    section. Surviving class_defs' class_data_off pointers are updated to reflect
 *    the new positions, and the map_list count is decremented.
 *
 * This completely eliminates both the class definition and its method/field data,
 * preventing ART from seeing duplicate definitions across DEX files and satisfying
 * dexdump's cross-reference validation.
 *
 * The real implementations of stripped classes live in separate DEX files that are
 * loaded first (lower-numbered classesN.dex).
 */
internal object DexStripper {

    // DEX header field offsets (little-endian uint32 unless noted).
    private const val CHECKSUM_OFF = 8          // uint: Adler32 checksum
    private const val SIGNATURE_OFF = 12        // ubyte[20]: SHA-1 signature
    private const val SIGNATURE_SIZE = 20
    private const val MAP_OFF_OFF = 52          // uint: offset to map_list
    private const val STRING_IDS_OFF_OFF = 60   // uint: offset to string_ids
    private const val TYPE_IDS_OFF_OFF = 68     // uint: offset to type_ids
    private const val CLASS_DEFS_SIZE_OFF = 96  // uint: count of class_defs
    private const val CLASS_DEFS_OFF_OFF = 100  // uint: offset to class_defs

    private const val CLASS_DEF_ITEM_SIZE = 32  // each class_def_item is 32 bytes

    // Offsets within a class_def_item.
    private const val CLASS_DEF_CLASS_DATA_OFF = 24

    // map_list constants.
    private const val TYPE_CLASS_DEF_ITEM: Int = 0x0006
    private const val TYPE_CLASS_DATA_ITEM: Int = 0x2000
    private const val MAP_ITEM_SIZE = 12  // each map_item is 12 bytes

    /**
     * Strips class definitions from a DEX file by compacting them out of the
     * class_defs array and their class_data_items out of the data section.
     *
     * @param dexFile The DEX file to edit in-place.
     * @param classDescriptorsToStrip Set of class descriptors to strip (e.g., "Lcom/example/Foo;").
     * @return The number of class definitions stripped.
     */
    fun stripInPlace(dexFile: File, classDescriptorsToStrip: Set<String>): Int {
        if (classDescriptorsToStrip.isEmpty()) return 0

        RandomAccessFile(dexFile, "rw").use { raf ->
            val fileSize = raf.length().toInt()
            val mappedBuf = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length())
            val buf = mappedBuf.order(ByteOrder.LITTLE_ENDIAN)

            val stringIdsOff = buf.getInt(STRING_IDS_OFF_OFF)
            val typeIdsOff = buf.getInt(TYPE_IDS_OFF_OFF)
            val classDefsSize = buf.getInt(CLASS_DEFS_SIZE_OFF)
            val classDefsOff = buf.getInt(CLASS_DEFS_OFF_OFF)
            val mapOff = buf.getInt(MAP_OFF_OFF)

            if (classDefsSize == 0) return 0

            // Identify which class_def indices to remove and their class_data offsets.
            val indicesToRemove = mutableListOf<Int>()
            val orphanedClassDataOffsets = HashSet<Int>()

            for (i in 0 until classDefsSize) {
                val entryOff = classDefsOff + i * CLASS_DEF_ITEM_SIZE
                val classIdx = buf.getInt(entryOff)
                val descriptor = resolveDescriptor(buf, classIdx, typeIdsOff, stringIdsOff)
                if (descriptor in classDescriptorsToStrip) {
                    indicesToRemove.add(i)
                    val classDataOff = buf.getInt(entryOff + CLASS_DEF_CLASS_DATA_OFF)
                    if (classDataOff != 0) {
                        orphanedClassDataOffsets.add(classDataOff)
                    }
                }
            }

            if (indicesToRemove.isEmpty()) return 0

            // Compact the class_data section: remove orphaned items, update pointers.
            if (orphanedClassDataOffsets.isNotEmpty()) {
                compactClassData(buf, mapOff, classDefsOff, classDefsSize, indicesToRemove, orphanedClassDataOffsets)
            }

            // Compact the class_defs array.
            compactClassDefs(buf, classDefsOff, classDefsSize, indicesToRemove)

            val newClassDefsSize = classDefsSize - indicesToRemove.size

            // Update class_defs_size in the header.
            buf.putInt(CLASS_DEFS_SIZE_OFF, newClassDefsSize)

            // Update TYPE_CLASS_DEF_ITEM count in the map_list.
            updateMapItemCount(buf, mapOff, TYPE_CLASS_DEF_ITEM, newClassDefsSize)

            // Recompute checksums.
            recomputeSignature(buf, fileSize)
            recomputeChecksum(buf, fileSize)

            mappedBuf.force()

            return indicesToRemove.size
        }
    }

    // -------------------------------------------------------------------------
    // class_data compaction
    // -------------------------------------------------------------------------

    /**
     * Compacts the class_data section by removing orphaned class_data_items and
     * updating all surviving class_def class_data_off pointers.
     *
     * Parses all class_data_items sequentially from the map, identifies orphaned
     * ones, copies surviving items forward to fill gaps, zeroes the tail, and
     * updates the map count.
     */
    private fun compactClassData(
        buf: ByteBuffer,
        mapOff: Int,
        classDefsOff: Int,
        classDefsSize: Int,
        indicesToRemove: List<Int>,
        orphanedClassDataOffsets: Set<Int>,
    ) {
        // Find the TYPE_CLASS_DATA_ITEM map entry.
        val mapEntry = findMapEntry(buf, mapOff, TYPE_CLASS_DATA_ITEM) ?: return
        val blockOff = mapEntry.second
        val itemCount = mapEntry.first

        // Parse all class_data_items to get their boundaries.
        val items = parseClassDataItemBoundaries(buf, blockOff, itemCount)

        // Build old_offset → new_offset mapping by compacting non-orphaned items forward.
        val oldToNewOffset = HashMap<Int, Int>(items.size * 2)
        var writePos = blockOff
        var keptCount = 0

        for ((itemOff, itemSize) in items) {
            if (itemOff in orphanedClassDataOffsets) continue

            oldToNewOffset[itemOff] = writePos
            if (writePos != itemOff) {
                // Copy item bytes forward.
                val temp = ByteArray(itemSize)
                buf.position(itemOff)
                buf.get(temp)
                buf.position(writePos)
                buf.put(temp)
            }
            writePos += itemSize
            keptCount++
        }

        // Zero the vacated tail.
        val lastItem = items.last()
        val blockEnd = lastItem.first + lastItem.second
        if (blockEnd > writePos) {
            buf.position(writePos)
            buf.put(ByteArray(blockEnd - writePos))
        }

        // Update surviving class_defs' class_data_off pointers.
        val removeSet = indicesToRemove.toHashSet()
        for (i in 0 until classDefsSize) {
            if (i in removeSet) continue
            val entryOff = classDefsOff + i * CLASS_DEF_ITEM_SIZE
            val oldDataOff = buf.getInt(entryOff + CLASS_DEF_CLASS_DATA_OFF)
            if (oldDataOff != 0) {
                val newDataOff = oldToNewOffset[oldDataOff]
                    ?: error("class_data_off $oldDataOff not found in compacted block")
                buf.putInt(entryOff + CLASS_DEF_CLASS_DATA_OFF, newDataOff)
            }
        }

        // Update map_list count.
        updateMapItemCount(buf, mapOff, TYPE_CLASS_DATA_ITEM, keptCount)
    }

    /**
     * Parses [count] class_data_items sequentially starting at [offset] and returns
     * a list of (offset, byteSize) pairs.
     *
     * A class_data_item is:
     *   static_fields_size (uleb128), instance_fields_size (uleb128),
     *   direct_methods_size (uleb128), virtual_methods_size (uleb128),
     *   encoded_field[static_fields_size],   // each: field_idx_diff + access_flags
     *   encoded_field[instance_fields_size],
     *   encoded_method[direct_methods_size],  // each: method_idx_diff + access_flags + code_off
     *   encoded_method[virtual_methods_size]
     */
    private fun parseClassDataItemBoundaries(
        buf: ByteBuffer,
        offset: Int,
        count: Int,
    ): List<Pair<Int, Int>> {
        val items = ArrayList<Pair<Int, Int>>(count)
        var pos = offset

        for (i in 0 until count) {
            val itemStart = pos
            val staticFieldsSize = readUleb128(buf, pos).also { pos = it.second }.first
            val instanceFieldsSize = readUleb128(buf, pos).also { pos = it.second }.first
            val directMethodsSize = readUleb128(buf, pos).also { pos = it.second }.first
            val virtualMethodsSize = readUleb128(buf, pos).also { pos = it.second }.first

            // encoded_field: 2 ULEB128 values each.
            repeat(staticFieldsSize + instanceFieldsSize) {
                pos = skipUleb128(buf, pos)
                pos = skipUleb128(buf, pos)
            }
            // encoded_method: 3 ULEB128 values each.
            repeat(directMethodsSize + virtualMethodsSize) {
                pos = skipUleb128(buf, pos)
                pos = skipUleb128(buf, pos)
                pos = skipUleb128(buf, pos)
            }

            items.add(itemStart to (pos - itemStart))
        }

        return items
    }

    // -------------------------------------------------------------------------
    // class_defs compaction
    // -------------------------------------------------------------------------

    /**
     * Compacts the class_defs array by removing entries at [indicesToRemove] and
     * shifting subsequent entries up to fill the gaps. Zeroes the vacated tail.
     */
    private fun compactClassDefs(
        buf: ByteBuffer,
        classDefsOff: Int,
        classDefsSize: Int,
        indicesToRemove: List<Int>,
    ) {
        val removeSet = indicesToRemove.toHashSet()
        val temp = ByteArray(CLASS_DEF_ITEM_SIZE)
        var writeIdx = 0

        for (readIdx in 0 until classDefsSize) {
            if (readIdx in removeSet) continue

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

        // Zero out the vacated tail entries.
        val tailStart = classDefsOff + writeIdx * CLASS_DEF_ITEM_SIZE
        val tailEnd = classDefsOff + classDefsSize * CLASS_DEF_ITEM_SIZE
        if (tailEnd > tailStart) {
            buf.position(tailStart)
            buf.put(ByteArray(tailEnd - tailStart))
        }
    }

    // -------------------------------------------------------------------------
    // map_list helpers
    // -------------------------------------------------------------------------

    /**
     * Finds a map entry by type. Returns (count, offset) or null.
     */
    private fun findMapEntry(buf: ByteBuffer, mapOff: Int, targetType: Int): Pair<Int, Int>? {
        val mapSize = buf.getInt(mapOff)
        for (i in 0 until mapSize) {
            val entryOff = mapOff + 4 + i * MAP_ITEM_SIZE
            val type = buf.getShort(entryOff).toInt() and 0xFFFF
            if (type == targetType) {
                val count = buf.getInt(entryOff + 4)
                val offset = buf.getInt(entryOff + 8)
                return count to offset
            }
        }
        return null
    }

    /**
     * Updates a map entry's count field by type.
     */
    private fun updateMapItemCount(buf: ByteBuffer, mapOff: Int, targetType: Int, newCount: Int) {
        val mapSize = buf.getInt(mapOff)
        for (i in 0 until mapSize) {
            val entryOff = mapOff + 4 + i * MAP_ITEM_SIZE
            val type = buf.getShort(entryOff).toInt() and 0xFFFF
            if (type == targetType) {
                buf.putInt(entryOff + 4, newCount)
                return
            }
        }
    }

    // -------------------------------------------------------------------------
    // DEX structure reading
    // -------------------------------------------------------------------------

    /**
     * Resolves a type_ids index to its class descriptor string.
     */
    private fun resolveDescriptor(
        buf: ByteBuffer,
        typeIdx: Int,
        typeIdsOff: Int,
        stringIdsOff: Int,
    ): String {
        val descriptorIdx = buf.getInt(typeIdsOff + typeIdx * 4)
        val stringDataOff = buf.getInt(stringIdsOff + descriptorIdx * 4)
        return readMutf8(buf, stringDataOff)
    }

    /**
     * Reads a MUTF-8 string from the DEX data section.
     */
    private fun readMutf8(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        while (buf.get(pos).toInt() and 0x80 != 0) pos++
        pos++

        val sb = StringBuilder()
        while (true) {
            val b = buf.get(pos++).toInt() and 0xFF
            if (b == 0) break
            if (b and 0x80 == 0) {
                sb.append(b.toChar())
            } else if (b and 0xE0 == 0xC0) {
                val b2 = buf.get(pos++).toInt() and 0x3F
                sb.append(((b and 0x1F shl 6) or b2).toChar())
            } else if (b and 0xF0 == 0xE0) {
                val b2 = buf.get(pos++).toInt() and 0x3F
                val b3 = buf.get(pos++).toInt() and 0x3F
                sb.append(((b and 0x0F shl 12) or (b2 shl 6) or b3).toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Reads a ULEB128 value at [pos], returning (value, newPos).
     */
    private fun readUleb128(buf: ByteBuffer, pos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = pos
        while (true) {
            val b = buf.get(p++).toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }

    /**
     * Skips one ULEB128 value at [pos], returning the position after it.
     */
    private fun skipUleb128(buf: ByteBuffer, pos: Int): Int {
        var p = pos
        while (buf.get(p++).toInt() and 0x80 != 0) { /* skip */ }
        return p
    }

    // -------------------------------------------------------------------------
    // Checksum computation
    // -------------------------------------------------------------------------

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
