/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * A DEX file held on the heap in place of a mapping of a file, see [DexReadWrite.readMultidexFileFromZip].
 * [originalFile] is where the file would be extracted to, and does not exist unless it is written there.
 */
internal class MemoryDexFile(override val originalFile: File, val bytes: ByteArray) : MappedFile {
    override val buffer: ByteBuffer = ByteBuffer.wrap(bytes)

    /** Nothing to write back, as the contents are not backed by storage. */
    override fun force() = Unit

    override fun close() = Unit

    /** Writes the contents to [originalFile]. */
    fun writeToOriginalFile() {
        originalFile.absoluteFile.parentFile?.mkdirs()
        originalFile.writeBytes(bytes)
    }
}

/**
 * A stream over the contents of a [MemoryDexFile], which tells a reader that wants all of them where they
 * are, so that they need not be copied. The array must not change while it is read.
 */
internal class DexBytesInputStream(val bytes: ByteArray) : ByteArrayInputStream(bytes)
