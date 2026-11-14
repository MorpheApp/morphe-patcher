/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
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

    @Test
    fun `clearMatch evaluates the values of filter lambdas again`() {
        var value = "first"
        fun type() = "Lcom/test/$value;"

        val newInstance = newInstance(type = { type() })
        val instanceOf = instanceOf(type = { type() })
        val checkCast = checkCast(type = { type() })
        val methodCall = methodCall(definingClass = { type() })
        val fieldAccess = fieldAccess(definingClass = { type() })
        val string = string(string = { value })
        val literal = literal(literal = { value.length.toLong() })
        val nestedString = string(string = { value })
        val stringFilter = string(string = { value })

        val fingerprint = Fingerprint(
            filters = listOf(
                newInstance,
                instanceOf,
                checkCast,
                methodCall,
                fieldAccess,
                string,
                literal,
                anyInstruction(nestedString),
            ),
            strings = listOf(stringFilter),
        )

        fun assertValues(expected: String, expectedType: String) {
            assertEquals(expectedType, newInstance.typeValue)
            assertEquals(expectedType, instanceOf.typeValue)
            assertEquals(expectedType, checkCast.typeValue)
            assertEquals(expectedType, methodCall.definingClass)
            assertEquals(expectedType, fieldAccess.definingClass)
            assertEquals(expected, string.stringValue)
            assertEquals(expected.length.toLong(), literal.literalValue)
            assertEquals(expected, nestedString.stringValue)
            assertEquals(expected, stringFilter.stringValue)
        }

        assertValues("first", "Lcom/test/first;")

        value = "second"
        assertValues("first", "Lcom/test/first;") // Evaluated values are stored.

        fingerprint.clearMatch()
        assertValues("second", "Lcom/test/second;")
    }

    @Test
    fun `clearMatch clears the filter values of the class fingerprint`() {
        var value = "first"
        val filter = string(string = { value })
        val fingerprint = Fingerprint(
            classFingerprint = Fingerprint(filters = listOf(filter)),
            name = "testMethod",
        )

        assertEquals("first", filter.stringValue)

        value = "second"
        fingerprint.clearMatch()
        assertEquals("second", filter.stringValue)
    }

    @Test
    fun `clearFingerprints clears filter values every time`() {
        var value = "first"
        val filter = newInstance(type = { "Lcom/test/$value;" })
        val fingerprint = Fingerprint(filters = listOf(filter))

        repeat(3) { run ->
            value = "run$run"
            Fingerprint.clearFingerprints()
            assertEquals("Lcom/test/run$run;", filter.typeValue)
        }

        assertNotNull(fingerprint.filters)
    }

    @Test
    fun `Filter values known up front are unchanged after clearMatch`() {
        val filter = methodCall(definingClass = "Lcom/test/Class;", name = "method")
        val fingerprint = Fingerprint(filters = listOf(filter))

        fingerprint.clearMatch()

        assertEquals("Lcom/test/Class;", filter.definingClass)
        assertEquals("method", filter.name)
    }

    // ==================== Lambda value tests ====================

    @Test
    fun `Fingerprint values can be given by lambdas`() {
        val classFingerprint = Fingerprint(name = "toString")
        val fingerprints = listOf(
            Fingerprint(definingClass = { "Lcom/test/A;" }, parameters = listOf("I")),
            Fingerprint(superclass = { "Lcom/test/A;" }, name = "method"),
            Fingerprint(interfaces = { listOf("Lcom/test/A;") }, returnType = "V"),
            Fingerprint(returnType = { "Lcom/test/A;" }, parameters = listOf("I", "I")),
            Fingerprint(classFingerprint = classFingerprint, parameters = { listOf("Lcom/test/A;") }),
            Fingerprint(returnType = { "Lcom/test/A;" }, strings = listOf("a")),
            Fingerprint(returnType = { "Lcom/test/A;" }, strings = listOf(string("a"))),
        )

        assertEquals("Lcom/test/A;", fingerprints[0].definingClass)
        assertEquals(listOf("I"), fingerprints[0].parameters)
        assertEquals("Lcom/test/A;", fingerprints[1].superclass)
        assertEquals(listOf("Lcom/test/A;"), fingerprints[2].interfaces)
        assertEquals("V", fingerprints[2].returnType)
        assertEquals("Lcom/test/A;", fingerprints[3].returnType)
        assertEquals(listOf("Lcom/test/A;"), fingerprints[4].parameters)
        assertEquals(listOf("a"), fingerprints[5].strings)
        assertEquals(listOf("a"), fingerprints[6].strings)
    }

    @Test
    fun `Fingerprint values can be given by more than one lambda`() {
        val classFingerprint = Fingerprint(name = "toString")
        val fingerprint = Fingerprint(
            returnType = { "Lcom/test/A;" },
            parameters = { listOf("I", "I") },
        )
        assertEquals("Lcom/test/A;", fingerprint.returnType)
        assertEquals(listOf("I", "I"), fingerprint.parameters)

        val all = Fingerprint(
            definingClass = { "Lcom/test/A;" },
            superclass = { "Lcom/test/B;" },
            interfaces = { listOf("Lcom/test/C;") },
            name = "method",
            accessFlags = listOf(AccessFlags.PUBLIC),
            returnType = { "V" },
            parameters = { listOf("I") },
            filters = listOf(string("a")),
            strings = listOf("b"),
            custom = { _, _ -> true },
        )
        assertEquals("Lcom/test/A;", all.definingClass)
        assertEquals("Lcom/test/B;", all.superclass)
        assertEquals(listOf("Lcom/test/C;"), all.interfaces)
        assertEquals("V", all.returnType)
        assertEquals(listOf("I"), all.parameters)
        assertEquals(listOf("b"), all.strings)

        val withClassFingerprint = Fingerprint(
            classFingerprint = classFingerprint,
            superclass = { "Lcom/test/B;" },
            parameters = { listOf("I") },
            strings = listOf(string("b")),
        )
        assertEquals(classFingerprint, withClassFingerprint.classFingerprint)
        assertEquals("Lcom/test/B;", withClassFingerprint.superclass)
        assertEquals(listOf("I"), withClassFingerprint.parameters)
        assertEquals(listOf("b"), withClassFingerprint.strings)

        val interfacesAndReturnType = Fingerprint(
            interfaces = { listOf("Lcom/test/C;") },
            returnType = { "V" },
            isStatic = true,
        )
        assertEquals(listOf("Lcom/test/C;"), interfacesAndReturnType.interfaces)
        assertEquals("V", interfacesAndReturnType.returnType)
        assertEquals(true, interfacesAndReturnType.isStatic)
    }

    @Test
    fun `Fingerprint lambdas are evaluated when first needed and again after clearMatch`() {
        var value = "first"
        var evaluations = 0
        val fingerprint = Fingerprint(
            returnType = {
                evaluations++
                "Lcom/test/$value;"
            },
        )

        assertEquals(0, evaluations)
        assertEquals("Lcom/test/first;", fingerprint.returnType)
        assertEquals("Lcom/test/first;", fingerprint.returnType)
        assertEquals(1, evaluations)

        value = "second"
        fingerprint.clearMatch()
        assertEquals("Lcom/test/second;", fingerprint.returnType)
        assertEquals(2, evaluations)
    }

    @Test
    fun `Fingerprint lambda for a type can return a class`() {
        val classDef = ImmutableClassDef("Lcom/test/A;", 0, null, null, null, null, null, null)

        assertEquals("Lcom/test/A;", Fingerprint(returnType = { classDef }).returnType)
        assertEquals(listOf("Lcom/test/A;", "I"), Fingerprint(parameters = { listOf(classDef, "I") }).parameters)
    }

    @Test
    fun `Fingerprint lambda values are checked when evaluated`() {
        val fingerprint = Fingerprint(name = "testMethod", interfaces = { emptyList() })

        assertThrows<IllegalArgumentException> { fingerprint.interfaces }
        assertThrows<IllegalArgumentException> {
            Fingerprint(classFingerprint = Fingerprint(name = "toString"), definingClass = { "Lcom/test/A;" })
        }
    }

    @Test
    fun `Fingerprint constructor ignores void return type from a lambda`() {
        val fingerprint = Fingerprint(accessFlags = listOf(AccessFlags.CONSTRUCTOR), returnType = { "V" })

        assertNull(fingerprint.returnType)
    }

    @Test
    fun `toString does not evaluate lambdas`() {
        val fingerprint = Fingerprint(
            name = "testMethod",
            returnType = { error("Must not be evaluated") },
            parameters = listOf("I"),
        )

        assertEquals("Fingerprint(name=testMethod, returnType=lambda, parameters=[I])", fingerprint.toString())

        val evaluated = Fingerprint(name = "testMethod", returnType = { "V" })
        evaluated.returnType
        assertEquals("Fingerprint(name=testMethod, returnType=V)", evaluated.toString())
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
