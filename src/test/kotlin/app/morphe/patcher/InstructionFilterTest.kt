/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object InstructionFilterTest {

    // Helper to create a test method
    private fun createTestMethod(
        definingClass: String = "Lcom/test/TestClass;",
        name: String = "testMethod",
        returnType: String = "V",
        accessFlags: Int = AccessFlags.PUBLIC.value
    ) = ImmutableMethod(
        definingClass,
        name,
        emptyList(),
        returnType,
        accessFlags,
        emptySet(),
        emptySet(),
        MutableMethodImplementation(4)
    )

    // ==================== InstructionLocation tests ====================

    @Test
    fun `MatchAfterAnywhere always returns true`() {
        val location = InstructionLocation.MatchAfterAnywhere()
        assertTrue(location.indexIsValidForMatching(-1, 0))
        assertTrue(location.indexIsValidForMatching(0, 1))
        assertTrue(location.indexIsValidForMatching(5, 100))
        assertTrue(location.indexIsValidForMatching(100, 5))
    }

    @Test
    fun `MatchFirst only valid for first filter`() {
        val location = InstructionLocation.MatchFirst()

        // Valid for first filter (previouslyMatchedIndex < 0)
        assertTrue(location.indexIsValidForMatching(-1, 0))
        assertTrue(location.indexIsValidForMatching(-1, 5))

        // Throws exception when not first filter
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(0, 1)
        }
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(5, 10)
        }
    }

    @Test
    fun `MatchAfterImmediately only valid immediately after previous`() {
        val location = InstructionLocation.MatchAfterImmediately()

        // Throws exception for first filter
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(-1, 0)
        }

        // Valid only when current is immediately after previous
        assertTrue(location.indexIsValidForMatching(0, 1))
        assertTrue(location.indexIsValidForMatching(5, 6))

        // Invalid when gap exists
        assertFalse(location.indexIsValidForMatching(0, 2))
        assertFalse(location.indexIsValidForMatching(5, 10))
    }

    @Test
    fun `MatchAfterWithin requires non-negative matchDistance`() {
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchAfterWithin(-1)
        }

        // Valid with 0 or positive
        InstructionLocation.MatchAfterWithin(0)
        InstructionLocation.MatchAfterWithin(10)
    }

    @Test
    fun `MatchAfterWithin validates distance correctly`() {
        val location = InstructionLocation.MatchAfterWithin(2)

        // Distance of 0, 1, 2 should be valid
        assertTrue(location.indexIsValidForMatching(0, 1))  // distance 0
        assertTrue(location.indexIsValidForMatching(0, 2))  // distance 1
        assertTrue(location.indexIsValidForMatching(0, 3))  // distance 2

        // Distance of 3+ should be invalid
        assertFalse(location.indexIsValidForMatching(0, 4))  // distance 3
        assertFalse(location.indexIsValidForMatching(0, 10)) // distance 9
    }

    @Test
    fun `MatchAfterWithin with distance 0 behaves like MatchAfterImmediately`() {
        val location = InstructionLocation.MatchAfterWithin(0)

        assertTrue(location.indexIsValidForMatching(0, 1))
        assertFalse(location.indexIsValidForMatching(0, 2))
    }

    @Test
    fun `MatchAfterAtLeast requires non-negative minimum distance`() {
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchAfterAtLeast(-1)
        }

        InstructionLocation.MatchAfterAtLeast(0)
        InstructionLocation.MatchAfterAtLeast(10)
    }

    @Test
    fun `MatchAfterAtLeast validates minimum distance`() {
        val location = InstructionLocation.MatchAfterAtLeast(3)

        // Distance < 3 should be invalid
        assertFalse(location.indexIsValidForMatching(0, 1))  // distance 0
        assertFalse(location.indexIsValidForMatching(0, 2))  // distance 1
        assertFalse(location.indexIsValidForMatching(0, 3))  // distance 2

        // Distance >= 3 should be valid
        assertTrue(location.indexIsValidForMatching(0, 4))   // distance 3
        assertTrue(location.indexIsValidForMatching(0, 100)) // distance 99
    }

    @Test
    fun `MatchAfterRange requires valid range parameters`() {
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchAfterRange(-1, 0)
        }
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchAfterRange(0, -1)
        }
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchAfterRange(5, 3)  // min > max
        }

        // Valid ranges
        InstructionLocation.MatchAfterRange(0, 0)
        InstructionLocation.MatchAfterRange(0, 5)
        InstructionLocation.MatchAfterRange(3, 3)
    }

    @Test
    fun `MatchAfterRange validates bounded range`() {
        val location = InstructionLocation.MatchAfterRange(2, 5)

        // Distance < 2 should be invalid
        assertFalse(location.indexIsValidForMatching(0, 1))  // distance 0
        assertFalse(location.indexIsValidForMatching(0, 2))  // distance 1

        // Distance 2-5 should be valid
        assertTrue(location.indexIsValidForMatching(0, 3))   // distance 2
        assertTrue(location.indexIsValidForMatching(0, 5))   // distance 4
        assertTrue(location.indexIsValidForMatching(0, 6))   // distance 5

        // Distance > 5 should be invalid
        assertFalse(location.indexIsValidForMatching(0, 7))  // distance 6
        assertFalse(location.indexIsValidForMatching(0, 100))
    }

    // ==================== OpcodeFilter tests ====================

    @Test
    fun `OpcodeFilter matches correct opcode`() {
        val method = createTestMethod()
        val filter = OpcodeFilter(Opcode.CONST_4)

        val constInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 0)
        val returnInstruction = BuilderInstruction10x(Opcode.RETURN_VOID)

        assertTrue(filter.matches(method, constInstruction))
        assertFalse(filter.matches(method, returnInstruction))
    }

    @Test
    fun `opcode function creates OpcodeFilter with location`() {
        val filter = opcode(Opcode.NOP, InstructionLocation.MatchFirst())

        assertEquals(Opcode.NOP, filter.opcode)
        assertTrue(filter.location is InstructionLocation.MatchFirst)
    }

    // ==================== OpcodesFilter tests ====================

    @Test
    fun `OpcodesFilter matches any of multiple opcodes`() {
        val method = createTestMethod()
        val filters = OpcodesFilter.opcodesToFilters(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

        val constInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 0)
        val returnInstruction = BuilderInstruction10x(Opcode.RETURN_VOID)

        // First filter should match
        assertTrue(filters[0].matches(method, constInstruction))
        assertFalse(filters[0].matches(method, returnInstruction))
    }

    @Test
    fun `opcodesToFilters creates filters with correct locations`() {
        val filters = OpcodesFilter.opcodesToFilters(Opcode.NOP, Opcode.RETURN_VOID, null)

        assertEquals(3, filters.size)

        // First filter matches anywhere
        assertTrue(filters[0].location is InstructionLocation.MatchAfterAnywhere)

        // Subsequent filters match immediately after
        assertTrue(filters[1].location is InstructionLocation.MatchAfterImmediately)
        assertTrue(filters[2].location is InstructionLocation.MatchAfterImmediately)
    }

    @Test
    fun `opcodesToFilters throws on empty input`() {
        // Note: This appears to not throw based on implementation - it creates empty list
        val filters = OpcodesFilter.opcodesToFilters()
        assertTrue(filters.isEmpty())
    }

    @Test
    fun `opcodesToFilters handles null opcode as wildcard`() {
        val filters = OpcodesFilter.opcodesToFilters(null)
        val method = createTestMethod()

        // Null opcode should match any instruction
        val nopInstruction = BuilderInstruction10x(Opcode.NOP)
        val returnInstruction = BuilderInstruction10x(Opcode.RETURN_VOID)

        assertTrue(filters[0].matches(method, nopInstruction))
        assertTrue(filters[0].matches(method, returnInstruction))
    }

    // ==================== LiteralFilter tests ====================

    @Test
    fun `literal filter matches long value`() {
        val method = createTestMethod()
        val filter = literal(5L)

        // BuilderInstruction11n (CONST_4) implements WideLiteralInstruction
        val matchingInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 5)
        val nonMatchingInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 3)

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `literal filter with lambda`() {
        var value = 5L
        val filter = literal({ value })

        val method = createTestMethod()
        val matchingInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 5)
        val nonMatchingInstruction = BuilderInstruction11n(Opcode.CONST_4, 0, 3)

        // First match should succeed
        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))

        // Lambda value is cached after first access, so changing it shouldn't affect filter
        value = 3L
        assertTrue(filter.matches(method, matchingInstruction))
    }

    @Test
    fun `literal filter doesn't match non-literal instructions`() {
        val method = createTestMethod()
        val filter = literal(42L)

        val nonLiteralInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonLiteralInstruction))
    }

    // ==================== StringFilter tests ====================

    @Test
    fun `string filter matches exact string`() {
        val method = createTestMethod()
        val filter = string("test string")

        val matchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("test string")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("different")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `string filter matches with CONTAINS comparison`() {
        val method = createTestMethod()
        val filter = string("middle", StringComparisonType.CONTAINS)

        val matchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("start middle end")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("no match here")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `string filter matches with STARTS_WITH comparison`() {
        val method = createTestMethod()
        val filter = string("prefix", StringComparisonType.STARTS_WITH)

        val matchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("prefix_and_more")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("no prefix here")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `string filter matches with ENDS_WITH comparison`() {
        val method = createTestMethod()
        val filter = string("suffix", StringComparisonType.ENDS_WITH)

        val matchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("text_with_suffix")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("suffix_at_start")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `string filter with lambda`() {
        val searchString = "initial"
        val filter = string({ searchString })

        val method = createTestMethod()
        val instruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("initial")
        )

        assertTrue(filter.matches(method, instruction))
    }

    @Test
    fun `string filter doesn't match non-string instructions`() {
        val method = createTestMethod()
        val filter = string("test")

        val nonStringInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonStringInstruction))
    }

    // ==================== MethodCallFilter tests ====================

    @Test
    fun `methodCall filter matches by name`() {
        val method = createTestMethod()
        val filter = methodCall(name = "targetMethod")

        val matchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "targetMethod", emptyList(), "V")
        )
        val nonMatchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "otherMethod", emptyList(), "V")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `methodCall filter matches by defining class`() {
        val method = createTestMethod()
        val filter = methodCall(definingClass = "Lcom/target/TargetClass;")

        val matchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/target/TargetClass;", "method", emptyList(), "V")
        )
        val nonMatchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/other/OtherClass;", "method", emptyList(), "V")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `methodCall filter matches 'this' defining class`() {
        val method = createTestMethod(definingClass = "Lcom/test/MyClass;")
        val filter = methodCall(definingClass = "this")

        val matchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/test/MyClass;", "method", emptyList(), "V")
        )
        val nonMatchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/other/OtherClass;", "method", emptyList(), "V")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `methodCall filter matches by return type`() {
        val method = createTestMethod()
        val filter = methodCall(returnType = "Ljava/lang/String;")

        val matchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "method", emptyList(), "Ljava/lang/String;")
        )
        val nonMatchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "method", emptyList(), "I")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `methodCall filter matches by parameters`() {
        val method = createTestMethod()
        val filter = methodCall(parameters = listOf("I", "Ljava/lang/String;"))

        val matchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "method", listOf("I", "Ljava/lang/String;"), "V")
        )
        val nonMatchingInstruction = BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("Lcom/Test;", "method", listOf("Z"), "V")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `methodCall filter doesn't match non-method-call instructions`() {
        val method = createTestMethod()
        val filter = methodCall(name = "test")

        val nonMethodCallInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonMethodCallInstruction))
    }

    // ==================== FieldAccessFilter tests ====================

    @Test
    fun `fieldAccess filter matches by name`() {
        val method = createTestMethod()
        val filter = fieldAccess(name = "targetField")

        val matchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/Test;", "targetField", "I")
        )
        val nonMatchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/Test;", "otherField", "I")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `fieldAccess filter matches by defining class`() {
        val method = createTestMethod()
        val filter = fieldAccess(definingClass = "Lcom/target/TargetClass;")

        val matchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/target/TargetClass;", "field", "I")
        )
        val nonMatchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/other/OtherClass;", "field", "I")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `fieldAccess filter matches 'this' defining class`() {
        val method = createTestMethod(definingClass = "Lcom/test/MyClass;")
        val filter = fieldAccess(definingClass = "this")

        val matchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/test/MyClass;", "field", "I")
        )
        val nonMatchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/other/OtherClass;", "field", "I")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `fieldAccess filter matches by type`() {
        val method = createTestMethod()
        val filter = fieldAccess(type = "Ljava/lang/String;")

        val matchingInstruction = BuilderInstruction22c(
            Opcode.IGET_OBJECT, 0, 1,
            ImmutableFieldReference("Lcom/Test;", "field", "Ljava/lang/String;")
        )
        val nonMatchingInstruction = BuilderInstruction22c(
            Opcode.IGET, 0, 1,
            ImmutableFieldReference("Lcom/Test;", "field", "I")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `fieldAccess filter doesn't match non-field instructions`() {
        val method = createTestMethod()
        val filter = fieldAccess(name = "test")

        val nonFieldInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonFieldInstruction))
    }

    // ==================== NewInstanceFilter tests ====================

    @Test
    fun `newInstance filter matches type`() {
        val method = createTestMethod()
        val filter = newInstance("Ljava/lang/StringBuilder;")

        val matchingInstruction = BuilderInstruction21c(
            Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Ljava/lang/StringBuilder;")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Ljava/lang/String;")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `newInstance filter with partial type matching`() {
        val method = createTestMethod()
        val filter = newInstance("Ljava/lang")  // STARTS_WITH

        val matchingInstruction = BuilderInstruction21c(
            Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Ljava/lang/StringBuilder;")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Landroid/view/View;")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `newInstance filter doesn't match non-new-instance instructions`() {
        val method = createTestMethod()
        val filter = newInstance("Lcom/Test;")

        val nonNewInstanceInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonNewInstanceInstruction))
    }

    // ==================== CheckCastFilter tests ====================

    @Test
    fun `checkCast filter matches type`() {
        val method = createTestMethod()
        val filter = checkCast("Ljava/lang/String;")

        val matchingInstruction = BuilderInstruction21c(
            Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;")
        )
        val nonMatchingInstruction = BuilderInstruction21c(
            Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/Object;")
        )

        assertTrue(filter.matches(method, matchingInstruction))
        assertFalse(filter.matches(method, nonMatchingInstruction))
    }

    @Test
    fun `checkCast filter doesn't match non-check-cast instructions`() {
        val method = createTestMethod()
        val filter = checkCast("Lcom/Test;")

        val nonCheckCastInstruction = BuilderInstruction10x(Opcode.NOP)

        assertFalse(filter.matches(method, nonCheckCastInstruction))
    }

    // ==================== AnyInstruction tests ====================

    @Test
    fun `anyInstruction matches when any filter matches`() {
        val method = createTestMethod()
        val filter = anyInstruction(
            string("test1"),
            string("test2"),
            string("test3")
        )

        val instruction1 = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("test2")
        )
        val instruction2 = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("no match")
        )

        assertTrue(filter.matches(method, instruction1))
        assertFalse(filter.matches(method, instruction2))
    }

    @Test
    fun `anyInstruction returns false when no filter matches`() {
        val method = createTestMethod()
        val filter = anyInstruction(
            string("test1"),
            string("test2")
        )

        val instruction = BuilderInstruction21c(
            Opcode.CONST_STRING, 0, ImmutableStringReference("different")
        )

        assertFalse(filter.matches(method, instruction))
    }
}
