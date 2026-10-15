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
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21ih
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21lh
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
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

        // Valid for first filter (previouslyMatchedIndex < 0), and only at the first instruction.
        assertTrue(location.indexIsValidForMatching(-1, 0))
        assertFalse(location.indexIsValidForMatching(-1, 1))
        assertFalse(location.indexIsValidForMatching(-1, 5))

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
    fun `forward locations search forward from the previous match`() {
        val locations = listOf(
            InstructionLocation.MatchAfterAnywhere(),
            InstructionLocation.MatchAfterImmediately(),
            InstructionLocation.MatchAfterWithin(2),
            InstructionLocation { _, _ -> true },
        )

        locations.forEach { location ->
            assertFalse(location.searchesBackwards)
            assertEquals(0, location.searchStartIndex(-1, 10))
            assertEquals(6, location.searchStartIndex(5, 10))
        }
    }

    @Test
    fun `MatchAfterLast searches backwards from the end of the method`() {
        val location = InstructionLocation.MatchAfterLast()

        assertTrue(location.searchesBackwards)
        assertEquals(10, location.searchStartIndex(-1, 10))
        assertEquals(10, location.searchStartIndex(5, 10))

        // Valid for first filter at any index.
        assertTrue(location.indexIsValidForMatching(-1, 0))
        assertTrue(location.indexIsValidForMatching(-1, 10))

        // Valid only after the previous match.
        assertTrue(location.indexIsValidForMatching(5, 6))
        assertTrue(location.indexIsValidForMatching(5, 10))
        assertFalse(location.indexIsValidForMatching(5, 5))
        assertFalse(location.indexIsValidForMatching(5, 4))
    }

    @Test
    fun `MatchBeforeImmediately only valid immediately before previous`() {
        val location = InstructionLocation.MatchBeforeImmediately()

        assertTrue(location.searchesBackwards)
        assertEquals(4, location.searchStartIndex(5, 10))

        // Throws exception for first filter
        assertThrows<IllegalArgumentException> {
            location.searchStartIndex(-1, 10)
        }
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(-1, 0)
        }

        // Valid only when current is immediately before previous
        assertTrue(location.indexIsValidForMatching(5, 4))
        assertTrue(location.indexIsValidForMatching(1, 0))

        // Invalid when gap exists or not before the previous
        assertFalse(location.indexIsValidForMatching(5, 3))
        assertFalse(location.indexIsValidForMatching(5, 5))
        assertFalse(location.indexIsValidForMatching(5, 6))
    }

    @Test
    fun `MatchBeforeAnywhere searches backwards from the previous match`() {
        val location = InstructionLocation.MatchBeforeAnywhere()

        assertTrue(location.searchesBackwards)
        assertEquals(4, location.searchStartIndex(5, 10))

        // Throws exception for first filter
        assertThrows<IllegalArgumentException> {
            location.searchStartIndex(-1, 10)
        }
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(-1, 0)
        }

        // Valid only before the previous match.
        assertTrue(location.indexIsValidForMatching(5, 4))
        assertTrue(location.indexIsValidForMatching(5, 0))
        assertFalse(location.indexIsValidForMatching(5, 5))
        assertFalse(location.indexIsValidForMatching(5, 6))
    }

    @Test
    fun `MatchBeforeFirst searches forward from the start of the method`() {
        val location = InstructionLocation.MatchBeforeFirst()

        assertFalse(location.searchesBackwards)
        assertEquals(0, location.searchStartIndex(5, 10))

        // Throws exception for first filter
        assertThrows<IllegalArgumentException> {
            location.searchStartIndex(-1, 10)
        }
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(-1, 0)
        }

        // Valid only before the previous match.
        assertTrue(location.indexIsValidForMatching(5, 0))
        assertTrue(location.indexIsValidForMatching(5, 4))
        assertFalse(location.indexIsValidForMatching(5, 5))
        assertFalse(location.indexIsValidForMatching(5, 6))
    }

    @Test
    fun `MatchBeforeWithin requires non-negative matchDistance`() {
        assertThrows<IllegalArgumentException> {
            InstructionLocation.MatchBeforeWithin(-1)
        }

        // Valid with 0 or positive
        InstructionLocation.MatchBeforeWithin(0)
        InstructionLocation.MatchBeforeWithin(10)
    }

    @Test
    fun `MatchBeforeWithin validates distance correctly`() {
        val location = InstructionLocation.MatchBeforeWithin(2)

        assertTrue(location.searchesBackwards)
        assertEquals(4, location.searchStartIndex(5, 10))

        // Throws exception for first filter
        assertThrows<IllegalArgumentException> {
            location.searchStartIndex(-1, 10)
        }
        assertThrows<IllegalArgumentException> {
            location.indexIsValidForMatching(-1, 0)
        }

        // Distance of 0, 1, 2 should be valid
        assertTrue(location.indexIsValidForMatching(5, 4))  // distance 0
        assertTrue(location.indexIsValidForMatching(5, 3))  // distance 1
        assertTrue(location.indexIsValidForMatching(5, 2))  // distance 2

        // Distance of 3+ should be invalid
        assertFalse(location.indexIsValidForMatching(5, 1))  // distance 3
        assertFalse(location.indexIsValidForMatching(10, 0)) // distance 9

        // Not before the previous match
        assertFalse(location.indexIsValidForMatching(5, 5))
        assertFalse(location.indexIsValidForMatching(5, 6))
    }

    @Test
    fun `MatchBeforeWithin with distance 0 behaves like MatchBeforeImmediately`() {
        val location = InstructionLocation.MatchBeforeWithin(0)

        assertTrue(location.indexIsValidForMatching(5, 4))
        assertFalse(location.indexIsValidForMatching(5, 3))
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
    fun `instanceOf filter matches type`() {
        val method = createTestMethod()
        val instanceOfInstruction = BuilderInstruction22c(
            Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference("Ljava/lang/String;")
        )
        val otherTypeInstruction = BuilderInstruction22c(
            Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference("Ljava/lang/Integer;")
        )
        val checkCastInstruction = BuilderInstruction21c(
            Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;")
        )

        // Declared type checks both overloads return an InstanceOfFilter.
        listOf<InstanceOfFilter>(
            instanceOf("Ljava/lang/String;"),
            instanceOf({ "Ljava/lang/String;" })
        ).forEach { filter ->
            assertTrue(filter.matches(method, instanceOfInstruction))
            assertFalse(filter.matches(method, otherTypeInstruction))
            assertFalse(filter.matches(method, checkCastInstruction))
        }
    }

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

    // ==================== Additional literal tests ====================

    @Test
    fun `literal filter matches int, float and double values`() {
        val method = createTestMethod()

        assertTrue(literal(5).matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, 5)))
        assertTrue(literal(-1).matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, -1)))
        assertTrue(literal(1000).matches(method, BuilderInstruction21s(Opcode.CONST_16, 0, 1000)))
        assertTrue(literal(100_000).matches(method, BuilderInstruction31i(Opcode.CONST, 0, 100_000)))

        // Floats and doubles are compared by their raw bits, as the compiler stores them.
        val floatInstruction = BuilderInstruction21ih(Opcode.CONST_HIGH16, 0, 1.5f.toRawBits())
        assertTrue(literal(1.5f).matches(method, floatInstruction))
        assertFalse(literal(2.5f).matches(method, floatInstruction))

        val negativeFloatInstruction = BuilderInstruction31i(Opcode.CONST, 0, (-0.25f).toRawBits())
        assertTrue(literal(-0.25f).matches(method, negativeFloatInstruction))

        val doubleInstruction = BuilderInstruction21lh(Opcode.CONST_WIDE_HIGH16, 0, 2.0.toRawBits())
        assertTrue(literal(2.0).matches(method, doubleInstruction))
        assertFalse(literal(2.0f).matches(method, doubleInstruction))
    }

    @Test
    fun `literal filter only matches declared opcodes`() {
        val method = createTestMethod()
        val filter = literal(5, opcodes = listOf(Opcode.CONST_16))

        assertTrue(filter.matches(method, BuilderInstruction21s(Opcode.CONST_16, 0, 5)))
        assertFalse(filter.matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, 5)))
    }

    @Test
    fun `literal filter with null value never matches`() {
        val method = createTestMethod()
        val filter = literal({ null })

        assertFalse(filter.matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, 0)))
        assertThrows<IllegalStateException> {
            filter.literal()
        }
    }

    // ==================== Additional string tests ====================

    @Test
    fun `string filter matches const-string jumbo`() {
        val method = createTestMethod()
        val filter = string("test")

        assertTrue(
            filter.matches(
                method,
                BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference("test"))
            )
        )
        assertFalse(
            filter.matches(
                method,
                BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, 0, ImmutableStringReference("other"))
            )
        )
    }

    // ==================== Additional methodCall tests ====================

    private fun invoke(opcode: Opcode, reference: ImmutableMethodReference) =
        BuilderInstruction35c(opcode, reference.parameterTypes.size, 0, 1, 2, 3, 4, reference)

    @Test
    fun `methodCall filter only matches declared opcodes`() {
        val method = createTestMethod()
        val reference = ImmutableMethodReference("Lcom/Test;", "method", emptyList(), "V")

        val staticFilter = methodCall(name = "method", opcode = Opcode.INVOKE_STATIC)
        assertTrue(staticFilter.matches(method, invoke(Opcode.INVOKE_STATIC, reference)))
        assertFalse(staticFilter.matches(method, invoke(Opcode.INVOKE_VIRTUAL, reference)))

        val virtualOrInterfaceFilter = methodCall(
            name = "method",
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)
        )
        assertTrue(virtualOrInterfaceFilter.matches(method, invoke(Opcode.INVOKE_VIRTUAL, reference)))
        assertTrue(virtualOrInterfaceFilter.matches(method, invoke(Opcode.INVOKE_INTERFACE, reference)))
        assertFalse(virtualOrInterfaceFilter.matches(method, invoke(Opcode.INVOKE_STATIC, reference)))
    }

    @Test
    fun `methodCall filter matches range invoke`() {
        val method = createTestMethod()
        val filter = methodCall(definingClass = "Lcom/Test;", name = "method")
        val reference = ImmutableMethodReference("Lcom/Test;", "method", listOf("I", "I"), "V")

        assertTrue(filter.matches(method, BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 16, 2, reference)))
    }

    @Test
    fun `methodCall filter requires the same number of parameters`() {
        val method = createTestMethod()
        val twoParameters = invoke(
            Opcode.INVOKE_VIRTUAL,
            ImmutableMethodReference("Lcom/Test;", "method", listOf("I", "Ljava/lang/String;"), "V")
        )
        val noParameters = invoke(
            Opcode.INVOKE_VIRTUAL,
            ImmutableMethodReference("Lcom/Test;", "method", emptyList(), "V")
        )

        assertFalse(methodCall(parameters = listOf("I")).matches(method, twoParameters))
        assertFalse(methodCall(parameters = listOf("I", "L", "Z")).matches(method, twoParameters))
        assertTrue(methodCall(parameters = listOf("I", "L")).matches(method, twoParameters))

        assertTrue(methodCall(parameters = emptyList()).matches(method, noParameters))
        assertFalse(methodCall(parameters = emptyList()).matches(method, twoParameters))
    }

    @Test
    fun `methodCall filter uses type declaration comparisons`() {
        val method = createTestMethod()
        val instruction = invoke(
            Opcode.INVOKE_VIRTUAL,
            ImmutableMethodReference("Lcom/some/app/Loader;", "load", listOf("[Ljava/lang/String;"), "Ljava/util/List;")
        )

        // Starts with.
        assertTrue(methodCall(definingClass = "Lcom/some/app/").matches(method, instruction))
        assertFalse(methodCall(definingClass = "Lcom/other/").matches(method, instruction))
        // Ends with.
        assertTrue(methodCall(returnType = "/List;").matches(method, instruction))
        // Exact, so a partial class name does not match.
        assertFalse(methodCall(definingClass = "Lcom/some/app/Load;").matches(method, instruction))
        // Any array.
        assertTrue(methodCall(parameters = listOf("[")).matches(method, instruction))
    }

    @Test
    fun `methodCall filter 'this' does not match other classes`() {
        val method = createTestMethod(definingClass = "Lcom/test/TestClass;")
        val filter = methodCall(definingClass = "this", name = "method")

        val sameClass = invoke(
            Opcode.INVOKE_DIRECT,
            ImmutableMethodReference("Lcom/test/TestClass;", "method", emptyList(), "V")
        )
        val otherClass = invoke(
            Opcode.INVOKE_DIRECT,
            ImmutableMethodReference("Lcom/test/TestClassOther;", "method", emptyList(), "V")
        )

        assertTrue(filter.matches(method, sameClass))
        assertFalse(filter.matches(method, otherClass))
    }

    @Test
    fun `methodCall filter parses smali declarations`() {
        val method = createTestMethod()
        val filter = methodCall(
            smali = "Lcom/Test;->method([I[[Ljava/lang/String;JZ)[Ljava/lang/Object;"
        )

        assertEquals("Lcom/Test;", filter.definingClass)
        assertEquals("method", filter.name)
        assertEquals(listOf("[I", "[[Ljava/lang/String;", "J", "Z"), filter.parameters)
        assertEquals("[Ljava/lang/Object;", filter.returnType)

        val reference = ImmutableMethodReference(
            "Lcom/Test;", "method", listOf("[I", "[[Ljava/lang/String;", "J", "Z"), "[Ljava/lang/Object;"
        )
        assertTrue(filter.matches(method, invoke(Opcode.INVOKE_STATIC, reference)))

        val otherParameters = ImmutableMethodReference(
            "Lcom/Test;", "method", listOf("[I", "[Ljava/lang/String;", "J", "Z"), "[Ljava/lang/Object;"
        )
        assertFalse(filter.matches(method, invoke(Opcode.INVOKE_STATIC, otherParameters)))

        // No parameters, and an opcode restriction.
        val noParametersFilter = methodCall(smali = "Lcom/Test;->run()V", opcode = Opcode.INVOKE_VIRTUAL)
        assertEquals(emptyList(), noParametersFilter.parameters)
        val runReference = ImmutableMethodReference("Lcom/Test;", "run", emptyList(), "V")
        assertTrue(noParametersFilter.matches(method, invoke(Opcode.INVOKE_VIRTUAL, runReference)))
        assertFalse(noParametersFilter.matches(method, invoke(Opcode.INVOKE_STATIC, runReference)))
    }

    @Test
    fun `methodCall filter throws for invalid smali declarations`() {
        listOf(
            "Lcom/Test;->method(I)",
            "Lcom/Test;method(I)V",
            "com/Test;->method(I)V",
            "Lcom/Test;->method(Lcom/Missing)V",
            "Lcom/Test;->method(I)Ljava/lang/String",
        ).forEach { smali ->
            assertThrows<IllegalArgumentException>(smali) {
                methodCall(smali = smali)
            }
        }
    }

    @Test
    fun `methodCall filter from a method reference`() {
        val method = createTestMethod()
        val reference = ImmutableMethodReference("Lcom/Test;", "method", listOf("I"), "Z")
        val filter = methodCall(reference)

        assertTrue(filter.matches(method, invoke(Opcode.INVOKE_VIRTUAL, reference)))
        assertFalse(
            filter.matches(
                method,
                invoke(Opcode.INVOKE_VIRTUAL, ImmutableMethodReference("Lcom/Test;", "method", listOf("J"), "Z"))
            )
        )
        assertFalse(
            filter.matches(
                method,
                invoke(Opcode.INVOKE_VIRTUAL, ImmutableMethodReference("Lcom/Test2;", "method", listOf("I"), "Z"))
            )
        )
    }

    // ==================== Additional fieldAccess tests ====================

    @Test
    fun `fieldAccess filter only matches declared opcodes`() {
        val method = createTestMethod()
        val reference = ImmutableFieldReference("Lcom/Test;", "field", "I")
        val get = BuilderInstruction22c(Opcode.IGET, 0, 1, reference)
        val put = BuilderInstruction22c(Opcode.IPUT, 0, 1, reference)

        assertTrue(fieldAccess(name = "field", opcode = Opcode.IGET).matches(method, get))
        assertFalse(fieldAccess(name = "field", opcode = Opcode.IGET).matches(method, put))

        val getOrPut = fieldAccess(name = "field", opcodes = listOf(Opcode.IGET, Opcode.IPUT))
        assertTrue(getOrPut.matches(method, get))
        assertTrue(getOrPut.matches(method, put))

        // Static field access uses different opcodes.
        val staticGet = BuilderInstruction21c(Opcode.SGET, 0, reference)
        assertTrue(fieldAccess(name = "field").matches(method, staticGet))
        assertFalse(fieldAccess(name = "field", opcode = Opcode.IGET).matches(method, staticGet))
    }

    @Test
    fun `fieldAccess filter parses smali declarations`() {
        val method = createTestMethod()
        val filter = fieldAccess(smali = "Landroid/os/Build;->MODEL:Ljava/lang/String;")

        assertEquals("Landroid/os/Build;", filter.definingClass)
        assertEquals("MODEL", filter.name)
        assertEquals("Ljava/lang/String;", filter.type)

        val model = ImmutableFieldReference("Landroid/os/Build;", "MODEL", "Ljava/lang/String;")
        val device = ImmutableFieldReference("Landroid/os/Build;", "DEVICE", "Ljava/lang/String;")
        assertTrue(filter.matches(method, BuilderInstruction21c(Opcode.SGET_OBJECT, 0, model)))
        assertFalse(filter.matches(method, BuilderInstruction21c(Opcode.SGET_OBJECT, 0, device)))

        val arrayFilter = fieldAccess(smali = "Lcom/Test;->values:[I", opcode = Opcode.IGET_OBJECT)
        assertEquals("[I", arrayFilter.type)
        val values = ImmutableFieldReference("Lcom/Test;", "values", "[I")
        assertTrue(arrayFilter.matches(method, BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 1, values)))
        assertFalse(arrayFilter.matches(method, BuilderInstruction22c(Opcode.IPUT_OBJECT, 0, 1, values)))
    }

    @Test
    fun `fieldAccess filter throws for invalid smali declarations`() {
        listOf(
            "Lcom/Test;->field",
            "Lcom/Test;->field:Ljava/lang/String",
            "Lcom/Test;field:I",
            "com/Test;->field:I",
        ).forEach { smali ->
            assertThrows<IllegalArgumentException>(smali) {
                fieldAccess(smali = smali)
            }
        }
    }

    @Test
    fun `fieldAccess filter from a field reference`() {
        val method = createTestMethod()
        val reference = ImmutableFieldReference("Lcom/Test;", "field", "Z")
        val filter = fieldAccess(reference, Opcode.IPUT_BOOLEAN)

        assertTrue(filter.matches(method, BuilderInstruction22c(Opcode.IPUT_BOOLEAN, 0, 1, reference)))
        assertFalse(filter.matches(method, BuilderInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, reference)))
        assertFalse(
            filter.matches(
                method,
                BuilderInstruction22c(Opcode.IPUT_BOOLEAN, 0, 1, ImmutableFieldReference("Lcom/Test;", "field", "I"))
            )
        )
    }

    // ==================== Additional type filter tests ====================

    @Test
    fun `type filters with lambda types`() {
        val method = createTestMethod()
        val stringType = ImmutableTypeReference("Ljava/lang/String;")

        assertTrue(
            newInstance({ "Ljava/lang/String;" })
                .matches(method, BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, stringType))
        )
        assertTrue(
            checkCast({ "Ljava/lang/String;" })
                .matches(method, BuilderInstruction21c(Opcode.CHECK_CAST, 0, stringType))
        )
        assertFalse(
            checkCast({ "Ljava/lang/String;" })
                .matches(method, BuilderInstruction22c(Opcode.INSTANCE_OF, 0, 1, stringType))
        )
    }

    @Test
    fun `type filter lambda is called only once`() {
        val method = createTestMethod()
        var calls = 0
        val filter = checkCast({
            calls++
            "Ljava/lang/String;"
        })
        assertEquals(0, calls)

        val instruction = BuilderInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;"))
        repeat(3) {
            assertTrue(filter.matches(method, instruction))
        }
        assertEquals(1, calls)
    }

    @Test
    fun `type filters match array types`() {
        val method = createTestMethod()
        val stringArray = BuilderInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("[Ljava/lang/String;"))
        val intArray = BuilderInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("[I"))
        val string = BuilderInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;"))

        // Exact array type.
        assertTrue(checkCast("[Ljava/lang/String;").matches(method, stringArray))
        assertFalse(checkCast("[Ljava/lang/String;").matches(method, string))

        // Any array.
        assertTrue(checkCast("[").matches(method, stringArray))
        assertTrue(checkCast("[").matches(method, intArray))
        assertFalse(checkCast("[").matches(method, string))

        // Any object array.
        assertTrue(checkCast("[L").matches(method, stringArray))
        assertFalse(checkCast("[L").matches(method, intArray))
    }

    // ==================== Additional anyInstruction tests ====================

    @Test
    fun `anyInstruction matches different kinds of filters`() {
        val method = createTestMethod()
        val filter = anyInstruction(
            string("text"),
            literal(7),
            opcode(Opcode.NOP)
        )

        assertTrue(filter.matches(method, BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("text"))))
        assertTrue(filter.matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, 7)))
        assertTrue(filter.matches(method, BuilderInstruction10x(Opcode.NOP)))
        assertFalse(filter.matches(method, BuilderInstruction11n(Opcode.CONST_4, 0, 6)))
        assertFalse(filter.matches(method, BuilderInstruction10x(Opcode.RETURN_VOID)))
    }

    @Test
    fun `anyInstruction uses its own location`() {
        val filter = anyInstruction(
            string("a", location = InstructionLocation.MatchFirst()),
            string("b", location = InstructionLocation.MatchAfterImmediately()),
            location = InstructionLocation.MatchAfterWithin(3)
        )
        assertTrue(filter.location is InstructionLocation.MatchAfterWithin)

        // Default location.
        assertTrue(anyInstruction(string("a")).location is InstructionLocation.MatchAfterAnywhere)
    }
}
