/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher.util.proxy.mutableTypes.encodedValue

import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableEncodedValue.Companion.toMutable
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableAnnotationEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableArrayEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableBooleanEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableByteEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableCharEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableDoubleEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableEnumEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableFieldEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableFloatEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableIntEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableLongEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableMethodEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableNullEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableShortEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableTypeEncodedValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object MutableEncodedValueTest {

    // ==================== MutableStringEncodedValue tests ====================

    @Test
    fun `MutableStringEncodedValue converts from StringEncodedValue`() {
        val original = ImmutableStringEncodedValue("test value")
        val mutable = original.toMutable()

        assertTrue(mutable is MutableStringEncodedValue)
        assertEquals("test value", (mutable as MutableStringEncodedValue).value)
    }

    @Test
    fun `MutableStringEncodedValue setValue changes value`() {
        val original = ImmutableStringEncodedValue("original")
        val mutable = MutableStringEncodedValue(original)

        mutable.setValue("modified")

        assertEquals("modified", mutable.value)
    }

    // ==================== MutableBooleanEncodedValue tests ====================

    @Test
    fun `MutableBooleanEncodedValue converts from BooleanEncodedValue`() {
        val original = ImmutableBooleanEncodedValue.forBoolean(true)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableBooleanEncodedValue)
        assertEquals(true, (mutable as MutableBooleanEncodedValue).value)
    }

    @Test
    fun `MutableBooleanEncodedValue setValue changes value`() {
        val original = ImmutableBooleanEncodedValue.forBoolean(true)
        val mutable = MutableBooleanEncodedValue(original)

        mutable.setValue(false)

        assertEquals(false, mutable.value)
    }

    // ==================== MutableIntEncodedValue tests ====================

    @Test
    fun `MutableIntEncodedValue converts from IntEncodedValue`() {
        val original = ImmutableIntEncodedValue(42)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableIntEncodedValue)
        assertEquals(42, (mutable as MutableIntEncodedValue).value)
    }

    @Test
    fun `MutableIntEncodedValue setValue changes value`() {
        val original = ImmutableIntEncodedValue(10)
        val mutable = MutableIntEncodedValue(original)

        mutable.setValue(20)

        assertEquals(20, mutable.value)
    }

    // ==================== MutableLongEncodedValue tests ====================

    @Test
    fun `MutableLongEncodedValue converts from LongEncodedValue`() {
        val original = ImmutableLongEncodedValue(9876543210L)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableLongEncodedValue)
        assertEquals(9876543210L, (mutable as MutableLongEncodedValue).value)
    }

    @Test
    fun `MutableLongEncodedValue setValue changes value`() {
        val original = ImmutableLongEncodedValue(100L)
        val mutable = MutableLongEncodedValue(original)

        mutable.setValue(200L)

        assertEquals(200L, mutable.value)
    }

    // ==================== MutableFloatEncodedValue tests ====================

    @Test
    fun `MutableFloatEncodedValue converts from FloatEncodedValue`() {
        val original = ImmutableFloatEncodedValue(3.14f)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableFloatEncodedValue)
        assertEquals(3.14f, (mutable as MutableFloatEncodedValue).value)
    }

    @Test
    fun `MutableFloatEncodedValue setValue changes value`() {
        val original = ImmutableFloatEncodedValue(1.0f)
        val mutable = MutableFloatEncodedValue(original)

        mutable.setValue(2.5f)

        assertEquals(2.5f, mutable.value)
    }

    // ==================== MutableDoubleEncodedValue tests ====================

    @Test
    fun `MutableDoubleEncodedValue converts from DoubleEncodedValue`() {
        val original = ImmutableDoubleEncodedValue(3.14159265359)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableDoubleEncodedValue)
        assertEquals(3.14159265359, (mutable as MutableDoubleEncodedValue).value)
    }

    @Test
    fun `MutableDoubleEncodedValue setValue changes value`() {
        val original = ImmutableDoubleEncodedValue(1.0)
        val mutable = MutableDoubleEncodedValue(original)

        mutable.setValue(2.718281828)

        assertEquals(2.718281828, mutable.value)
    }

    // ==================== MutableByteEncodedValue tests ====================

    @Test
    fun `MutableByteEncodedValue converts from ByteEncodedValue`() {
        val original = ImmutableByteEncodedValue(127.toByte())
        val mutable = original.toMutable()

        assertTrue(mutable is MutableByteEncodedValue)
        assertEquals(127.toByte(), (mutable as MutableByteEncodedValue).value)
    }

    @Test
    fun `MutableByteEncodedValue setValue changes value`() {
        val original = ImmutableByteEncodedValue(10.toByte())
        val mutable = MutableByteEncodedValue(original)

        mutable.setValue(20.toByte())

        assertEquals(20.toByte(), mutable.value)
    }

    // ==================== MutableShortEncodedValue tests ====================

    @Test
    fun `MutableShortEncodedValue converts from ShortEncodedValue`() {
        val original = ImmutableShortEncodedValue(32767.toShort())
        val mutable = original.toMutable()

        assertTrue(mutable is MutableShortEncodedValue)
        assertEquals(32767.toShort(), (mutable as MutableShortEncodedValue).value)
    }

    @Test
    fun `MutableShortEncodedValue setValue changes value`() {
        val original = ImmutableShortEncodedValue(100.toShort())
        val mutable = MutableShortEncodedValue(original)

        mutable.setValue(200.toShort())

        assertEquals(200.toShort(), mutable.value)
    }

    // ==================== MutableCharEncodedValue tests ====================

    @Test
    fun `MutableCharEncodedValue converts from CharEncodedValue`() {
        val original = ImmutableCharEncodedValue('A')
        val mutable = original.toMutable()

        assertTrue(mutable is MutableCharEncodedValue)
        assertEquals('A', (mutable as MutableCharEncodedValue).value)
    }

    @Test
    fun `MutableCharEncodedValue setValue changes value`() {
        val original = ImmutableCharEncodedValue('X')
        val mutable = MutableCharEncodedValue(original)

        mutable.setValue('Y')

        assertEquals('Y', mutable.value)
    }

    // ==================== MutableNullEncodedValue tests ====================

    @Test
    fun `MutableNullEncodedValue converts from NullEncodedValue`() {
        val original = ImmutableNullEncodedValue.INSTANCE
        val mutable = original.toMutable()

        assertTrue(mutable is MutableNullEncodedValue)
    }

    @Test
    fun `MutableNullEncodedValue can be created directly`() {
        val mutable = MutableNullEncodedValue()

        // NullEncodedValue has no value to test, just ensure it doesn't crash
        assertTrue(mutable is MutableEncodedValue)
    }

    // ==================== MutableTypeEncodedValue tests ====================

    @Test
    fun `MutableTypeEncodedValue converts from TypeEncodedValue`() {
        val original = ImmutableTypeEncodedValue("Ljava/lang/String;")
        val mutable = original.toMutable()

        assertTrue(mutable is MutableTypeEncodedValue)
        assertEquals("Ljava/lang/String;", (mutable as MutableTypeEncodedValue).value)
    }

    @Test
    fun `MutableTypeEncodedValue setValue changes value`() {
        val original = ImmutableTypeEncodedValue("Ljava/lang/String;")
        val mutable = MutableTypeEncodedValue(original)

        mutable.setValue("Ljava/lang/Integer;")

        assertEquals("Ljava/lang/Integer;", mutable.value)
    }

    // ==================== MutableEnumEncodedValue tests ====================

    @Test
    fun `MutableEnumEncodedValue converts from EnumEncodedValue`() {
        val original = ImmutableEnumEncodedValue(
            ImmutableFieldReference("Ljava/lang/Thread\$State;", "RUNNABLE", "Ljava/lang/Thread\$State;")
        )
        val mutable = original.toMutable()

        assertTrue(mutable is MutableEnumEncodedValue)
        assertEquals("RUNNABLE", (mutable as MutableEnumEncodedValue).value.name)
    }

    // ==================== MutableFieldEncodedValue tests ====================

    @Test
    fun `MutableFieldEncodedValue converts from FieldEncodedValue`() {
        val original = ImmutableFieldEncodedValue(
            ImmutableFieldReference("Lcom/test/TestClass;", "testField", "I")
        )
        val mutable = original.toMutable()

        assertTrue(mutable is MutableFieldEncodedValue)
        assertEquals("testField", (mutable as MutableFieldEncodedValue).value.name)
    }

    // ==================== MutableMethodEncodedValue tests ====================

    @Test
    fun `MutableMethodEncodedValue converts from MethodEncodedValue`() {
        val original = ImmutableMethodEncodedValue(
            ImmutableMethodReference("Lcom/test/TestClass;", "testMethod", emptyList(), "V")
        )
        val mutable = original.toMutable()

        assertTrue(mutable is MutableMethodEncodedValue)
        assertEquals("testMethod", (mutable as MutableMethodEncodedValue).value.name)
    }

    // ==================== MutableArrayEncodedValue tests ====================

    @Test
    fun `MutableArrayEncodedValue converts from ArrayEncodedValue`() {
        val elements = listOf(
            ImmutableStringEncodedValue("element1"),
            ImmutableStringEncodedValue("element2")
        )
        val original = ImmutableArrayEncodedValue(elements)
        val mutable = original.toMutable()

        assertTrue(mutable is MutableArrayEncodedValue)
        assertEquals(2, (mutable as MutableArrayEncodedValue).value.size)
    }

    @Test
    fun `MutableArrayEncodedValue value is mutable`() {
        val elements = listOf(
            ImmutableStringEncodedValue("element1")
        )
        val original = ImmutableArrayEncodedValue(elements)
        val mutable = MutableArrayEncodedValue(original)

        val values = mutable.value
        assertTrue(values is MutableList)
    }

    // ==================== MutableAnnotationEncodedValue tests ====================

    @Test
    fun `MutableAnnotationEncodedValue converts from AnnotationEncodedValue`() {
        val original = ImmutableAnnotationEncodedValue(
            "Ljava/lang/Deprecated;",
            emptySet()
        )
        val mutable = original.toMutable()

        assertTrue(mutable is MutableAnnotationEncodedValue)
        assertEquals("Ljava/lang/Deprecated;", (mutable as MutableAnnotationEncodedValue).type)
    }

    @Test
    fun `MutableAnnotationEncodedValue elements are mutable`() {
        val original = ImmutableAnnotationEncodedValue(
            "Lcom/test/Annotation;",
            emptySet()
        )
        val mutable = MutableAnnotationEncodedValue(original)

        val elements = mutable.elements
        assertTrue(elements is MutableSet)
    }
}
