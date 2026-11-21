package app.morphe.patcher.patch

import app.morphe.patcher.PatcherContext
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.string
import app.morphe.patcher.util.PatchClasses
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class ReplaceStringsTest {
    private lateinit var patchClasses: PatchClasses

    @AfterEach
    fun tearDown() {
        PatcherContext.current = null
    }

    @Test
    fun `exact strings are replaced and classes without them are not made mutable`() {
        usePatchClasses(
            "LA;" to listOf(
                method("LA;", "m1", "const-string v0, \"keep\"\nconst-string v1, \"a\"\nreturn-void"),
                method("LA;", "m2", "const-string v0, \"a\"\nreturn-void"),
            ),
            "LB;" to listOf(method("LB;", "m1", "const-string v0, \"b\"\nreturn-void")),
            "LC;" to listOf(method("LC;", "m1", "const-string v0, \"keep\"\nreturn-void")),
        )

        assertEquals(
            3,
            replaceStrings(
                string("a") to "x",
                string("b") to "y",
                string("missing") to "z",
            ),
        )

        assertEquals(listOf("keep", "x"), strings("LA;", "m1"))
        assertEquals(listOf("x"), strings("LA;", "m2"))
        assertEquals(listOf("y"), strings("LB;", "m1"))
        assertEquals(listOf("keep"), strings("LC;", "m1"))
        assertFalse(classDef("LC;") is MutableClass, "A class without a replaced string is not made mutable")

        val instruction = method("LA;", "m1").instructionsOrNull!!.elementAt(1)
        assertEquals(Opcode.CONST_STRING, instruction.opcode)
        assertEquals(1, (instruction as OneRegisterInstruction).registerA, "The register is kept")
    }

    @Test
    fun `replacements replace the part of the string the filter matches`() {
        usePatchClasses(
            "LA;" to listOf(
                method(
                    "LA;", "m1",
                    """
                        const-string v0, "foo-x-foo"
                        const-string v0, "end-x-end"
                        const-string v0, "mid-mid"
                        return-void
                    """,
                ),
            ),
        )

        assertEquals(
            3,
            replaceStrings(
                string("foo", StringComparisonType.STARTS_WITH) to "bar",
                string("end", StringComparisonType.ENDS_WITH) to "END",
                string("mid", StringComparisonType.CONTAINS) to "M",
            ),
        )

        assertEquals(listOf("bar-x-foo", "end-x-END", "M-M"), strings("LA;", "m1"))
    }

    @Test
    fun `strings are replaced with the first declared filter and are not matched again`() {
        usePatchClasses(
            "LA;" to listOf(
                method(
                    "LA;", "m1",
                    """
                        const-string v0, "a"
                        const-string v0, "b"
                        const-string v0, "abc"
                        const-string v0, "abd"
                        return-void
                    """,
                ),
            ),
        )

        assertEquals(
            4,
            replaceStrings(
                string("a") to "b",
                string("b") to "c",
                string("abd") to "exact",
                string("ab", StringComparisonType.STARTS_WITH) to "prefix_",
                string("abc") to "not used",
            ),
        )

        assertEquals(listOf("b", "c", "prefix_c", "exact"), strings("LA;", "m1"))
    }

    @Test
    fun `duplicate exact strings and empty partial strings are rejected`() {
        usePatchClasses("LA;" to listOf(method("LA;", "m1", "const-string v0, \"a\"\nreturn-void")))

        assertThrows<IllegalArgumentException> {
            replaceStrings(string("a") to "b", string("a") to "c")
        }
        assertThrows<IllegalArgumentException> {
            replaceStrings(string("", StringComparisonType.CONTAINS) to "b")
        }
        assertThrows<IllegalArgumentException> {
            replaceStrings(string("", StringComparisonType.STARTS_WITH)) { _, _, _ -> "b" }
        }
        assertEquals(listOf("a"), strings("LA;", "m1"))

        // The replacement function is called once for each instruction.
        var calls = 0
        assertEquals(1, replaceStrings(string("a"), string("a")) { _, _, _ -> calls++; "b" })
        assertEquals(1, calls)
        assertEquals(listOf("b"), strings("LA;", "m1"))
    }

    @Test
    fun `replacement function gets each match and returning null keeps the class immutable`() {
        usePatchClasses(
            "LA;" to listOf(
                method("LA;", "m1", "const-string v0, \"url:same\"\nreturn-void"),
                method("LA;", "m2", "const/4 v0, 0x0\nconst-string v1, \"url:one\"\nreturn-void"),
            ),
            "LB;" to listOf(method("LB;", "m1", "const-string v0, \"url:skip\"\nreturn-void")),
        )

        val calls = mutableListOf<String>()
        val replacedCount = replaceStrings(string("url:", StringComparisonType.STARTS_WITH)) { method, index, string ->
            assertFalse(method is MutableMethod, "The method of a class that is not mutable is not mutable")
            calls += "${method.definingClass}${method.name}:$index:$string"

            when (string) {
                "url:skip" -> null
                "url:same" -> string
                else -> string.uppercase()
            }
        }

        assertEquals(1, replacedCount)
        assertEquals(listOf("LA;m1:0:url:same", "LA;m2:1:url:one", "LB;m1:0:url:skip"), calls)
        assertEquals(listOf("URL:ONE"), strings("LA;", "m2"))
        assertFalse(classDef("LB;") is MutableClass, "Returning null does not make the class mutable")
        assertFalse(
            assertIs<MutableMethod>(method("LA;", "m1")).isImplementationCreated,
            "Returning the same string does not change the method",
        )
    }

    @Test
    fun `strings of mutable classes and changed methods are replaced`() {
        usePatchClasses(
            "LA;" to listOf(
                method("LA;", "m1", "return-void"),
                method("LA;", "m2", "return-void"),
            ),
            "LB;" to listOf(
                method("LB;", "m1", "const-string v0, \"original\"\nreturn-void"),
                method("LB;", "m2", "const-string v0, \"other\"\nreturn-void"),
            ),
        )
        // Index the classes before they are changed, so the added string is not in the indexes.
        patchClasses.getClassesByReferenceMap()

        val classA = patchClasses.classMap.getValue("LA;").getMutableClass()
        classA.methods.first { it.name == "m2" }.addInstructions(0, "const-string v0, \"added\"")
        val classB = patchClasses.classMap.getValue("LB;").getMutableClass()

        assertEquals(2, replaceStrings(string("added") to "added replaced", string("original") to "original replaced"))

        assertEquals(listOf("added replaced"), strings("LA;", "m2"))
        assertEquals(listOf("original replaced"), strings("LB;", "m1"))
        assertFalse(
            classB.methods.first { it.name == "m2" }.isImplementationCreated,
            "Methods without a replaced string are not changed",
        )
    }

    @Test
    fun `jumbo strings are replaced and the class can be written`() {
        usePatchClasses(
            "LA;" to listOf(method("LA;", "m1", "const-string/jumbo v1, \"jumbo\"\nreturn-void")),
        )

        assertEquals(1, replaceStrings(string("jumbo") to "replaced"))

        val instruction = method("LA;", "m1").instructionsOrNull!!.first()
        assertEquals(Opcode.CONST_STRING, instruction.opcode)
        assertEquals(1, (instruction as OneRegisterInstruction).registerA)

        val dexPool = DexPool(Opcodes.getDefault())
        dexPool.internClass(classDef("LA;"))
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)
        val writtenClass = DexReadWrite.readDexStream(ByteArrayInputStream(dataStore.data)).classes.single()
        assertEquals(listOf("replaced"), writtenClass.methods.single().strings())
    }

    @Test
    fun `nothing is replaced without filters or matches`() {
        usePatchClasses("LA;" to listOf(method("LA;", "m1", "const-string v0, \"a\"\nreturn-void")))

        assertEquals(0, replaceStrings())
        assertEquals(0, replaceStrings(emptyList()) { _, _, _ -> "b" })
        assertEquals(0, replaceStrings(string("b") to "c"))
        assertTrue(classDef("LA;") !is MutableClass)
    }

    private fun method(definingClass: String, name: String, smali: String) = ImmutableMethod.of(
        ImmutableMethod(
            definingClass, name, emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, MutableMethodImplementation(2),
        ).toMutable().apply { addInstructions(0, smali) }
    )

    /**
     * Uses dex backed classes with the methods for patching, as when patching an APK.
     */
    private fun usePatchClasses(vararg classes: Pair<String, List<ImmutableMethod>>) {
        val dexPool = DexPool(Opcodes.getDefault())
        classes.forEach { (type, methods) ->
            dexPool.internClass(ImmutableClassDef(type, 0, null, null, null, null, null, methods))
        }
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)

        val dexClasses = DexReadWrite.readDexStream(ByteArrayInputStream(dataStore.data)).classes
            .associateBy { it.type }
        patchClasses = PatchClasses(classes.mapTo(LinkedHashSet()) { (type, _) -> dexClasses.getValue(type) })

        val context = mockk<PatcherContext> {
            every { bytecodeContext.patchClasses } returns patchClasses
        }
        PatcherContext.current = context
    }

    private fun classDef(type: String): ClassDef = patchClasses.classMap.getValue(type).classDef

    private fun method(type: String, name: String): Method = classDef(type).methods.first { it.name == name }

    private fun strings(type: String, name: String) = method(type, name).strings()

    private fun Method.strings() = instructionsOrNull!!.filter { instruction ->
        instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO
    }.map { instruction ->
        ((instruction as ReferenceInstruction).reference as StringReference).string
    }
}
