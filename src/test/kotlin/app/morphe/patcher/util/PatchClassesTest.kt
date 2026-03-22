/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object PatchClassesTest {

    private lateinit var patchClasses: PatchClasses

    private fun createClassDef(
        type: String,
        methods: List<ImmutableMethod> = emptyList()
    ) = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null, null, null, null,
        methods
    )

    private fun createMethodWithStrings(
        definingClass: String,
        methodName: String,
        strings: List<String>
    ): ImmutableMethod {
        val implementation = MutableMethodImplementation(2)
        strings.forEach { str ->
            implementation.addInstruction(
                BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(str))
            )
        }
        return ImmutableMethod(
            definingClass,
            methodName,
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value,
            null, null,
            implementation
        )
    }

    @BeforeEach
    fun setup() {
        val class1 = createClassDef(
            "Lcom/test/Class1;",
            listOf(createMethodWithStrings("Lcom/test/Class1;", "method1", listOf("hello", "world")))
        )
        val class2 = createClassDef(
            "Lcom/test/Class2;",
            listOf(createMethodWithStrings("Lcom/test/Class2;", "method2", listOf("foo", "bar")))
        )
        val class3 = createClassDef(
            "Lcom/test/Class3;",
            listOf(createMethodWithStrings("Lcom/test/Class3;", "method3", listOf("hello", "bar")))  // Shares strings
        )
        val classNoStrings = createClassDef("Lcom/test/EmptyClass;")

        patchClasses = PatchClasses(setOf(class1, class2, class3, classNoStrings))
    }

    // ==================== classByOrNull tests ====================

    @Test
    fun `classByOrNull returns class when found by type`() {
        val result = patchClasses.classByOrNull("Lcom/test/Class1;")

        assertNotNull(result)
        assertEquals("Lcom/test/Class1;", result.type)
    }

    @Test
    fun `classByOrNull returns null when not found`() {
        val result = patchClasses.classByOrNull("Lcom/test/NonExistent;")

        assertNull(result)
    }

    @Test
    fun `classByOrNull with predicate returns matching class`() {
        val result = patchClasses.classByOrNull { it.type.contains("Class2") }

        assertNotNull(result)
        assertEquals("Lcom/test/Class2;", result.type)
    }

    @Test
    fun `classByOrNull with predicate returns null when no match`() {
        val result = patchClasses.classByOrNull { it.type.contains("NonExistent") }

        assertNull(result)
    }

    // ==================== classBy tests ====================

    @Test
    fun `classBy returns class when found`() {
        val result = patchClasses.classBy("Lcom/test/Class1;")

        assertEquals("Lcom/test/Class1;", result.type)
    }

    @Test
    fun `classBy throws PatchException when not found`() {
        assertThrows<PatchException> {
            patchClasses.classBy("Lcom/test/NonExistent;")
        }
    }

    @Test
    fun `classBy with predicate returns matching class`() {
        val result = patchClasses.classBy { it.type.contains("Class2") }

        assertEquals("Lcom/test/Class2;", result.type)
    }

    @Test
    fun `classBy with predicate throws PatchException when no match`() {
        assertThrows<PatchException> {
            patchClasses.classBy { it.type.contains("NonExistent") }
        }
    }

    // ==================== mutableClassByOrNull tests ====================

    @Test
    fun `mutableClassByOrNull returns mutable class when found`() {
        val result = patchClasses.mutableClassByOrNull("Lcom/test/Class1;")

        assertNotNull(result)
        assertEquals("Lcom/test/Class1;", result.type)
    }

    @Test
    fun `mutableClassByOrNull returns null when not found`() {
        val result = patchClasses.mutableClassByOrNull("Lcom/test/NonExistent;")

        assertNull(result)
    }

    @Test
    fun `mutableClassByOrNull with predicate returns mutable class when found`() {
        val result = patchClasses.mutableClassByOrNull { it.type.contains("Class2") }

        assertNotNull(result)
        assertEquals("Lcom/test/Class2;", result.type)
    }

    // ==================== mutableClassBy tests ====================

    @Test
    fun `mutableClassBy returns mutable class when found`() {
        val result = patchClasses.mutableClassBy("Lcom/test/Class1;")

        assertEquals("Lcom/test/Class1;", result.type)
    }

    @Test
    fun `mutableClassBy throws PatchException when not found`() {
        assertThrows<PatchException> {
            patchClasses.mutableClassBy("Lcom/test/NonExistent;")
        }
    }

    @Test
    fun `mutableClassBy from ClassDef returns mutable version`() {
        val classDef = patchClasses.classBy("Lcom/test/Class1;")
        val mutableClass = patchClasses.mutableClassBy(classDef)

        assertEquals("Lcom/test/Class1;", mutableClass.type)
    }

    @Test
    fun `mutableClassBy with predicate returns mutable class when found`() {
        val result = patchClasses.mutableClassBy { it.type.contains("Class2") }

        assertEquals("Lcom/test/Class2;", result.type)
    }

    @Test
    fun `mutableClassBy with predicate throws PatchException when no match`() {
        assertThrows<PatchException> {
            patchClasses.mutableClassBy { it.type.contains("NonExistent") }
        }
    }

    // ==================== forEach tests ====================

    @Test
    fun `forEach iterates over all classes`() {
        val classTypes = mutableListOf<String>()

        patchClasses.forEach { classDef ->
            classTypes.add(classDef.type)
        }

        assertEquals(4, classTypes.size)
        assertTrue(classTypes.contains("Lcom/test/Class1;"))
        assertTrue(classTypes.contains("Lcom/test/Class2;"))
        assertTrue(classTypes.contains("Lcom/test/Class3;"))
        assertTrue(classTypes.contains("Lcom/test/EmptyClass;"))
    }

    // ==================== addClass tests ====================

    @Test
    fun `addClass adds new class`() {
        val newClass = createClassDef("Lcom/test/NewClass;")

        assertNull(patchClasses.classByOrNull("Lcom/test/NewClass;"))

        patchClasses.addClass(newClass)

        assertNotNull(patchClasses.classByOrNull("Lcom/test/NewClass;"))
    }

    @Test
    fun `addClass replaces existing class`() {
        val replacementClass = ImmutableClassDef(
            "Lcom/test/Class1;",
            AccessFlags.PRIVATE.value,  // Different access flags
            "Ljava/lang/Object;",
            null, null, null, null, null
        )

        patchClasses.addClass(replacementClass)

        val result = patchClasses.classBy("Lcom/test/Class1;")
        assertEquals(AccessFlags.PRIVATE.value, result.accessFlags)
    }

    // ==================== String map tests ====================

    @Test
    fun `getClassesByStringMap returns map of strings to classes`() {
        val stringMap = patchClasses.getClassesByStringMap()

        assertTrue(stringMap.isNotEmpty())
        assertTrue(stringMap.containsKey("hello"))
        assertTrue(stringMap.containsKey("world"))
        assertTrue(stringMap.containsKey("foo"))
        assertTrue(stringMap.containsKey("bar"))
    }

    @Test
    fun `getClassFromOpcodeStringLiteral returns classes containing string`() {
        val classes = patchClasses.getClassesFromOpcodeStringLiteral("hello")

        assertNotNull(classes)
        assertEquals(2, classes.size)  // Class1 and Class3 both have "hello"
    }

    @Test
    fun `getClassFromOpcodeStringLiteral returns null for non-existent string`() {
        val classes = patchClasses.getClassesFromOpcodeStringLiteral("nonexistent")

        assertNull(classes)
    }

    @Test
    fun `getAllClassesWithStrings returns all classes that have strings`() {
        val classesWithStrings = patchClasses.getAllClassesWithStrings()

        assertEquals(3, classesWithStrings.size)  // Class1, Class2, Class3 (EmptyClass excluded)
    }

    // ==================== close tests ====================

    @Test
    fun `close clears class map`() {
        assertNotNull(patchClasses.classByOrNull("Lcom/test/Class1;"))

        patchClasses.close()

        assertNull(patchClasses.classByOrNull("Lcom/test/Class1;"))
    }

    @Test
    fun `closeStringMap clears only string map`() {
        // First build the string map
        patchClasses.getClassesByStringMap()

        // Close string map
        patchClasses.closeStringMap()

        // Classes should still be accessible
        assertNotNull(patchClasses.classByOrNull("Lcom/test/Class1;"))
    }

    // ==================== ClassDefWrapper tests ====================

    @Test
    fun `ClassDefWrapper getMutableClass returns mutable version`() {
        val classDef = createClassDef("Lcom/test/TestClass;")
        val wrapper = PatchClasses.ClassDefWrapper(classDef)

        val mutableClass = wrapper.getMutableClass()

        assertEquals("Lcom/test/TestClass;", mutableClass.type)
    }

    @Test
    fun `ClassDefWrapper getMutableClass returns same instance on repeated calls`() {
        val classDef = createClassDef("Lcom/test/TestClass;")
        val wrapper = PatchClasses.ClassDefWrapper(classDef)

        val mutableClass1 = wrapper.getMutableClass()
        val mutableClass2 = wrapper.getMutableClass()

        assertTrue(mutableClass1 === mutableClass2)
    }
}
