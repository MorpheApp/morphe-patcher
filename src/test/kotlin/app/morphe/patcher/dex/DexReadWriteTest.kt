/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.dexbacked.raw.HeaderItem
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodProtoReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DexReadWriteTest {
    @TempDir
    lateinit var temporaryDirectory: File

    private fun zipOf(vararg entries: Pair<String, ByteArray>) =
        temporaryDirectory.resolve("input.apk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }

    private fun dexOf(opcodes: Opcodes, vararg classes: Pair<String, BuilderInstruction>): ByteArray {
        val dexPool = DexPool(opcodes)
        classes.forEach { (type, instruction) ->
            val implementation = MutableMethodImplementation(1).apply {
                addInstruction(instruction)
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            }
            val method = ImmutableMethod(type, "method", null, "V", AccessFlags.PUBLIC.value, null, null, implementation)
            dexPool.internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)))
        }
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    private fun stringInstruction(string: String) = BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string))

    private fun checksumIsValid(dex: ByteArray): Boolean {
        val adler = Adler32().apply { update(dex, HeaderItem.CHECKSUM_DATA_START_OFFSET, dex.size - HeaderItem.CHECKSUM_DATA_START_OFFSET) }
        val signature = MessageDigest.getInstance("SHA-1").apply {
            update(dex, HeaderItem.SIGNATURE_DATA_START_OFFSET, dex.size - HeaderItem.SIGNATURE_DATA_START_OFFSET)
        }.digest()
        return ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN).getInt(HeaderItem.CHECKSUM_OFFSET) == adler.value.toInt() &&
                signature.contentEquals(dex.copyOfRange(HeaderItem.SIGNATURE_OFFSET, HeaderItem.SIGNATURE_OFFSET + HeaderItem.SIGNATURE_SIZE))
    }

    @Test
    fun `dex copies keep only the kept classes with the dex version of the dex pool`() {
        val dexBytes = dexOf(Opcodes.forApi(27), "La;" to stringInstruction("a"), "Lb;" to stringInstruction("b"))
        assertEquals(38, HeaderItem.getVersion(dexBytes, 0))
        val classes = DexReadWrite.readDexStream(dexBytes.inputStream()).classes.associateBy { it.type }
        val copy = temporaryDirectory.resolve("copy.dex")

        assertTrue(
            DexReadWrite.writeDexCopy(dexBytes, classes.keys, listOf(classes.getValue("La;")), Opcodes.forApi(23), copy)
        )
        val copyBytes = copy.readBytes()
        assertEquals(35, HeaderItem.getVersion(copyBytes, 0))
        assertTrue(checksumIsValid(copyBytes))
        val copiedClass = DexReadWrite.readDexStream(copyBytes.inputStream()).classes.single()
        assertEquals("La;", copiedClass.type)
        assertEquals(
            "a",
            ((copiedClass.methods.single().implementation!!.instructions.first() as ReferenceInstruction).reference as StringReference).string,
        )

        // Lowering the version without stripping keeps the checksum and signature valid.
        assertTrue(DexReadWrite.writeDexCopy(dexBytes, classes.keys, classes.values, Opcodes.forApi(23), copy))
        assertEquals(35, HeaderItem.getVersion(copy.readBytes(), 0))
        assertTrue(checksumIsValid(copy.readBytes()))

        // A newer dex pool version is not needed.
        assertTrue(DexReadWrite.writeDexCopy(dexBytes, classes.keys, classes.values, Opcodes.forApi(28), copy))
        assertEquals(38, HeaderItem.getVersion(copy.readBytes(), 0))
        assertEquals(setOf("La;", "Lb;"), DexReadWrite.readDexStream(copy.readBytes().inputStream()).classes.mapTo(HashSet()) { it.type })
    }

    @Test
    fun `dex copies are not written if the classes use instructions of a newer dex version`() {
        val methodType = BuilderInstruction21c(Opcode.CONST_METHOD_TYPE, 0, ImmutableMethodProtoReference(emptyList(), "V"))
        val dexBytes = dexOf(Opcodes.forApi(28), "La;" to methodType, "Lb;" to stringInstruction("b"))
        val classes = DexReadWrite.readDexStream(dexBytes.inputStream()).classes.associateBy { it.type }
        val copy = temporaryDirectory.resolve("copy.dex")

        assertFalse(DexReadWrite.writeDexCopy(dexBytes, classes.keys, classes.values, Opcodes.forApi(23), copy))
        assertFalse(copy.exists())
        // Classes that use only older instructions can be copied.
        assertTrue(
            DexReadWrite.writeDexCopy(dexBytes, classes.keys, listOf(classes.getValue("Lb;")), Opcodes.forApi(23), copy)
        )
    }

    @Test
    fun `dex entry that climbs out of the output directory is rejected`() {
        val apk = zipOf("classes/../../evil.dex" to "payload".toByteArray())
        val outputDir = temporaryDirectory.resolve("out")

        assertFailsWith<SecurityException> { DexReadWrite.extractDexEntries(apk, outputDir) }
        assertFalse(temporaryDirectory.resolve("evil.dex").exists())
    }

    @Test
    fun `ordinary dex entries are extracted unchanged`() {
        val apk = zipOf(
            "classes.dex" to "one".toByteArray(),
            "classes2.dex" to "two".toByteArray(),
            "assets/readme.txt" to "ignored".toByteArray(),
        )
        val outputDir = temporaryDirectory.resolve("out")

        val files = DexReadWrite.extractDexEntries(apk, outputDir)

        assertEquals(listOf("classes.dex", "classes2.dex"), files.map { it.name })
        assertEquals("two", outputDir.resolve("classes2.dex").readText())
    }

    @Test
    fun `dex files read into memory are the same as the extracted ones and leave no files`() {
        val opcodes = Opcodes.forApi(26)
        val first = dexOf(opcodes, "La;" to stringInstruction("first"))
        val second = dexOf(opcodes, "Lb;" to stringInstruction("second"))
        val apk = zipOf("classes.dex" to first, "classes2.dex" to second)
        val outputDir = temporaryDirectory.resolve("out")

        DexReadWrite.readMultidexFileFromZip(apk, outputDir, inMemory = true).use { result ->
            assertTrue(result.mappedFiles.all { it is MemoryDexFile })
            assertEquals(listOf("classes.dex", "classes2.dex"), result.mappedFiles.map { it.originalFile.name })
            assertContentEquals(second, (result.mappedFiles[1] as MemoryDexFile).bytes)
            assertEquals(setOf("La;", "Lb;"), result.dexFile.classes.mapTo(HashSet()) { it.type })
        }
        assertFalse(outputDir.resolve("classes.dex").exists())

        DexReadWrite.readMultidexFileFromZip(apk, outputDir, inMemory = false).use { result ->
            assertFalse(result.mappedFiles.any { it is MemoryDexFile })
            assertContentEquals(first, outputDir.resolve("classes.dex").readBytes())
        }
    }

    @Test
    fun `apk without dex files fails with a clear message`() {
        val apk = zipOf("assets/readme.txt" to "nothing to patch".toByteArray())

        val exception = assertFailsWith<PatchException> {
            DexReadWrite.readMultidexFileFromZip(apk, temporaryDirectory.resolve("out"))
        }
        assertEquals("APK contains no DEX files to patch", exception.message)
    }

    @Test
    fun `methods patches left alone are written from their own instructions`() {
        val opcodes = Opcodes.getDefault()
        fun method(name: String, string: String) = ImmutableMethod(
            "La;", name, null, "V", AccessFlags.PUBLIC.value, null, null,
            MutableMethodImplementation(1).apply {
                addInstruction(stringInstruction(string))
                addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            },
        )
        val dexBytes = MemoryDataStore().also { dataStore ->
            DexPool(opcodes).apply {
                internClass(
                    ImmutableClassDef(
                        "La;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                        listOf(method("changed", "old"), method("unchanged", "kept")),
                    )
                )
                writeTo(dataStore)
            }
        }.data

        val mutableClass = DexReadWrite.readDexStream(dexBytes.inputStream()).classes.single().toMutable()
        mutableClass.methods.first { it.name == "changed" }.implementation!!.replaceInstruction(0, stringInstruction("new"))

        val written = DexReadWrite.writeMultiDexFile(temporaryDirectory.resolve("out"), mutableListOf(mutableClass), opcodes)

        assertFalse(
            mutableClass.methods.first { it.name == "unchanged" }.isImplementationCreated,
            "Writing must not create the mutable implementation of a method patches left alone",
        )
        val strings = DexReadWrite.readDexStream(written.single().readBytes().inputStream()).classes.single().methods.associate { method ->
            method.name to method.implementation!!.instructions.firstNotNullOf { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            }
        }
        assertEquals(mapOf("changed" to "new", "unchanged" to "kept"), strings)
    }
}
