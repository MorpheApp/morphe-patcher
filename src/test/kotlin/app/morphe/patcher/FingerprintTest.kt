/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object FingerprintTest {

    // ==================== Constructor validation tests ====================

    @Test
    fun `Fingerprint throws on empty fingerprint`() {
        assertThrows<IllegalArgumentException> {
            Fingerprint()
        }
    }

    @Test
    fun `Fingerprint with only name is valid`() {
        val fingerprint = Fingerprint(name = "testMethod")

        assertEquals("testMethod", fingerprint.name)
    }

    @Test
    fun `Fingerprint with only definingClass is valid`() {
        val fingerprint = Fingerprint(definingClass = "Lcom/test/TestClass;")

        assertEquals("Lcom/test/TestClass;", fingerprint.definingClass)
    }

    @Test
    fun `Fingerprint with only accessFlags is valid`() {
        val fingerprint = Fingerprint(accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC))

        assertNotNull(fingerprint.accessFlags)
        assertEquals(AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, fingerprint.accessFlags)
    }

    @Test
    fun `Fingerprint with only isStatic is valid`() {
        val staticFingerprint = Fingerprint(isStatic = true)
        val instanceFingerprint = Fingerprint(isStatic = false)

        assertEquals(true, staticFingerprint.isStatic)
        assertEquals(false, instanceFingerprint.isStatic)
        assertNull(staticFingerprint.accessFlags)
        assertNull(instanceFingerprint.accessFlags)
    }

    @Test
    fun `Fingerprint with isStatic and string filters is valid`() {
        val fingerprint = Fingerprint(
            definingClass = "Lcom/test/TestClass;",
            isStatic = false,
            strings = listOf(string("test")),
        )

        assertEquals(false, fingerprint.isStatic)
        assertEquals(listOf("test"), fingerprint.strings)
    }

    @Test
    fun `Fingerprint with only returnType is valid`() {
        val fingerprint = Fingerprint(returnType = "Ljava/lang/String;")

        assertEquals("Ljava/lang/String;", fingerprint.returnType)
    }

    @Test
    fun `Fingerprint with only parameters is valid`() {
        val fingerprint = Fingerprint(parameters = listOf("I", "Ljava/lang/String;"))

        assertEquals(2, fingerprint.parameters?.size)
    }

    @Test
    fun `Fingerprint with only filters is valid`() {
        val fingerprint = Fingerprint(filters = listOf(string("test")))

        assertEquals(1, fingerprint.filters?.size)
    }

    @Test
    fun `Fingerprint with only strings is valid`() {
        val fingerprint = Fingerprint(strings = listOf("test1", "test2"))

        assertEquals(2, fingerprint.strings?.size)
    }

    @Test
    fun `Fingerprint with only custom block is valid`() {
        val fingerprint = Fingerprint(custom = { _, _ -> true })

        assertNotNull(fingerprint.custom)
    }

    // ==================== Access flags tests ====================

    @Test
    fun `Fingerprint combines multiple access flags`() {
        val fingerprint = Fingerprint(
            accessFlags = listOf(
                AccessFlags.PUBLIC,
                AccessFlags.STATIC,
                AccessFlags.FINAL
            )
        )

        val expected = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        assertEquals(expected, fingerprint.accessFlags)
    }

    @Test
    fun `Fingerprint with single access flag`() {
        val fingerprint = Fingerprint(accessFlags = listOf(AccessFlags.PRIVATE))

        assertEquals(AccessFlags.PRIVATE.value, fingerprint.accessFlags)
    }

    @Test
    fun `Fingerprint with empty access flags list`() {
        val fingerprint = Fingerprint(name = "test", accessFlags = emptyList())

        assertEquals(0, fingerprint.accessFlags)
    }

    // ==================== Return type tests ====================

    @Test
    fun `Fingerprint constructor ignores void return type`() {
        val fingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.CONSTRUCTOR),
            returnType = "V"
        )

        // Constructor with void return type should have null returnType
        assertNull(fingerprint.returnType)
    }

    @Test
    fun `Fingerprint keeps non-void return type for constructor`() {
        // Edge case - constructor with non-void return (unusual but testing)
        val fingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.CONSTRUCTOR),
            returnType = "I"
        )

        assertEquals("I", fingerprint.returnType)
    }

    // ==================== clearMatch tests ====================

    @Test
    fun `clearMatch resets fingerprint match`() {
        val fingerprint = Fingerprint(name = "testMethod")

        // clearMatch should not throw even if never matched
        fingerprint.clearMatch()
    }

    // ==================== toString tests ====================

    @Test
    fun `Fingerprint toString returns meaningful string`() {
        val fingerprint = Fingerprint(
            definingClass = "Lcom/test/TestClass;",
            name = "testMethod",
            returnType = "V"
        )

        val str = fingerprint.toString()
        assertNotNull(str)
        // toString should include class info
    }

    private object NamedFingerprint : Fingerprint(name = "namedMethod")

    @Test
    fun `Fingerprint raw strings are converted to equals string filters`() {
        val fingerprint = Fingerprint(name = "testMethod", strings = listOf("test1", "test2"))

        assertEquals(listOf("test1", "test2"), fingerprint.strings)
        assertEquals(
            listOf(StringComparisonType.EQUALS, StringComparisonType.EQUALS),
            fingerprint.stringFilters?.map { it.comparison },
        )
    }

    @Test
    fun `Fingerprint with string filters is valid`() {
        val classFingerprint = Fingerprint(name = "toString")
        val fingerprints = listOf(
            Fingerprint(strings = listOf(string("test1"), string("test2", StringComparisonType.STARTS_WITH))),
            Fingerprint(name = "testMethod", strings = listOf(string("test1"), string("test2", StringComparisonType.STARTS_WITH))),
            Fingerprint(classFingerprint = classFingerprint, strings = listOf(string("test1"), string("test2", StringComparisonType.STARTS_WITH))),
            Fingerprint(definingClass = "Lcom/test/TestClass;", strings = listOf(string("test1"), string("test2", StringComparisonType.STARTS_WITH))),
        )

        fingerprints.forEach { fingerprint ->
            assertEquals(listOf("test1", "test2"), fingerprint.strings)
            assertEquals(
                listOf(StringComparisonType.EQUALS, StringComparisonType.STARTS_WITH),
                fingerprint.stringFilters?.map { it.comparison },
            )
        }
        assertEquals(classFingerprint, fingerprints[2].classFingerprint)
        assertEquals("Lcom/test/TestClass;", fingerprints[3].definingClass)
    }

    @Test
    fun `Fingerprint string filters cannot declare an instruction location`() {
        assertThrows<IllegalArgumentException> {
            Fingerprint(strings = listOf(string("test", InstructionLocation.MatchFirst())))
        }
    }

    @Test
    fun `toString describes the declared fields`() {
        val fingerprint = Fingerprint(
            definingClass = "Lcom/test/TestClass;",
            name = "testMethod",
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            returnType = "V",
            parameters = listOf("I", "Ljava/lang/String;"),
            filters = listOf(string("test")),
            strings = listOf("a"),
            custom = { _, _ -> true },
        )

        assertEquals(
            "Fingerprint(definingClass=Lcom/test/TestClass;, name=testMethod, accessFlags=public static, " +
                "returnType=V, parameters=[I, Ljava/lang/String;], filters=[StringFilter], strings=[a], custom)",
            fingerprint.toString(),
        )
    }

    @Test
    fun `toString describes isStatic`() {
        val fingerprint = Fingerprint(name = "testMethod", isStatic = false, returnType = "V")

        assertEquals("Fingerprint(name=testMethod, isStatic=false, returnType=V)", fingerprint.toString())
    }

    @Test
    fun `toString describes superclass and interfaces`() {
        val fingerprint = Fingerprint(
            superclass = "Lcom/test/Base;",
            interfaces = listOf("Lcom/test/A;", "Lcom/test/B;"),
            name = "testMethod",
        )

        assertEquals(
            "Fingerprint(superclass=Lcom/test/Base;, interfaces=[Lcom/test/A;, Lcom/test/B;], name=testMethod)",
            fingerprint.toString(),
        )
    }

    @Test
    fun `Fingerprint with only superclass or interfaces is valid`() {
        assertEquals("Lcom/test/Base;", Fingerprint(superclass = "Lcom/test/Base;").superclass)
        assertEquals(listOf("Lcom/test/A;"), Fingerprint(interfaces = listOf("Lcom/test/A;")).interfaces)
    }

    @Test
    fun `Fingerprint throws on empty interfaces`() {
        assertThrows<IllegalArgumentException> {
            Fingerprint(name = "testMethod", interfaces = emptyList())
        }
    }

    @Test
    fun `Fingerprint throws on empty filters`() {
        assertThrows<IllegalArgumentException> {
            Fingerprint(filters = emptyList())
        }
        assertThrows<IllegalArgumentException> {
            Fingerprint(name = "testMethod", filters = emptyList())
        }
    }

    @Test
    fun `Fingerprint throws on both accessFlags and isStatic`() {
        assertThrows<IllegalArgumentException> {
            Fingerprint(accessFlags = listOf(AccessFlags.PUBLIC), isStatic = true)
        }
    }

    @Test
    fun `Fingerprint 1x constructors are kept for binary compatibility`() {
        val list = List::class.java
        val string = String::class.java
        val function = Function2::class.java
        val custom: (Any, Any) -> Boolean = { _, _ -> true }

        val classFingerprint = Fingerprint(name = "classMethod")
        val withClassFingerprint = Fingerprint::class.java
            .getConstructor(Fingerprint::class.java, string, list, string, list, list, list, function)
            .newInstance(classFingerprint, "a", null, "V", null, null, listOf("s"), custom)
        assertEquals(classFingerprint, withClassFingerprint.classFingerprint)
        assertEquals("a", withClassFingerprint.name)
        assertEquals(listOf("s"), withClassFingerprint.strings)

        val withDefiningClass = Fingerprint::class.java
            .getConstructor(string, string, list, string, list, list, list, function)
            .newInstance("Lcom/test/TestClass;", "a", listOf(AccessFlags.PUBLIC), null, null, null, null, null)
        assertEquals("Lcom/test/TestClass;", withDefiningClass.definingClass)
        assertEquals(AccessFlags.PUBLIC.value, withDefiningClass.accessFlags)

        val withName = Fingerprint::class.java
            .getConstructor(string, list, string, list, list, list, function)
            .newInstance("a", null, null, listOf("I"), null, null, null)
        assertEquals(listOf("I"), withName.parameters)

        val withAccessFlags = Fingerprint::class.java
            .getConstructor(list, string, list, list, list, function)
            .newInstance(null, "V", null, null, null, null)
        assertEquals("V", withAccessFlags.returnType)

        listOf(withClassFingerprint, withDefiningClass, withName, withAccessFlags).forEach {
            assertNull(it.superclass)
            assertNull(it.interfaces)
            assertNull(it.isStatic)
        }
    }

    @Test
    fun `toString of a named fingerprint uses the class name`() {
        assertTrue(NamedFingerprint.toString().startsWith(NamedFingerprint::class.java.name + "@"))
    }

    // ==================== patchException tests ====================

    @Test
    fun `patchException returns PatchException with fingerprint info`() {
        val fingerprint = Fingerprint(name = "testMethod")

        val exception = fingerprint.patchException()

        assertNotNull(exception)
        assertTrue(exception.message?.contains("fingerprint") == true)
    }

    // ==================== Parameter type comparison tests ====================

    @Test
    fun `Fingerprint with partial parameter types`() {
        val fingerprint = Fingerprint(
            parameters = listOf("L", "/String;", "I")
        )

        // Parameters using partial matching
        assertEquals(3, fingerprint.parameters?.size)
    }

    // ==================== Edge cases ====================

    @Test
    fun `Fingerprint with all fields set`() {
        val fingerprint = Fingerprint(
            definingClass = "Lcom/test/TestClass;",
            name = "testMethod",
            accessFlags = listOf(AccessFlags.PUBLIC),
            returnType = "Ljava/lang/String;",
            parameters = listOf("I", "Z"),
            filters = listOf(string("test")),
            strings = listOf("string1"),
            custom = { _, _ -> true }
        )

        assertNotNull(fingerprint.definingClass)
        assertNotNull(fingerprint.name)
        assertNotNull(fingerprint.accessFlags)
        assertNotNull(fingerprint.returnType)
        assertNotNull(fingerprint.parameters)
        assertNotNull(fingerprint.filters)
        assertNotNull(fingerprint.strings)
        assertNotNull(fingerprint.custom)
    }

    @Test
    fun `Fingerprint with partial class type matching`() {
        val fingerprint = Fingerprint(definingClass = "Ljava/lang")  // STARTS_WITH

        assertEquals("Ljava/lang", fingerprint.definingClass)
    }

    @Test
    fun `Fingerprint with ends with class type matching`() {
        val fingerprint = Fingerprint(definingClass = "/String;")  // ENDS_WITH

        assertEquals("/String;", fingerprint.definingClass)
    }
}

