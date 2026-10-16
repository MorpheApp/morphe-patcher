/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethodImplementation
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

    override fun getStringSection(): OptionalIndexedSection<String> = strings

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
    }

    private class CachedSection(private val section: OptionalIndexedSection<String>) : OptionalIndexedSection<String>() {
        private val cache = arrayOfNulls<String>(section.size)

        override fun get(index: Int): String = cache[index] ?: section[index].also { cache[index] = it }

        override fun getOptional(index: Int): String? = if (index == -1) null else get(index)

        override fun getOffset(index: Int) = section.getOffset(index)

        override val size get() = section.size
    }
}
