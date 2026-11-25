/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.dex

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object CachingDexBackedDexFileTest {
    private fun dexFile(strings: List<String>): CachingDexBackedDexFile {
        val implementation = MutableMethodImplementation(1)
        strings.forEach { string ->
            implementation.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string)))
        }
        val method = ImmutableMethod(
            "Lcom/test/Strings;", "strings", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, implementation,
        )
        val dexPool = DexPool(Opcodes.getDefault())
        dexPool.internClass(
            ImmutableClassDef(
                "Lcom/test/Strings;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method),
            )
        )
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        return CachingDexBackedDexFile(null, dataStore.data)
    }

    @Test
    fun `hash codes of strings are those of the decoded strings`() {
        val strings = listOf(
            "", "a", "Lcom/test/A;", "x".repeat(300),
            // Characters of two and three bytes, of a surrogate pair, and the null character, which has two bytes.
            "café", "中文", "smile 😀", "null ${Char(0)} character",
        )
        val dexFile = dexFile(strings)
        val expected = dexFile(strings).stringSection.map { it.hashCode() }
        assertTrue(dexFile(strings).stringSection.containsAll(strings))

        // Twice, as the second time they are those kept from the first.
        repeat(2) {
            assertEquals(expected, expected.indices.map(dexFile::stringHashCode))
        }
        dexFile.releaseStringHashCodes()
        assertEquals(expected, expected.indices.map(dexFile::stringHashCode))

        // Of strings that were decoded.
        dexFile.stringSection.toList()
        assertEquals(expected, expected.indices.map(dexFile::stringHashCode))
    }
}
