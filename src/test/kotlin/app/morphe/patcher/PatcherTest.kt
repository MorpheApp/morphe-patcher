/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.util.PatchClasses
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal object PatcherTest {
    private lateinit var patcher: Patcher

    @BeforeEach
    fun setUp() {
        patcher = mockk<Patcher> {
            // Can't mock private fields, until https://github.com/mockk/mockk/issues/1244 is resolved.
            setPrivateField(
                "config",
                mockk<PatcherConfig> {
                    every { resourceMode } returns ResourceMode.NONE
                },
            )
            setPrivateField(
                "logger",
                Logger.getAnonymousLogger(),
            )

            every { context.bytecodeContext.patchClasses } returns mockk(relaxed = true)
            every { this@mockk() } answers { callOriginal() }
        }
    }


    @Test
    fun `executes patches in correct order`() {
        val executed = mutableListOf<String>()

        val patches = setOf(
            bytecodePatch { execute { executed += "1" } },
            bytecodePatch {
                dependsOn(
                    bytecodePatch {
                        execute { executed += "2" }
                        finalize { executed += "-2" }
                    },
                    bytecodePatch { execute { executed += "3" } },
                )

                execute { executed += "4" }
                finalize { executed += "-1" }
            },
        )

        assert(executed.isEmpty())

        patches()

        assertEquals(
            listOf("1", "2", "3", "4", "-1", "-2"),
            executed,
            "Expected patches to be executed in correct order.",
        )
    }


    @Test
    fun `handles execution of patches correctly when exceptions occur`() {
        val executed = mutableListOf<String>()

        infix fun Patch<*>.produces(equals: List<String>) {
            val patches = setOf(this)

            patches()

            assertEquals(equals, executed, "Expected patches to be executed in correct order.")

            executed.clear()
        }

        // No patches execute successfully,
        // because the dependency patch throws an exception inside the execute block.
        bytecodePatch {
            dependsOn(
                bytecodePatch {
                    execute { throw PatchException("1") }
                    finalize { executed += "-2" }
                },
            )

            execute { executed += "2" }
            finalize { executed += "-1" }
        } produces emptyList()

        // The dependency patch is executed successfully,
        // because only the dependant patch throws an exception inside the finalize block.
        // Patches that depend on a failed patch should not be executed,
        // but patches that are depended on by a failed patch should be executed.
        bytecodePatch {
            dependsOn(
                bytecodePatch {
                    execute { executed += "1" }
                    finalize { executed += "-2" }
                },
            )

            execute { throw PatchException("2") }
            finalize { executed += "-1" }
        } produces listOf("1", "-2")

        // Because the finalize block of the dependency patch is executed after the finalize block of the dependant patch,
        // the dependant patch executes successfully, but the dependency patch raises an exception in the finalize block.
        bytecodePatch {
            dependsOn(
                bytecodePatch {
                    execute { executed += "1" }
                    finalize { throw PatchException("-2") }
                },
            )

            execute { executed += "2" }
            finalize { executed += "-1" }
        } produces listOf("1", "2", "-1")

        // The dependency patch is executed successfully,
        // because the dependant patch raises an exception in the finalize block.
        // Patches that depend on a failed patch should not be executed,
        // but patches that are depended on by a failed patch should be executed.
        bytecodePatch {
            dependsOn(
                bytecodePatch {
                    execute { executed += "1" }
                    finalize { executed += "-2" }
                },
            )

            execute { executed += "2" }
            finalize { throw PatchException("-1") }
        } produces listOf("1", "2", "-2")
    }

    @Test
    fun `matches fingerprint`() {
        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(
            setOf(ImmutableClassDef(
                    "Lclass1;",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf(
                        ImmutableMethod(
                            "Lclass1;",
                            "method1",
                            emptyList(),
                            "Ljava/lang/String;",
                            0,
                            null,
                            null,
                            null,
                        )
                    )
                ),
                ImmutableClassDef(
                    "Lclass2;",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf(
                        ImmutableMethod(
                            "Lclass2;",
                            "method2",
                            emptyList(),
                            "Ljava/lang/String;",
                            0,
                            null,
                            null,
                            null,
                        ),
                        ImmutableMethod(
                            "Lclass2;",
                            "method3",
                            emptyList(),
                            "Ljava/lang/Integer;",
                            0,
                            null,
                            null,
                            null,
                        )
                    )
                )
            )
        )

        assertThrows<IllegalArgumentException>("Empty fingerprint") {
            Fingerprint()
        }

        val fingerprint1 = Fingerprint(returnType = "Ljava/lang/String;")
        val fingerprint2 = Fingerprint(returnType = "String;")
        val fingerprint3 = Fingerprint(returnType = "/lang")
        val fingerprint4 = Fingerprint(name = "method2")
        val fingerprint5 = Fingerprint(classFingerprint = fingerprint4, returnType = "Ljava/lang/String;")
        val fingerprint6 = Fingerprint(name = "method1")
        val fingerprint7 = Fingerprint(definingClass = "Lclass2;", name = "method1")
        val fingerprint8 = Fingerprint(definingClass = "Lclass2", name = "method1")

        assertThrows<IllegalArgumentException>("Empty fingerprint") {
            Fingerprint(classFingerprint = fingerprint1)
        }

        val patches = setOf(
            bytecodePatch {
                execute {
                    val class1 = patchClasses.classMap.values.first().classDef
                    println("class1: $class1")
                    val class2 = patchClasses.classMap.values.last().classDef
                    println("class2: $class2")

                    fingerprint1.match(patchClasses.classMap.values.first().classDef.methods.first())
                    fingerprint2.match(patchClasses.classMap.values.first().classDef)
                    fingerprint3.originalClassDef
                    fingerprint4.match()
                    fingerprint5.match()
                    fingerprint6.match()
                    fingerprint7.match()
                    fingerprint8.match()
                }
            }
        )

        patches()

        with(patcher.context.bytecodeContext) {
            println("fingerprint4.originalClassDef.type: ")
            println(fingerprint4.originalClassDef.type)
            assertEquals(fingerprint4.originalClassDef.type, "Lclass2;")
            assertEquals(fingerprint5.originalClassDef.type, "Lclass2;")

            assertAll(
                "Expected fingerprints to match.",
                { assertNotNull(fingerprint1.originalClassDefOrNull) },
                { assertNotNull(fingerprint2.originalClassDefOrNull) },
                { assertNotNull(fingerprint3.originalClassDefOrNull) },
            )

            println("fingerprint5.originalClassDef: ${fingerprint5.originalClassDef}")
            assertAll(
                "classFingerprint resolves",
                {
                    assertTrue{
                        fingerprint4.originalClassDef.type == "Lclass2;"
                    }
                    assertEquals(
                        fingerprint5.originalClassDef, fingerprint4.originalClassDef
                    )
                }
            )

            assertThrows<Exception>(
                "Matching class that differs from classFingerprint"
            ) {
                fingerprint5.match(fingerprint6.originalClassDef)
            }

            assertThrows<Exception>(
                "Matching method that differs from classFingerprint"
            ) {
                fingerprint7.match(fingerprint6.method)
            }

            assertThrows<Exception>(
                "Matching method that differs from classFingerprint"
            ) {
                fingerprint8.match(fingerprint6.method)
            }
        }
    }

    @Test
    fun `String filter patcomparison types`() {
        val method = ImmutableMethod(
            "class",
            "method",
            emptyList(),
            "V",
            0,
            null,
            null,
            null,
        )

        val instruction1 = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            0,
            ImmutableStringReference("12345")
        )

        val instruction2 = ImmutableInstruction21c(
            Opcode.CONST_STRING,
            0,
            ImmutableStringReference("345")
        )

        with(string("12345")) {
            assertTrue(matches(method, instruction1))
            assertFalse(matches(method, instruction2))
        }

        with(string("234", comparison = StringComparisonType.CONTAINS)) {
            assertTrue(matches(method, instruction1))
            assertFalse(matches(method, instruction2))
        }

        with(string("123", comparison = StringComparisonType.STARTS_WITH)) {
            assertTrue(matches(method, instruction1))
            assertFalse(matches(method, instruction2))
        }

        with(string("345", comparison = StringComparisonType.ENDS_WITH)) {
            assertTrue(matches(method, instruction1))
            assertTrue(matches(method, instruction2))
        }

        with(string("123", comparison = StringComparisonType.ENDS_WITH)) {
            assertFalse(matches(method, instruction1))
            assertFalse(matches(method, instruction2))
        }
    }

    @Test
    fun `MethodCallFilter smali parsing`() {
        with(patcher.context.bytecodeContext) {
            var definingClass = "Landroid/view/View;"
            var name = "inflate"
            var parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup;")
            var returnType = "Landroid/view/View;"
            var methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            var filter = MethodCallFilter.parseJvmMethodCall(methodSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(parameters, filter.parameters!!)
            assertEquals(returnType, filter.returnType!!)

            definingClass = "Landroid/view/View\$InnerClass;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "Landroid/view/ViewGroup\$ViewInnerClass;", "J")
            returnType = "V"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            filter = MethodCallFilter.parseJvmMethodCall(methodSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(parameters, filter.parameters!!)
            assertEquals(returnType, filter.returnType!!)

            definingClass = "Landroid/view/View\$InnerClass;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "Landroid/view/ViewGroup\$ViewInnerClass;", "J")
            returnType = "I"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            filter = MethodCallFilter.parseJvmMethodCall(methodSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(parameters, filter.parameters!!)
            assertEquals(returnType, filter.returnType!!)

            definingClass = "Landroid/view/View\$InnerClass;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "Landroid/view/ViewGroup\$ViewInnerClass;", "J")
            returnType = "[I"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            filter = MethodCallFilter.parseJvmMethodCall(methodSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(parameters, filter.parameters!!)
            assertEquals(returnType, filter.returnType!!)
        }
    }

    @Test
    fun `MethodCallFilter smali bad input`() {
        with(patcher.context.bytecodeContext) {
            var definingClass = "Landroid/view/View"
            var name = "inflate"
            var parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup;")
            var returnType = "Landroid/view/View;"
            var methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            assertThrows<IllegalArgumentException>("Defining class missing semicolon") {
                MethodCallFilter.parseJvmMethodCall(methodSignature)
            }


            definingClass = "Landroid/view/View;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup")
            returnType = "Landroid/view/View"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            assertThrows<IllegalArgumentException>("Return type missing semicolon") {
                MethodCallFilter.parseJvmMethodCall(methodSignature)
            }


            definingClass = "Landroid/view/View;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup;")
            returnType = ""
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            assertThrows<IllegalArgumentException>("Empty return type") {
                MethodCallFilter.parseJvmMethodCall(methodSignature)
            }


            definingClass = "Landroid/view/View;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup;")
            returnType = "Landroid/view/View"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            assertThrows<IllegalArgumentException>("Return type class missing semicolon") {
                MethodCallFilter.parseJvmMethodCall(methodSignature)
            }


            definingClass = "Landroid/view/View;"
            name = "inflate"
            parameters = listOf("[Ljava/lang/String;", "I", "Z", "F", "J", "Landroid/view/ViewGroup;")
            returnType = "Q"
            methodSignature = "$definingClass->$name(${parameters.joinToString("")})$returnType"

            assertThrows<IllegalArgumentException>("Bad primitive type") {
                MethodCallFilter.parseJvmMethodCall(methodSignature)
            }
        }
    }

    @Test
    fun `FieldAccess smali parsing`() {
        with(patcher.context.bytecodeContext) {
            var definingClass = "Ljava/lang/Boolean;"
            var name = "TRUE"
            var type = "Ljava/lang/Boolean;"
            var fieldSignature = "$definingClass->$name:$type"

            var filter = FieldAccessFilter.parseJvmFieldAccess(fieldSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(type, filter.type!!)


            definingClass = "Landroid/view/View\$InnerClass;"
            name = "arrayField"
            type = "[Ljava/lang/Boolean;"
            fieldSignature = "$definingClass->$name:$type"

            filter = FieldAccessFilter.parseJvmFieldAccess(fieldSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(type, filter.type!!)


            definingClass = "Landroid/view/View\$InnerClass;"
            name = "primitiveField"
            type = "I"
            fieldSignature = "$definingClass->$name:$type"

            filter = FieldAccessFilter.parseJvmFieldAccess(fieldSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(type, filter.type!!)


            definingClass = "Landroid/view/View\$InnerClass;"
            name = "primitiveField"
            type = "[I"
            fieldSignature = "$definingClass->$name:$type"

            filter = FieldAccessFilter.parseJvmFieldAccess(fieldSignature)

            assertEquals(definingClass, filter.definingClass!!)
            assertEquals(name, filter.name!!)
            assertEquals(type, filter.type!!)
        }
    }

    @Test
    fun `FieldAccess smali bad input`() {
        with(patcher.context.bytecodeContext) {
            assertThrows<IllegalArgumentException>("Defining class missing semicolon") {
                FieldAccessFilter.parseJvmFieldAccess("Landroid/view/View->fieldName:Landroid/view/View;")
            }

            assertThrows<IllegalArgumentException>("Type class missing semicolon") {
                FieldAccessFilter.parseJvmFieldAccess("Landroid/view/View;->fieldName:Landroid/view/View")
            }

            assertThrows<IllegalArgumentException>("Empty field name") {
                FieldAccessFilter.parseJvmFieldAccess("Landroid/view/View;->:Landroid/view/View;")
            }

            assertThrows<IllegalArgumentException>("Invalid primitive type") {
                FieldAccessFilter.parseJvmFieldAccess("Landroid/view/View;->fieldName:Q")
            }
        }
    }

    @Test
    fun `parameter type declaration comparison`() {
        with(patcher.context.bytecodeContext) {

            arrayOf(
                "B", "C", "D", "F", "I", "J", "S", "V", "Z"
            ).forEach { type ->
                assertEquals(
                    StringComparisonType.typeDeclarationToComparison(type),
                    StringComparisonType.EQUALS
                )
            }

            assertEquals(
                StringComparisonType.typeDeclarationToComparison("L"),
                StringComparisonType.STARTS_WITH
            )

            assertEquals(
                StringComparisonType.typeDeclarationToComparison("/String;"),
                StringComparisonType.ENDS_WITH
            )

            assertEquals(
                // 'S' is a type, but ; ends with has precedences.
                StringComparisonType.typeDeclarationToComparison("String;"),
                StringComparisonType.ENDS_WITH
            )

            assertEquals(
                // 'S' is a type, but ; ends with has precedences.
                StringComparisonType.typeDeclarationToComparison("Ljava/lang/String;"),
                StringComparisonType.EQUALS
            )

            assertEquals(
                StringComparisonType.typeDeclarationToComparison("/String"),
                StringComparisonType.CONTAINS
            )

            assertEquals(
                StringComparisonType.typeDeclarationToComparison("[Ljava/lang"),
                StringComparisonType.STARTS_WITH
            )

            assertEquals(
                StringComparisonType.typeDeclarationToComparison("[I"),
                StringComparisonType.STARTS_WITH
            )
        }
    }

    @Test
    fun `parameter type comparison`() {
        with(patcher.context.bytecodeContext) {

            val parametersActual = listOf(
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "[Ljava/lang/String;",
                "[Landroid/view/ViewGroup;",
                "Landroid/view/ViewGroup;",
                "Z",
                "C",
            )
            var parametersFiler = listOf(
                "L",
                "/String;",
                "/String",
                "[Ljava",
                "[Landroid/view/ViewGroup;",
                "Landroid/view/ViewGroup;",
                "Z",
                "C",
            )

            assertTrue(
                parametersMatch(
                    parametersActual, parametersFiler
                )
            )

            parametersFiler = listOf(
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "[Ljava/lang/String;",
                "[Landroid/view/ViewGroup;",
                "Landroid/view/ViewGroup;",
                "Z",
                "I",
            )

            assertFalse(
                parametersMatch(
                    parametersActual, parametersFiler
                )
            )
        }
    }

    @Test
    fun `Instruction location match after`() {
        with(patcher.context.bytecodeContext) {
            assertThrows<IllegalArgumentException> {
                InstructionLocation.MatchAfterWithin(-1)
            }

            assertThrows<IllegalArgumentException> {
                InstructionLocation.MatchAfterAtLeast(-1)
                    .indexIsValidForMatching(-1, 0)
            }

            assertThrows<IllegalArgumentException> {
                InstructionLocation.MatchAfterRange(-1, 0)
            }

            assertThrows<IllegalArgumentException> {
                InstructionLocation.MatchAfterRange(0, -1)
            }

            InstructionLocation.MatchAfterRange(0, 0)
            InstructionLocation.MatchAfterRange(0, 1)
        }
    }

    private operator fun Set<Patch<*>>.invoke(): List<PatchResult> {
        every { patcher.context.executablePatches } returns toMutableSet()
        every { with(patcher.context.bytecodeContext) { mergeExtension(any<BytecodePatch>()) } } just runs

        return runBlocking { patcher().toList() }
    }

    private operator fun Patch<*>.invoke() = setOf(this)().first()

    private fun Any.setPrivateField(field: String, value: Any) {
        this::class.java.getDeclaredField(field).apply {
            this.isAccessible = true
            set(this@setPrivateField, value)
        }
    }
}
