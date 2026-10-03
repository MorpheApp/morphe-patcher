/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class DexStripperTest {
    @TempDir
    lateinit var tempDir: File

    private fun createDexFile(vararg classDefs: ImmutableClassDef): File {
        val file = tempDir.resolve("test-${System.nanoTime()}.dex")
        DexPool(Opcodes.getDefault()).apply {
            classDefs.forEach { internClass(it) }
            writeTo(FileDataStore(file))
        }
        return file
    }

    private fun simpleClassDef(
        type: String,
        fields: List<ImmutableField> = emptyList(),
        methods: List<ImmutableMethod> = emptyList(),
    ) = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        fields,
        methods,
    )

    private fun readClassDescriptors(file: File): Set<String> {
        return file.inputStream().buffered().use { stream ->
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), stream)
                .classes
                .mapTo(HashSet()) { it.type }
        }
    }

    @Test
    fun `stripInPlace with empty descriptors to strip returns 0`() {
        val file = createDexFile(simpleClassDef("Lcom/example/A;"), simpleClassDef("Lcom/example/B;"))
        val initialBytes = file.readBytes()

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(mapped, emptySet())
            assertEquals(0, strippedCount)
        }

        assertEquals(initialBytes.toList(), file.readBytes().toList())
    }

    @Test
    fun `stripInPlace with non-existent descriptor returns 0`() {
        val file = createDexFile(simpleClassDef("Lcom/example/A;"), simpleClassDef("Lcom/example/B;"))
        val initialBytes = file.readBytes()

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(mapped, setOf("Lcom/example/NonExistent;"))
            assertEquals(0, strippedCount)
        }

        assertEquals(initialBytes.toList(), file.readBytes().toList())
    }

    @Test
    fun `stripInPlace removes a single class definition`() {
        val file = createDexFile(
            simpleClassDef("Lcom/example/A;"),
            simpleClassDef("Lcom/example/B;"),
            simpleClassDef("Lcom/example/C;"),
        )

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(mapped, setOf("Lcom/example/B;"))
            assertEquals(1, strippedCount)
        }

        val survivingClasses = readClassDescriptors(file)
        assertEquals(setOf("Lcom/example/A;", "Lcom/example/C;"), survivingClasses)
    }

    @Test
    fun `stripInPlace removes multiple class definitions`() {
        val file = createDexFile(
            simpleClassDef("Lcom/example/A;"),
            simpleClassDef("Lcom/example/B;"),
            simpleClassDef("Lcom/example/C;"),
            simpleClassDef("Lcom/example/D;"),
        )

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(
                mapped,
                setOf("Lcom/example/A;", "Lcom/example/C;", "Lcom/example/Missing;"),
            )
            assertEquals(2, strippedCount)
        }

        val survivingClasses = readClassDescriptors(file)
        assertEquals(setOf("Lcom/example/B;", "Lcom/example/D;"), survivingClasses)
    }

    @Test
    fun `stripInPlace removes all class definitions`() {
        val file = createDexFile(
            simpleClassDef("Lcom/example/A;"),
            simpleClassDef("Lcom/example/B;"),
        )

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(
                mapped,
                setOf("Lcom/example/A;", "Lcom/example/B;"),
            )
            assertEquals(2, strippedCount)
        }

        val survivingClasses = readClassDescriptors(file)
        assertTrue(survivingClasses.isEmpty())
    }

    @Test
    fun `stripInPlace compacts class_data section with fields and methods`() {
        val fieldA = ImmutableField("Lcom/example/A;", "fieldA", "I", AccessFlags.PUBLIC.value, null, null, null)
        val methodA = ImmutableMethod("Lcom/example/A;", "methodA", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, null)
        val fieldB = ImmutableField("Lcom/example/B;", "fieldB", "I", AccessFlags.PUBLIC.value, null, null, null)
        val methodB = ImmutableMethod("Lcom/example/B;", "methodB", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, null)
        val fieldC = ImmutableField("Lcom/example/C;", "fieldC", "I", AccessFlags.PUBLIC.value, null, null, null)
        val methodC = ImmutableMethod("Lcom/example/C;", "methodC", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, null)

        val file = createDexFile(
            simpleClassDef("Lcom/example/A;", listOf(fieldA), listOf(methodA)),
            simpleClassDef("Lcom/example/B;", listOf(fieldB), listOf(methodB)),
            simpleClassDef("Lcom/example/C;", listOf(fieldC), listOf(methodC)),
        )

        MappedFile.mapReadWrite(file).use { mapped ->
            val strippedCount = DexStripper.stripInPlace(mapped, setOf("Lcom/example/B;"))
            assertEquals(1, strippedCount)
        }

        val dex = file.inputStream().buffered().use { stream ->
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), stream)
        }

        val surviving = dex.classes.associate { it.type to it }
        assertEquals(setOf("Lcom/example/A;", "Lcom/example/C;"), surviving.keys)

        val classA = surviving.getValue("Lcom/example/A;")
        assertEquals(listOf("fieldA"), classA.fields.map { it.name })
        assertEquals(listOf("methodA"), classA.methods.map { it.name })

        val classC = surviving.getValue("Lcom/example/C;")
        assertEquals(listOf("fieldC"), classC.fields.map { it.name })
        assertEquals(listOf("methodC"), classC.methods.map { it.name })
    }

    @Test
    fun `compareMutf8 produces identical comparison ordering to String compareTo`() {
        val testStrings = listOf(
            "",
            "A",
            "B",
            "Landroid/app/Activity;",
            "Lcom/example/A;",
            "Lcom/example/A$1;",
            "Lcom/example/AA;",
            "Lcom/example/B;",
            "Lcom/example/Foo;",
            "Lcom/example/FooBar;",
            "Ljava/lang/Object;",
            "Ljava/lang/String;",
            "Z",
            "\u00e9cole",
            "\u4e2d\u6587",
        )

        // Helper to encode MUTF-8 string with ULEB128 utf16_size prefix.
        fun encodeMutf8Item(s: String): ByteArray {
            val mutf8Bytes = java.io.ByteArrayOutputStream()
            for (c in s) {
                val code = c.code
                when {
                    code == 0 -> {
                        mutf8Bytes.write(0xC0)
                        mutf8Bytes.write(0x80)
                    }
                    code in 0x01..0x7F -> {
                        mutf8Bytes.write(code)
                    }
                    code in 0x80..0x7FF -> {
                        mutf8Bytes.write(0xC0 or ((code shr 6) and 0x1F))
                        mutf8Bytes.write(0x80 or (code and 0x3F))
                    }
                    else -> {
                        mutf8Bytes.write(0xE0 or ((code shr 12) and 0x0F))
                        mutf8Bytes.write(0x80 or ((code shr 6) and 0x3F))
                        mutf8Bytes.write(0x80 or (code and 0x3F))
                    }
                }
            }
            mutf8Bytes.write(0) // null terminator

            val out = java.io.ByteArrayOutputStream()
            // Write utf16_size as ULEB128
            var v = s.length
            do {
                var b = v and 0x7F
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out.write(b)
            } while (v != 0)

            out.write(mutf8Bytes.toByteArray())
            return out.toByteArray()
        }

        for (s1 in testStrings) {
            val encoded = encodeMutf8Item(s1)
            val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

            // Test readMutf8 roundtrip
            assertEquals(s1, DexStripper.readMutf8(buf, 0))

            // Test compareMutf8 against all target strings
            for (s2 in testStrings) {
                val expectedSign = s1.compareTo(s2).sign()
                val actualCmp = DexStripper.compareMutf8(buf, 0, s2)
                val actualSign = actualCmp.sign()
                assertEquals(
                    expectedSign,
                    actualSign,
                    "Comparing '$s1' with '$s2': expected sign $expectedSign but got $actualSign (cmp=$actualCmp)",
                )
            }
        }
    }

    @Test
    fun `findTypeIndex finds types correctly in DEX`() {
        val classA = "Lcom/example/Alpha;"
        val classB = "Lcom/example/Beta;"
        val classC = "Lcom/example/Gamma;"

        val file = createDexFile(
            simpleClassDef(classA),
            simpleClassDef(classB),
            simpleClassDef(classC),
        )

        MappedFile.mapReadWrite(file).use { mapped ->
            val buf = mapped.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val typeIdsSize = buf.getInt(64)
            val typeIdsOff = buf.getInt(68)
            val stringIdsOff = buf.getInt(60)

            val idxA = DexStripper.findTypeIndex(buf, typeIdsOff, typeIdsSize, stringIdsOff, classA)
            val idxB = DexStripper.findTypeIndex(buf, typeIdsOff, typeIdsSize, stringIdsOff, classB)
            val idxC = DexStripper.findTypeIndex(buf, typeIdsOff, typeIdsSize, stringIdsOff, classC)
            val idxMissing = DexStripper.findTypeIndex(buf, typeIdsOff, typeIdsSize, stringIdsOff, "Lmissing/Type;")

            assertTrue(idxA >= 0)
            assertTrue(idxB >= 0)
            assertTrue(idxC >= 0)
            assertEquals(-1, idxMissing)

            assertEquals(classA, DexStripper.resolveDescriptor(buf, idxA, typeIdsOff, stringIdsOff))
            assertEquals(classB, DexStripper.resolveDescriptor(buf, idxB, typeIdsOff, stringIdsOff))
            assertEquals(classC, DexStripper.resolveDescriptor(buf, idxC, typeIdsOff, stringIdsOff))
        }
    }

    private fun Int.sign() = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
