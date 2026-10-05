/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.util.PatchClasses
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.function.Supplier
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
                    every { bytecodeMode } returns BytecodeMode.NONE
                },
            )
            setPrivateField(
                "logger",
                Logger.getAnonymousLogger(),
            )

            every { context.bytecodeContext.patchClasses } returns mockk(relaxed = true)
            every { context.bytecodeContext.decodeDexFiles() } just runs
            every { context.bytecodeContext.classDefBy(any<String>()) } answers {
                context.bytecodeContext.patchClasses.classBy(firstArg<String>())
            }
            every { context.bytecodeContext.mutableClassDefBy(any<String>()) } answers {
                context.bytecodeContext.patchClasses.mutableClassBy(firstArg<String>())
            }
            every { this@mockk() } answers { callOriginal() }
        }
    }


    @Test
    fun `executes patches in correct order`() {
        val executed = mutableListOf<String>()

        val patches = setOf(
            bytecodePatch(default = true) {  execute { executed += "1" } },
            bytecodePatch(default = true)  {
                dependsOn(
                    bytecodePatch(default = true)  {
                        execute { executed += "2" }
                        finalize { executed += "-2" }
                    },
                    bytecodePatch(default = true)  { execute { executed += "3" } },
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
        bytecodePatch(default = true)  {
            dependsOn(
                bytecodePatch(default = true)  {
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
        bytecodePatch(default = true)  {
            dependsOn(
                bytecodePatch(default = true)  {
                    execute { executed += "1" }
                    finalize { executed += "-2" }
                },
            )

            execute { throw PatchException("2") }
            finalize { executed += "-1" }
        } produces listOf("1", "-2")

        // Because the finalize block of the dependency patch is executed after the finalize block of the dependant patch,
        // the dependant patch executes successfully, but the dependency patch raises an exception in the finalize block.
        bytecodePatch(default = true)  {
            dependsOn(
                bytecodePatch(default = true)  {
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
        bytecodePatch(default = true)  {
            dependsOn(
                bytecodePatch(default = true)  {
                    execute { executed += "1" }
                    finalize { executed += "-2" }
                },
            )

            execute { executed += "2" }
            finalize { throw PatchException("-1") }
        } produces listOf("1", "2", "-2")
    }

    @Test
    fun `universal patch that is default on`() {
        // Verify default is false even though universal declared true.
        val patch = bytecodePatch(name = "Test", default = true) { }

        assertEquals(false, patch.default)
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
            bytecodePatch(default = true)  {
                execute {
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
            assertEquals(fingerprint4.originalClassDef.type, "Lclass2;")
            assertEquals(fingerprint5.originalClassDef.type, "Lclass2;")

            assertAll(
                "Expected fingerprints to match.",
                { assertNotNull(fingerprint1.originalClassDefOrNull) },
                { assertNotNull(fingerprint2.originalClassDefOrNull) },
                { assertNotNull(fingerprint3.originalClassDefOrNull) },
            )

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

    @ParameterizedTest
    @CsvSource(
        "custom,false", "custom,true", "any,false", "any,true",
        "nested-any,false", "nested-any,true", "bundled,false", "bundled,true",
    )
    fun `instruction indexes only filter bundled non-union fingerprints`(kind: String, matchAll: Boolean) {
        val method = ImmutableMethod(
            "Lcandidate;", "value", emptyList(), "V", AccessFlags.PUBLIC.value,
            null, null, MutableMethodImplementation(1),
        ).toMutable().apply {
            addInstructions(0, "const/4 v0, 0x0\nreturn-void")
        }
        val classDef = ImmutableClassDef(
            "Lcandidate;", 0, null, null, null, null, null, listOf(method),
        )
        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(setOf(classDef))

        val firstFilter = when (kind) {
            "custom" -> InstructionFilter { _, _ -> true }
            "any" -> anyInstruction(literal(0))
            "nested-any" -> anyInstruction(anyInstruction(literal(0)))
            else -> literal(0)
        }
        var visitedMethods = 0
        val fingerprint = Fingerprint(
            filters = listOf(firstFilter, literal(1)),
            custom = { _, _ -> visitedMethods++; true },
        )

        with(patcher.context.bytecodeContext) {
            if (matchAll) assertNull(fingerprint.matchAllOrNull())
            else assertNull(fingerprint.matchOrNull())
        }
        assertEquals(if (kind == "bundled") 0 else 1, visitedMethods)
    }

    @Test
    fun `fingerprint matchAll`() {
        val class1 = ImmutableClassDef(
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
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    MutableMethodImplementation(1),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            const/4 v0, 0x0
                            const-string v0, "string_example"
                            return-void
                        """
                    )
                }
            )
        )

        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(
            setOf(
                class1,
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
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(1),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    const-string v0, "string_example"
                                    return-void
                                """
                            )
                        }
                    )
                ),
                ImmutableClassDef(
                    "Lclass3;",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf(
                        ImmutableMethod(
                            "Lclass3;",
                            "method3",
                            emptyList(),
                            "Ljava/lang/String;",
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(1),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    const-string v0, "string_example_long_1"
                                    const-string v0, "string_example_long_2"
                                    const-string v0, "string_example_long_3"
                                    return-void
                                """
                            )
                        }
                    )
                ),
                ImmutableClassDef(
                    "Lclass4;",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf(
                        ImmutableMethod(
                            "Lclass4;",
                            "method4",
                            emptyList(),
                            "Ljava/lang/String;",
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(1),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    const-string v0, "other_string"
                                    return-void
                                """
                            )
                        }
                    )
                ),
                ImmutableClassDef(
                    "Lclass5;",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf(
                        ImmutableMethod(
                            "Lclass5;",
                            "method5",
                            emptyList(),
                            "Ljava/lang/String;",
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(1),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    const-string v0, "other_string_long"
                                    return-void
                                """
                            )
                        }
                    )
                )

            )
        )

        with(patcher.context.bytecodeContext) {
            val cachedLiteralFingerprint = Fingerprint(
                returnType = "Ljava/lang/String;",
                filters = listOf(literal(0)),
            )
            cachedLiteralFingerprint.match(patchClasses.classBy("Lclass2;"))
            assertEquals(
                listOf("Lclass1;", "Lclass2;", "Lclass3;", "Lclass4;", "Lclass5;"),
                cachedLiteralFingerprint.matchAll().map { it.originalClassDef.type },
                "matchAll must start a new search instead of consuming a cached single match",
            )

            assertNull(
                Fingerprint(filters = listOf(literal(1))).matchAllOrNull(),
                "An empty literal candidate set must produce no match",
            )
            assertEquals(
                listOf("Lclass1;", "Lclass2;", "Lclass3;", "Lclass4;", "Lclass5;"),
                Fingerprint(
                    filters = listOf(anyInstruction(literal(1), literal(0))),
                ).matchAll().map { it.originalClassDef.type },
                "AnyInstruction must match through the full scan",
            )

            class ProxyFilterCounter(val filter: InstructionFilter) : InstructionFilter {
                var matchesCallCount = 0

                override fun matches(
                    enclosingMethod: Method,
                    instruction: Instruction
                ): Boolean {
                    matchesCallCount++
                    return filter.matches(enclosingMethod, instruction)
                }
            }

            val proxyFilter = ProxyFilterCounter(
                opcode(
                    Opcode.CONST_4
                )
            )
            val fingerprint1 = Fingerprint(
                filters = listOf(
                    proxyFilter,
                    string("string_example", location = InstructionLocation.MatchAfterWithin(10000))
                )
            )

            proxyFilter.matchesCallCount = 0
            fingerprint1.clearMatch()
            assertEquals(
                listOf(
                    "Lclass1;",
                    "Lclass2;",
                ),
                fingerprint1
                    .matchAll()
                    .map { it.originalClassDef.type }
            )

            // Should not do string lookup because of custom filter.
            assertEquals(
                13, proxyFilter.matchesCallCount,
                "Number of expected filter calls did not match"
            )


            // Add custom counter to bundled list so faster string lookup is used.
            BUNDLED_INSTRUCTION_FILTERS += ProxyFilterCounter::class

            proxyFilter.matchesCallCount = 0
            assertEquals(
                listOf(
                    "Lclass1;",
                    "Lclass2;", // to "method1",
                ),
                fingerprint1
                    .matchAll()
                    .map { it.originalClassDef.type }
            )

            // Matching now only checks methods with matching strings.
            assertEquals(
                2, proxyFilter.matchesCallCount,
                "Number of expected filter calls did not match"
            )


            assertEquals(
                listOf(
                    "Lclass1;"
                ),
                fingerprint1
                    .matchAll(class1)
                    .map { it.originalClassDef.type }
            )

            proxyFilter.matchesCallCount = 0
            val fingerprint2 = Fingerprint(
                filters = listOf(
                    proxyFilter,
                    string("string_example"),
                )
            )

            assertEquals(
                listOf(
                    "Lclass1;",
                    "Lclass2;",
                ),
                fingerprint2
                    .matchAll()
                    .map { it.originalClassDef.type }
            )
            assertEquals(
                2, proxyFilter.matchesCallCount,
                "Number of expected filter calls did not match"
            )


            proxyFilter.matchesCallCount = 0
            val fingerprint3 = Fingerprint(
                filters = listOf(
                    proxyFilter,
                    string("string_example", comparison = StringComparisonType.CONTAINS)
                )
            )

            assertEquals(
                listOf(
                    "Lclass1;",
                    "Lclass2;",
                    "Lclass3;",
                ),
                fingerprint3
                    .matchAll()
                    .map { it.originalClassDef.type }
            )
            // Check only methods with strings that contain the string.
            assertEquals(
                3, proxyFilter.matchesCallCount,
                "Number of expected filter calls did not match"
            )


            assertEquals(1, Fingerprint(
                filters = listOf(
                    string("string_example_long_1"),
                    string("string_example_long_2"),
                    string("string_example_long_3")
                )
            ).matchAll().size)

            assertEquals(1, Fingerprint(
                filters = listOf(
                    string("string_example_long_1", comparison = StringComparisonType.CONTAINS),
                    string("string_example_long_2", comparison = StringComparisonType.STARTS_WITH),
                    string("string_example_long_3", comparison = StringComparisonType.EQUALS)
                )
            ).matchAll().size)

            assertThrows<Exception> {
                fingerprint3.matchAll(0 .. 2)
            }

            assertThrows<Exception> {
                fingerprint3.matchAll(4 .. 100)
            }

            assertEquals(3, fingerprint3.matchAll(1 .. 3).size)

            proxyFilter.matchesCallCount = 0
            val fingerprint4 = Fingerprint(
                filters = listOf(
                    proxyFilter,
                    string("non_existent_string")
                )
            )
            assertThrows<Exception> {
                fingerprint4.matchAll()
            }
            assertEquals(0, fingerprint4.matchAll(0 .. 3).size)
            assertEquals(
                0, proxyFilter.matchesCallCount,
                "Number of expected filter calls did not match"
            )
        }
    }

    @Test
    fun `legacy string matching order`() {
        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(
            setOf(
                ImmutableClassDef(
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
                            AccessFlags.PUBLIC.value,
                            null,
                            null,
                            MutableMethodImplementation(1),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    const/4 v0, 0x0
                                    const-string v0, "string_example_1"
                                    const-string v0, "string_example_2"
                                    const-string v0, "string_example_3"
                                    return-void
                                """
                            )
                        }
                    )
                )
            )
        )

        val patches = setOf(
            bytecodePatch(default = true)  {
                execute {
                    val fingerprint1 = Fingerprint(
                        strings = listOf(
                            "string_example_2",
                            "string_example_1",
                            "string_example_3",
                        )
                    )

                    assertEquals(
                        listOf(
                            2,
                            1,
                            3
                        ),
                        fingerprint1.match().stringMatches.map { it.index }
                    )
                }
            }
        )

        patches()
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

    @Test
    fun `extendWith and extendWithAll are coalesced and resolved lazily at patch time`() {
        // Simulates extensions that are unknown when the patch is built and populated later,
        // for example by a resource patch that this patch depends on.
        val derivedExtensions = mutableListOf<Supplier<InputStream>>()
        val eagerExtension = Supplier<InputStream> { ByteArrayInputStream(ByteArray(0)) }

        val patch = bytecodePatch(name = "Test") {
            // A single extension is stored as a provider that yields one stream...
            extendWith(eagerExtension)
            // ...and a `for (e in derivedExtensions) extendWith(e)` loop here would add nothing because
            // the list is still empty at build time; extendWithAll defers resolution instead.
            extendWithAll { derivedExtensions }
        }

        // Both entry points are coalesced into the single providers list.
        assertEquals(2, patch.extensionStreamProviders.size)

        // Populate the list after the patch has been built.
        repeat(2) { derivedExtensions += Supplier { ByteArrayInputStream(ByteArray(0)) } }

        // The eager extension always yields exactly one stream.
        assertEquals(1, patch.extensionStreamProviders.first().get().count())
        // The deferred provider reflects the up-to-date contents when evaluated at patch time.
        assertEquals(2, patch.extensionStreamProviders.last().get().count())
    }

    @Test
    fun `extendWith and extendWithAll merge through a single path at patch time`() {
        // Tracks whether the merge closed each extension stream it consumed.
        class TrackingStream : InputStream() {
            var closed = false
            override fun read() = -1
            override fun close() {
                closed = true
            }
        }

        mockkObject(DexReadWrite)
        try {
            val emptyDex = mockk<DexFile> { every { classes } returns emptySet() }
            every { DexReadWrite.readDexStream(any()) } returns emptyDex

            // Populated only after the patch is built, to prove extendWithAll is deferred.
            val derivedExtensions = mutableListOf<Supplier<InputStream>>()
            val eagerStream = TrackingStream()

            val patch = bytecodePatch(name = "Test") {
                extendWith(Supplier { eagerStream })
                extendWithAll { derivedExtensions }
            }

            val dynamicStreams = List(3) { TrackingStream() }
            dynamicStreams.forEach { stream -> derivedExtensions += Supplier { stream } }

            val config = mockk<PatcherConfig>(relaxed = true) {
                every { patchedFiles } returns Files.createTempDirectory("morphe-test").toFile()
            }
            val context = BytecodePatchContext(config, mockk(relaxed = true))

            context.mergeExtension(patch)

            // The single eager and three deferred extensions were all read and closed at patch time.
            verify(exactly = 4) { DexReadWrite.readDexStream(any()) }
            assertTrue((dynamicStreams + eagerStream).all { it.closed })
        } finally {
            unmockkObject(DexReadWrite)
        }
    }

    private fun dexBackedMethod(definingClass: String, name: String, smali: String) = ImmutableMethod.of(
        ImmutableMethod(
            definingClass, name, emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null, null, MutableMethodImplementation(2),
        ).toMutable().apply { addInstructions(0, smali) }
    )

    /**
     * Writes the classes to a dex file and returns the dex backed classes, in the same order.
     */
    private fun dexBackedClasses(vararg classes: Pair<String, List<ImmutableMethod>>): Set<ClassDef> {
        val dexPool = DexPool(Opcodes.getDefault())
        classes.forEach { (type, methods) ->
            dexPool.internClass(ImmutableClassDef(type, 0, null, null, null, null, null, methods))
        }
        val dataStore = MemoryDataStore()
        dexPool.writeTo(dataStore)

        val dexClasses = DexReadWrite.readDexStream(ByteArrayInputStream(dataStore.data)).classes
            .associateBy { it.type }
        return classes.mapTo(LinkedHashSet()) { (type, _) -> dexClasses.getValue(type) }
    }

    @Test
    fun `fingerprints match changed methods of mutable dex backed classes`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod(
                        "Lclass1;", "method1",
                        "const v0, 0x5678\ninvoke-static { }, Lclass9;->calledMethod()V\nreturn-void",
                    ),
                ),
                "Lclass2;" to listOf(
                    dexBackedMethod("Lclass2;", "method2", "return-void"),
                    dexBackedMethod("Lclass2;", "method3", "return-void"),
                ),
                "Lclass3;" to listOf(
                    dexBackedMethod("Lclass3;", "method4", "invoke-static { }, Lclass9;->calledMethod()V\nreturn-void"),
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            fun Fingerprint.matchedMethods() = matchAllOrNull()?.map {
                it.originalClassDef.type + "->" + it.originalMethod.name
            }

            assertEquals(listOf("Lclass1;->method1"), Fingerprint(filters = listOf(literal(0x5678))).matchedMethods())
            assertEquals(
                listOf("Lclass1;->method1", "Lclass3;->method4"),
                Fingerprint(filters = listOf(methodCall(name = "calledMethod"))).matchedMethods(),
                "Method calls are found by the method name",
            )

            // Matching mutable methods must not create the mutable implementation of methods that cannot match.
            val mutableClass2 = patchClasses.classMap.getValue("Lclass2;").getMutableClass()
            assertNull(Fingerprint(filters = listOf(literal(0x9999))).matchAllOrNull())
            assertNull(Fingerprint(filters = listOf(anyInstruction(literal(0x9999)))).matchAllOrNull())
            assertTrue(mutableClass2.methods.none { it.isImplementationCreated })

            mutableClass2.methods.first { it.name == "method3" }.addInstructions(0, "const v0, 0x5678")

            assertEquals(
                listOf("Lclass1;->method1", "Lclass2;->method3"),
                Fingerprint(filters = listOf(literal(0x5678))).matchedMethods(),
                "Instructions added to a mutable class must match",
            )
            assertEquals(
                "Lclass2;->method3",
                Fingerprint(name = "method3", filters = listOf(literal(0x5678))).matchOrNull()?.let {
                    it.originalClassDef.type + "->" + it.originalMethod.name
                },
            )
            assertTrue(
                mutableClass2.methods.first { it.name == "method2" }.let { !it.isImplementationCreated },
                "Unchanged methods are matched with the instructions of the source method",
            )
        }
    }

    @Test
    fun `fingerprints with partial strings only check classes with matching strings`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(dexBackedMethod("Lclass1;", "method1", "const-string v0, \"prefix_value\"\nreturn-void")),
                "Lclass2;" to listOf(dexBackedMethod("Lclass2;", "method2", "const-string v0, \"other\"\nreturn-void")),
                "Lclass3;" to listOf(dexBackedMethod("Lclass3;", "method3", "const-string v0, \"prefix_other\"\nreturn-void")),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            var visitedMethods = 0
            val fingerprint = Fingerprint(
                filters = listOf(string("prefix_", StringComparisonType.STARTS_WITH)),
                custom = { _, _ -> visitedMethods++; true },
            )
            assertEquals(listOf("Lclass1;", "Lclass3;"), fingerprint.matchAll().map { it.originalClassDef.type })
            assertEquals(2, visitedMethods)

            visitedMethods = 0
            assertEquals("Lclass1;", fingerprint.match().originalClassDef.type)
            assertEquals(1, visitedMethods)

            val legacyFingerprint = Fingerprint(strings = listOf("_other"))
            assertEquals(listOf("Lclass3;"), legacyFingerprint.matchAll().map { it.originalClassDef.type })
            assertEquals("Lclass3;", legacyFingerprint.match().originalClassDef.type)
        }
    }

    @Test
    fun `match originalClassDef and originalMethod reflect updated class in patch context`() {
        val patchClasses = PatchClasses(
            setOf(
                ImmutableClassDef(
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
                            "V",
                            0,
                            null,
                            null,
                            null,
                        )
                    )
                )
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            val fp = Fingerprint(name = "method1")
            val match = fp.matchOrNull()
            assertNotNull(match)

            assertFalse(match.originalClassDef is MutableClass)

            val mutableClass = mutableClassDefBy("Lclass1;")

            assertTrue(match.originalClassDef is MutableClass)
            assertSame(mutableClass, match.originalClassDef)
            assertSame(mutableClass.methods.first(), match.originalMethod)
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
