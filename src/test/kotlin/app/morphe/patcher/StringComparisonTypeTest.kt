/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patcher
 */

package app.morphe.patcher

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object StringComparisonTypeTest {

    // ==================== StringComparisonType.compare tests ====================

    @Test
    fun `EQUALS comparison returns true for identical strings`() {
        assertTrue(StringComparisonType.EQUALS.compare("test", "test"))
        assertTrue(StringComparisonType.EQUALS.compare("Ljava/lang/String;", "Ljava/lang/String;"))
        assertTrue(StringComparisonType.EQUALS.compare("", ""))
    }

    @Test
    fun `EQUALS comparison returns false for different strings`() {
        assertFalse(StringComparisonType.EQUALS.compare("test", "different"))
        assertFalse(StringComparisonType.EQUALS.compare("Ljava/lang/String;", "Ljava/lang/Object;"))
        assertFalse(StringComparisonType.EQUALS.compare("test", ""))
    }

    @Test
    fun `CONTAINS comparison returns true when target contains search string`() {
        assertTrue(StringComparisonType.CONTAINS.compare("hello world", "world"))
        assertTrue(StringComparisonType.CONTAINS.compare("Ljava/lang/String;", "java/lang"))
        assertTrue(StringComparisonType.CONTAINS.compare("test", "test"))
        assertTrue(StringComparisonType.CONTAINS.compare("test", ""))
    }

    @Test
    fun `CONTAINS comparison returns false when target does not contain search string`() {
        assertFalse(StringComparisonType.CONTAINS.compare("hello", "world"))
        assertFalse(StringComparisonType.CONTAINS.compare("Ljava/lang/String;", "kotlin"))
    }

    @Test
    fun `STARTS_WITH comparison returns true when target starts with search string`() {
        assertTrue(StringComparisonType.STARTS_WITH.compare("hello world", "hello"))
        assertTrue(StringComparisonType.STARTS_WITH.compare("Ljava/lang/String;", "Ljava"))
        assertTrue(StringComparisonType.STARTS_WITH.compare("test", ""))
    }

    @Test
    fun `STARTS_WITH comparison returns false when target does not start with search string`() {
        assertFalse(StringComparisonType.STARTS_WITH.compare("hello world", "world"))
        assertFalse(StringComparisonType.STARTS_WITH.compare("Ljava/lang/String;", "String"))
    }

    @Test
    fun `ENDS_WITH comparison returns true when target ends with search string`() {
        assertTrue(StringComparisonType.ENDS_WITH.compare("hello world", "world"))
        assertTrue(StringComparisonType.ENDS_WITH.compare("Ljava/lang/String;", "String;"))
        assertTrue(StringComparisonType.ENDS_WITH.compare("test", ""))
    }

    @Test
    fun `ENDS_WITH comparison returns false when target does not end with search string`() {
        assertFalse(StringComparisonType.ENDS_WITH.compare("hello world", "hello"))
        assertFalse(StringComparisonType.ENDS_WITH.compare("Ljava/lang/String;", "Ljava"))
    }

    // ==================== typeDeclarationToComparison tests ====================

    @Test
    fun `typeDeclarationToComparison returns EQUALS for null`() {
        val nullCharSequence: CharSequence? = null
        assertEquals(
            StringComparisonType.EQUALS,
            StringComparisonType.typeDeclarationToComparison(nullCharSequence)
        )
    }

    @Test
    fun `typeDeclarationToComparison throws for empty string`() {
        val emptyString: CharSequence = ""
        assertThrows<IllegalArgumentException> {
            StringComparisonType.typeDeclarationToComparison(emptyString)
        }
    }

    @Test
    fun `typeDeclarationToComparison returns EQUALS for single char primitives`() {
        listOf('B', 'C', 'D', 'F', 'I', 'J', 'S', 'V', 'Z').forEach { primitive ->
            assertEquals(
                StringComparisonType.EQUALS,
                StringComparisonType.typeDeclarationToComparison(primitive.toString()),
                "Failed for primitive type: $primitive"
            )
        }
    }

    @Test
    fun `typeDeclarationToComparison returns STARTS_WITH for single L`() {
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("L")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns STARTS_WITH for single array marker`() {
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("[")
        )
    }

    @Test
    fun `typeDeclarationToComparison throws for unknown single char type`() {
        assertThrows<IllegalArgumentException> {
            StringComparisonType.typeDeclarationToComparison("X")
        }
    }

    @Test
    fun `typeDeclarationToComparison returns EQUALS for full class type`() {
        assertEquals(
            StringComparisonType.EQUALS,
            StringComparisonType.typeDeclarationToComparison("Ljava/lang/String;")
        )
        assertEquals(
            StringComparisonType.EQUALS,
            StringComparisonType.typeDeclarationToComparison("Landroid/view/View;")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns STARTS_WITH for partial class type starting with L`() {
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("Ljava/lang")
        )
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("Landroid")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns ENDS_WITH for partial class type ending with semicolon`() {
        assertEquals(
            StringComparisonType.ENDS_WITH,
            StringComparisonType.typeDeclarationToComparison("/String;")
        )
        assertEquals(
            StringComparisonType.ENDS_WITH,
            StringComparisonType.typeDeclarationToComparison("View;")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns CONTAINS for other type declarations`() {
        assertEquals(
            StringComparisonType.CONTAINS,
            StringComparisonType.typeDeclarationToComparison("/String")
        )
        assertEquals(
            StringComparisonType.CONTAINS,
            StringComparisonType.typeDeclarationToComparison("java/lang")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns EQUALS for array types with semicolon`() {
        assertEquals(
            StringComparisonType.EQUALS,
            StringComparisonType.typeDeclarationToComparison("[Ljava/lang/String;")
        )
        assertEquals(
            StringComparisonType.EQUALS,
            StringComparisonType.typeDeclarationToComparison("[[Ljava/lang/String;")
        )
    }

    @Test
    fun `typeDeclarationToComparison returns STARTS_WITH for array types without semicolon`() {
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("[I")
        )
        assertEquals(
            StringComparisonType.STARTS_WITH,
            StringComparisonType.typeDeclarationToComparison("[Ljava/lang")
        )
    }

    // ==================== parametersMatch tests ====================

    @Test
    fun `parametersMatch returns true for empty lists`() {
        assertTrue(parametersMatch(emptyList(), emptyList()))
    }

    @Test
    fun `parametersMatch returns true for identical parameters`() {
        val params = listOf("Ljava/lang/String;", "I", "Z")
        assertTrue(parametersMatch(params, params))
    }

    @Test
    fun `parametersMatch returns false for different size lists`() {
        assertFalse(parametersMatch(listOf("I"), emptyList()))
        assertFalse(parametersMatch(emptyList(), listOf("I")))
        assertFalse(parametersMatch(listOf("I", "Z"), listOf("I")))
    }

    @Test
    fun `parametersMatch returns true with partial type matching`() {
        val actual = listOf("Ljava/lang/String;", "Ljava/lang/Object;")
        val filter = listOf("L", "L")  // STARTS_WITH L
        assertTrue(parametersMatch(actual, filter))
    }

    @Test
    fun `parametersMatch returns true with ends with type matching`() {
        val actual = listOf("Ljava/lang/String;", "Landroid/view/View;")
        val filter = listOf("/String;", "/View;")  // ENDS_WITH
        assertTrue(parametersMatch(actual, filter))
    }

    @Test
    fun `parametersMatch returns false when parameters don't match`() {
        val actual = listOf("Ljava/lang/String;", "I")
        val filter = listOf("Ljava/lang/String;", "Z")
        assertFalse(parametersMatch(actual, filter))
    }

    @Test
    fun `parametersMatch with contains comparison`() {
        val actual = listOf("Ljava/lang/String;")
        val filter = listOf("java/lang")  // CONTAINS
        assertTrue(parametersMatch(actual, filter))
    }

    // ==================== typeDeclarationToComparison for Iterable tests ====================

    @Test
    fun `typeDeclarationToComparison returns empty list for null iterable`() {
        assertTrue(
            StringComparisonType.typeDeclarationToComparison(null as Iterable<CharSequence>?).isEmpty()
        )
    }

    @Test
    fun `typeDeclarationToComparison returns list of comparisons for iterable`() {
        val types = listOf("I", "Ljava/lang/String;", "L", "/View;")
        val result = StringComparisonType.typeDeclarationToComparison(types)

        assertEquals(4, result.size)
        assertEquals(StringComparisonType.EQUALS, result[0])
        assertEquals(StringComparisonType.EQUALS, result[1])
        assertEquals(StringComparisonType.STARTS_WITH, result[2])
        assertEquals(StringComparisonType.ENDS_WITH, result[3])
    }
}
