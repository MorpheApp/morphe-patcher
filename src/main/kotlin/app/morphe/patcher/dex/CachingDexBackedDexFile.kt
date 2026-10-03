/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.nio.ByteBuffer

/**
 * A [DexBackedDexFile] that decodes each string once.
 *
 * Every name, type and string literal dexlib2 hands out is decoded from MUTF-8 on each access,
 * so the repeated scans of fingerprint matching spend most of their time decoding the same
 * strings again. Types resolve through the string section and share its cache.
 * The cache is filled racily, which is safe for immutable strings.
 */
internal class CachingDexBackedDexFile(opcodes: Opcodes?, buffer: ByteBuffer) : DexBackedDexFile(opcodes, buffer) {
    private val strings = CachedSection(super.getStringSection())

    override fun getStringSection(): OptionalIndexedSection<String> = strings

    private class CachedSection(private val section: OptionalIndexedSection<String>) : OptionalIndexedSection<String>() {
        private val cache = arrayOfNulls<String>(section.size)

        override fun get(index: Int): String = cache[index] ?: section[index].also { cache[index] = it }

        override fun getOptional(index: Int): String? = if (index == -1) null else get(index)

        override fun getOffset(index: Int) = section.getOffset(index)

        override val size get() = section.size
    }
}
