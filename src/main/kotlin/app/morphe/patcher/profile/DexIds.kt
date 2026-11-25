/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.profile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the id sections of a DEX file where it is, which is all a baseline profile refers to: the
 * types, methods and class definitions, by their index. Strings are read as their raw MUTF-8 bytes,
 * one char per byte, so two DEX files name the same type or method with equal strings.
 *
 * @param buffer The whole DEX file. Its position and order are left as they are.
 * @throws IllegalArgumentException If [buffer] is not a DEX file, or is a DEX container (version 041),
 * which a baseline profile cannot describe.
 */
internal class DexIds(buffer: ByteBuffer) {
    private val dex = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(dex.capacity() >= HEADER_SIZE && dex.getInt(0) == DEX_MAGIC) { "Not a DEX file" }
        val version = (0 until 3).map { dex.get(4 + it).toInt().toChar() }.joinToString("")
        require(version in SUPPORTED_VERSIONS) { "DEX version $version has no baseline profile form" }
    }

    private val stringIdsOff = dex.getInt(STRING_IDS_OFF)
    private val typeIdsOff = dex.getInt(TYPE_IDS_OFF)
    private val protoIdsOff = dex.getInt(PROTO_IDS_OFF)
    private val methodIdsOff = dex.getInt(METHOD_IDS_OFF)
    private val classDefsOff = dex.getInt(CLASS_DEFS_OFF)

    val typeCount = dex.getInt(TYPE_IDS_SIZE)
    val methodCount = dex.getInt(METHOD_IDS_SIZE)
    val classDefCount = dex.getInt(CLASS_DEFS_SIZE)

    /** The type index of the class each class definition defines, in the order of the definitions. */
    fun classDefTypes() = IntArray(classDefCount) { dex.getInt(classDefsOff + it * CLASS_DEF_SIZE) }

    /** The type index of the class that declares method [method]. */
    fun methodClass(method: Int) = dex.getShort(methodIdsOff + method * METHOD_ID_SIZE).toInt() and 0xFFFF

    fun typeDescriptor(type: Int) = string(dex.getInt(typeIdsOff + type * TYPE_ID_SIZE))

    /** The class, name and prototype of method [method], such as `La;->b(I)V`. */
    fun methodSignature(method: Int): String {
        val offset = methodIdsOff + method * METHOD_ID_SIZE
        val proto = dex.getShort(offset + 2).toInt() and 0xFFFF
        val name = dex.getInt(offset + 4)
        return buildString {
            append(typeDescriptor(methodClass(method))).append("->").append(string(name)).append('(')
            val protoOffset = protoIdsOff + proto * PROTO_ID_SIZE
            val parametersOff = dex.getInt(protoOffset + 8)
            if (parametersOff != 0) {
                val size = dex.getInt(parametersOff)
                for (index in 0 until size) {
                    append(typeDescriptor(dex.getShort(parametersOff + 4 + index * 2).toInt() and 0xFFFF))
                }
            }
            append(')').append(typeDescriptor(dex.getInt(protoOffset + 4)))
        }
    }

    private fun string(index: Int): String {
        var offset = dex.getInt(stringIdsOff + index * STRING_ID_SIZE)
        // The data starts with the length in UTF-16 code units, which the bytes do not need.
        var lengthByte: Int
        do lengthByte = dex.get(offset++).toInt() while (lengthByte and 0x80 != 0)
        val start = offset
        while (dex.get(offset) != 0.toByte()) offset++
        // Older Android versions have no absolute bulk reads of a ByteBuffer.
        val bytes = ByteArray(offset - start) { dex.get(start + it) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private companion object {
        const val DEX_MAGIC = 0x0A786564 // "dex\n"
        // A profile keys each DEX file by the CRC of its own APK entry, which DEX container files lack.
        val SUPPORTED_VERSIONS = setOf("035", "036", "037", "038", "039", "040")

        const val HEADER_SIZE = 0x70
        const val STRING_IDS_OFF = 0x3C
        const val TYPE_IDS_SIZE = 0x40
        const val TYPE_IDS_OFF = 0x44
        const val PROTO_IDS_OFF = 0x4C
        const val METHOD_IDS_SIZE = 0x58
        const val METHOD_IDS_OFF = 0x5C
        const val CLASS_DEFS_SIZE = 0x60
        const val CLASS_DEFS_OFF = 0x64

        const val STRING_ID_SIZE = 4
        const val TYPE_ID_SIZE = 4
        const val PROTO_ID_SIZE = 12
        const val METHOD_ID_SIZE = 8
        const val CLASS_DEF_SIZE = 32
    }
}
