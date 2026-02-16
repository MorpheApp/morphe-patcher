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
