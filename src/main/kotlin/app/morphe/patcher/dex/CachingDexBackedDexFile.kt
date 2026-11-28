/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethodImplementation
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import java.nio.ByteBuffer

/**
 * A [DexBackedDexFile] that decodes each string once.
 *
 * Every name, type and string literal dexlib2 hands out is decoded from MUTF-8 on each access,
 * so the repeated scans of fingerprint matching spend most of their time decoding the same
 * strings again. Types resolve through the string section and share its cache.
 * The cache is filled racily, which is safe for immutable strings.
 */
internal class CachingDexBackedDexFile private constructor(
    opcodes: Opcodes?,
    buffer: ByteBuffer,
    /**
     * The contents of this DEX file if they were read into memory, or null if the file is memory mapped.
     */
    val bytes: ByteArray?,
) : DexBackedDexFile(opcodes, buffer) {
    constructor(opcodes: Opcodes?, buffer: ByteBuffer) : this(opcodes, buffer, null)

    constructor(opcodes: Opcodes?, bytes: ByteArray) : this(opcodes, ByteBuffer.wrap(bytes), bytes)

    private val strings = CachedSection(super.getStringSection())

    /**
     * The hash codes of the strings, or 0 for those not calculated yet. Null until the first is asked for.
     * Filled racily, which is safe for ints.
     */
    private var stringHashCodes: IntArray? = null

    override fun getStringSection(): OptionalIndexedSection<String> = strings

    /**
     * The hash code of the [String] at [index]. A string that was not decoded yet is hashed where it is in the
     * DEX file, as most strings of an app are only ever looked up by their hash code.
     */
    internal fun stringHashCode(index: Int): Int {
        strings.cached(index)?.let { return it.hashCode() }

        val hashCodes = stringHashCodes ?: IntArray(strings.size).also { stringHashCodes = it }
        var hashCode = hashCodes[index]
        if (hashCode == 0) {
            hashCode = hashEncodedString(index)
            hashCodes[index] = hashCode
        }
        return hashCode
    }

    /**
     * Frees the hash codes [stringHashCode] keeps. They are calculated again if needed.
     */
    internal fun releaseStringHashCodes() {
        stringHashCodes = null
    }

    private fun hashEncodedString(index: Int): Int {
        val data = dataBuffer
        val reader = data.readerAt(buffer.readSmallUint(strings.getOffset(index)))
        val length = reader.readSmallUleb128()
        val start = reader.offset

        var hashCode = 0
        for (i in 0 until length) {
            val byte = data.readByte(start + i)
            // A character of more than one byte is left to the decoder.
            if (byte < 0) return strings[index].hashCode()
            hashCode = 31 * hashCode + byte
        }
        return hashCode
    }

    override fun createMethodImplementation(
        dexFile: DexBackedDexFile,
        method: DexBackedMethod,
        codeOffset: Int,
    ): DexBackedMethodImplementation = MethodImplementation(dexFile, method, codeOffset)

    /**
     * A method implementation that gives the location of its instructions,
     * so they can be read without creating an object for each instruction.
     */
    internal class MethodImplementation(
        dexFile: DexBackedDexFile,
        method: DexBackedMethod,
        codeOffset: Int,
    ) : DexBackedMethodImplementation(dexFile, method, codeOffset) {
        /**
         * The offset of the first instruction in the data buffer of the DEX file.
         */
        val instructionsStart: Int
            get() = instructionsStartOffset

        /**
         * Calls [action] with the opcode and the offset in the data buffer of each instruction, read
         * without creating the instructions, as [DexBackedMethodImplementation] and [DexBackedInstruction]
         * read them, until [action] returns false.
         *
         * The opcode is null if the value is not an opcode of the DEX file version, which a dex backed
         * instruction reads as a nop.
         */
        inline fun forEachInstruction(action: (opcode: Opcode?, offset: Int) -> Boolean) {
            val opcodes = dexFile.opcodes
            val buffer = dexFile.dataBuffer
            var offset = instructionsStart
            val end = offset + instructionsSize * 2

            while (offset < end) {
                var opcodeValue = buffer.readUbyte(offset)
                if (opcodeValue == 0) opcodeValue = buffer.readUshort(offset)
                val opcode = opcodes.getOpcodeByValue(opcodeValue)
                if (!action(opcode, offset)) return

                offset += if (opcode == null || opcode.format.isPayloadFormat) {
                    DexBackedInstruction.getCodeUnits(dexFile, offset) * 2
                } else {
                    opcode.format.size
                }
            }
        }
    }

    private class CachedSection(private val section: OptionalIndexedSection<String>) : OptionalIndexedSection<String>() {
        private val cache = arrayOfNulls<String>(section.size)

        /**
         * The string if it was decoded before.
         */
        fun cached(index: Int): String? = cache[index]

        override fun get(index: Int): String = cache[index] ?: section[index].also { cache[index] = it }

        override fun getOptional(index: Int): String? = if (index == -1) null else get(index)

        override fun getOffset(index: Int) = section.getOffset(index)

        override val size get() = section.size
    }
}
