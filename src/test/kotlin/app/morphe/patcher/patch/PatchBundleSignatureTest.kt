/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PatchBundleSignatureTest {
    @TempDir
    lateinit var directory: File

    private fun bundle(comment: String? = null) = directory.resolve("patches.mpp").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(4096) { (it * 31).toByte() })
            zip.closeEntry()
            comment?.let(zip::setComment)
        }
    }

    private fun File.flipByte(offset: Long) = RandomAccessFile(this, "rw").use { archive ->
        archive.seek(offset)
        val byte = archive.read()
        archive.seek(offset)
        archive.write(byte xor 1)
    }

    @Test
    fun `signed bundle returns its signer and stays a readable archive`() {
        val file = bundle()

        assertEquals(PUBLIC_KEY, PatchBundleSignature.sign(file, PRIVATE_KEY))
        assertEquals(PUBLIC_KEY, PatchBundleSignature.verify(file))
        ZipFile(file).use { assertEquals(1, it.size()) }
    }

    @Test
    fun `unsigned bundle has no signer`() {
        assertNull(PatchBundleSignature.verify(bundle()))
        assertNull(PatchBundleSignature.verify(bundle(comment = "built by hand")))
    }

    @Test
    fun `signed bundle cannot be signed again`() {
        val file = bundle().apply { PatchBundleSignature.sign(this, PRIVATE_KEY) }

        assertFailsWith<IllegalArgumentException> { PatchBundleSignature.sign(file, PRIVATE_KEY) }
    }

    @Test
    fun `changed content fails`() {
        val file = bundle().apply {
            PatchBundleSignature.sign(this, PRIVATE_KEY)
            flipByte(100)
        }

        assertFailsWith<SecurityException> { PatchBundleSignature.verify(file) }
    }

    @Test
    fun `signature claimed by another key fails`() {
        val file = bundle().apply { PatchBundleSignature.sign(this, PRIVATE_KEY) }
        val text = file.readBytes().toString(Charsets.ISO_8859_1).replace(PUBLIC_KEY, OTHER_PUBLIC_KEY)
        file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))

        assertFailsWith<SecurityException> { PatchBundleSignature.verify(file) }
    }

    @Test
    fun `malformed signature fails`() {
        assertFailsWith<SecurityException> { PatchBundleSignature.verify(bundle(comment = "MorpheSig1 abc")) }
    }

    @Test
    fun `fingerprint is the start of the key digest`() {
        assertEquals("5647 5aa7 5463 474c", PatchBundleSignature.fingerprint(PUBLIC_KEY))
    }

    private companion object {
        // Keys made of the bytes 0 to 31 and of 32 sevens
        const val PRIVATE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        const val PUBLIC_KEY = "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg="
        const val OTHER_PUBLIC_KEY = "6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw="
    }
}