// ==================== FingerprintBuilder tests ====================

@Suppress("DEPRECATION")
internal object FingerprintBuilderTest {

    @Test
    fun `FingerprintBuilder accessFlags can only be set once`() {
        val builder = FingerprintBuilder()
        builder.accessFlags(AccessFlags.PUBLIC)

        assertThrows<IllegalArgumentException> {
            builder.accessFlags(AccessFlags.PRIVATE)
        }
    }

    @Test
    fun `FingerprintBuilder returns can only be set once`() {
        val builder = FingerprintBuilder()
        builder.returns("V")

        assertThrows<IllegalArgumentException> {
            builder.returns("I")
        }
    }

    @Test
    fun `FingerprintBuilder parameters can only be set once`() {
        val builder = FingerprintBuilder()
        builder.parameters("I", "Z")

        assertThrows<IllegalArgumentException> {
            builder.parameters("Ljava/lang/String;")
        }
    }

    @Test
    fun `FingerprintBuilder strings can only be set once`() {
        val builder = FingerprintBuilder()
        builder.strings("test1")

        assertThrows<IllegalArgumentException> {
            builder.strings("test2")
        }
    }

    @Test
    fun `FingerprintBuilder custom can only be set once`() {
        val builder = FingerprintBuilder()
        builder.custom { _, _ -> true }

        assertThrows<IllegalArgumentException> {
            builder.custom { _, _ -> false }
        }
    }

    @Test
    fun `FingerprintBuilder opcodes throws on empty`() {
        val builder = FingerprintBuilder()

        assertThrows<IllegalArgumentException> {
            builder.opcodes()
        }
    }

    @Test
    fun `FingerprintBuilder instructions throws on empty`() {
        val builder = FingerprintBuilder()

        assertThrows<IllegalArgumentException> {
            builder.instructions()
        }
    }

    @Test
    fun `FingerprintBuilder instruction filters can only be set once`() {
        val builder = FingerprintBuilder()
        builder.instructions(string("test"))

        assertThrows<IllegalArgumentException> {
            builder.opcodes(com.android.tools.smali.dexlib2.Opcode.NOP)
        }
    }

    @Test
    fun `fingerprint DSL creates valid Fingerprint`() {
        val fp = fingerprint {
            accessFlags(AccessFlags.PUBLIC)
            returns("V")
            parameters("I", "Z")
            strings("test")
            custom { _, _ -> true }
        }

        assertNotNull(fp.accessFlags)
        assertEquals("V", fp.returnType)
        assertEquals(2, fp.parameters?.size)
        assertEquals(1, fp.strings?.size)
        assertNotNull(fp.custom)
    }
}
