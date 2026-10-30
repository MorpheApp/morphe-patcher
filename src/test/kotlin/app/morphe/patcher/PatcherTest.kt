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
                    every { validate } returns false
                },
            )
            setPrivateField(
                "logger",
                Logger.getAnonymousLogger(),
            )

            every { context.bytecodeContext.patchClasses } returns mockk(relaxed = true)
            every { context.bytecodeContext.validate } returns false
            var fingerprintMatchNanos = 0L
            every { context.bytecodeContext.fingerprintMatchNanos } answers { fingerprintMatchNanos }
            every { context.bytecodeContext.fingerprintMatchNanos = any() } answers {
                fingerprintMatchNanos = firstArg()
            }
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
    fun `stack traces of patch exceptions are trimmed to the patch`() {
        fun fail(): Nothing = throw IllegalStateException("Failure")

        // Frames of the patcher and its caller are below the call into the patch.
        fun Throwable.assertTrimmed() {
            val patcherClassName = Patcher::class.java.name

            generateSequence(this) { it.cause }.forEach { throwable ->
                throwable.stackTrace.forEach { frame ->
                    assertFalse(
                        frame.className == patcherClassName ||
                            frame.className.startsWith("$patcherClassName$") ||
                            frame.className.startsWith("kotlinx.") ||
                            frame.className.startsWith("org.junit."),
                        "Expected no frames below the patch, but found: $frame",
                    )
                }
            }
        }

        fun Throwable.assertEndsAtPatchCall(method: String) {
            assertTrue(stackTrace.isNotEmpty(), "Expected the frames of the patch to be kept.")
            assertTrue(
                stackTrace.any { it.className == Patch::class.java.name && it.methodName == method },
                "Expected the call into the patch to be kept.",
            )
        }

        val direct = bytecodePatch { execute { throw PatchException("Direct") } }().exception
        assertNotNull(direct)
        direct.assertTrimmed()
        direct.assertEndsAtPatchCall("execute")

        // The wrapper has no frames of the patch.
        val wrapped = bytecodePatch { execute { fail() } }().exception
        assertNotNull(wrapped)
        wrapped.assertTrimmed()
        assertTrue(wrapped.stackTrace.isEmpty())
        assertTrue(wrapped.cause is IllegalStateException)
        wrapped.cause!!.assertEndsAtPatchCall("execute")

        val dependant = bytecodePatch {
            dependsOn(bytecodePatch { execute { fail() } })
            execute { }
        }().exception
        assertNotNull(dependant)
        dependant.assertTrimmed()
        assertTrue(dependant.stackTrace.isEmpty())
        assertFalse(dependant.message!!.contains("\tat "), "Expected no stack trace in the message.")
        assertTrue(dependant.cause!!.cause is IllegalStateException)
        dependant.cause!!.cause!!.assertEndsAtPatchCall("execute")

        val finalized = bytecodePatch {
            execute { }
            finalize { fail() }
        }().exception
        assertNotNull(finalized)
        finalized.assertTrimmed()
        assertFalse(finalized.message!!.contains("\tat "), "Expected no stack trace in the message.")
        assertTrue(finalized.cause is IllegalStateException)
        assertNull(finalized.cause!!.cause, "Expected the exception to be wrapped only once.")
        finalized.cause!!.assertEndsAtPatchCall("finalize")
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
                            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
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
        // Access flags are exact.
        val fingerprint9 = Fingerprint(name = "method1", accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC))
        val fingerprint10 = Fingerprint(name = "method1", accessFlags = listOf(AccessFlags.PUBLIC))
        // isStatic ignores all other access flags.
        val fingerprint11 = Fingerprint(name = "method1", isStatic = true)
        val fingerprint12 = Fingerprint(name = "method1", isStatic = false)
        val fingerprint13 = Fingerprint(name = "method2", isStatic = false)
        val fingerprint14 = Fingerprint(name = "method2", isStatic = true)

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
                { assertNotNull(fingerprint9.originalMethodOrNull) },
                { assertNotNull(fingerprint11.originalMethodOrNull) },
                { assertNotNull(fingerprint13.originalMethodOrNull) },
            )

            assertAll(
                "Expected fingerprints to not match.",
                { assertNull(fingerprint10.originalMethodOrNull) },
                { assertNull(fingerprint12.originalMethodOrNull) },
                { assertNull(fingerprint14.originalMethodOrNull) },
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
        "custom,false", "custom,true", "any-custom,false", "any-custom,true",
        "any,false", "any,true", "nested-any,false", "nested-any,true",
        "any-not-indexed,false", "any-not-indexed,true", "bundled,false", "bundled,true",
    )
    fun `instruction indexes only filter fingerprints with bundled filters`(kind: String, matchAll: Boolean) {
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

        val customFilter = InstructionFilter { _, _ -> true }
        val firstFilter = when (kind) {
            "custom" -> customFilter
            "any-custom" -> anyInstruction(literal(0), customFilter)
            "any" -> anyInstruction(literal(0))
            "nested-any" -> anyInstruction(anyInstruction(literal(0)))
            // Matches instructions without indexed values, so only the other filter is indexed.
            "any-not-indexed" -> anyInstruction(literal(0), opcode(Opcode.CONST_4))
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
        assertEquals(if (kind == "custom" || kind == "any-custom") 1 else 0, visitedMethods)
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
                "AnyInstruction must match the classes of any of its filters",
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
        }
    }

    @Test
    fun `MatchFirst only matches the first method instruction`() {
        val method = ImmutableMethod(
            "Lcandidate;", "value", emptyList(), "V", AccessFlags.PUBLIC.value,
            null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    nop
                    const/4 v1, 0x0
                    const/4 v2, 0x1
                    return-void
                """
            )
        }
        val classDef = ImmutableClassDef(
            "Lcandidate;", 0, null, null, null, null, null, listOf(method),
        )
        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(setOf(classDef))

        with(patcher.context.bytecodeContext) {
            val firstInstruction = Fingerprint(
                filters = listOf(literal(0, location = InstructionLocation.MatchFirst()))
            ).matchOrNull()
            assertNotNull(firstInstruction)
            assertEquals(0, firstInstruction.instructionMatches.first().index)

            // Literal 1 exists, but not at index 0.
            assertNull(
                Fingerprint(
                    filters = listOf(literal(1, location = InstructionLocation.MatchFirst()))
                ).matchOrNull()
            )

            // The second filter fails after index 0. Retrying must not
            // allow the first filter to match the later literal 0 at index 2.
            assertNull(
                Fingerprint(
                    filters = listOf(
                        literal(0, location = InstructionLocation.MatchFirst()),
                        literal(1, location = InstructionLocation.MatchAfterImmediately()),
                    )
                ).matchOrNull()
            )
        }
    }

    /**
     * Uses a method with the instructions:
     * ```
     * 0: const/4 v0, 0x0
     * 1: const/4 v1, 0x1
     * 2: nop
     * 3: const/4 v0, 0x0
     * 4: const/4 v1, 0x1
     * 5: const/4 v2, 0x2
     * 6: const/4 v0, 0x0
     * 7: return-void
     * ```
     */
    private fun mockInstructionLocationMethod() = mockCandidateMethod(
        """
            const/4 v0, 0x0
            const/4 v1, 0x1
            nop
            const/4 v0, 0x0
            const/4 v1, 0x1
            const/4 v2, 0x2
            const/4 v0, 0x0
            return-void
        """
    )

    /**
     * Uses a method with the instructions of [smali], which can use the registers v0 to v2.
     */
    private fun mockCandidateMethod(smali: String) {
        val method = ImmutableMethod(
            "Lcandidate;", "value", emptyList(), "V", AccessFlags.PUBLIC.value,
            null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
        val classDef = ImmutableClassDef(
            "Lcandidate;", 0, null, null, null, null, null, listOf(method),
        )
        every { patcher.context.bytecodeContext.patchClasses } returns PatchClasses(setOf(classDef))
    }

    context(_: BytecodePatchContext)
    private fun matchIndexes(vararg filters: InstructionFilter) =
        Fingerprint(filters = filters.toList()).matchOrNull()?.instructionMatches?.map { it.index }

    @Test
    fun `MatchAfterLast matches the last instruction after the previous match`() {
        mockInstructionLocationMethod()

        with(patcher.context.bytecodeContext) {
            assertEquals(
                listOf(6),
                matchIndexes(literal(0, location = InstructionLocation.MatchAfterLast()))
            )
            assertEquals(
                listOf(1, 6),
                matchIndexes(literal(1), literal(0, location = InstructionLocation.MatchAfterLast()))
            )

            // Literal 1 does not exist after literal 2.
            assertNull(
                matchIndexes(literal(2), literal(1, location = InstructionLocation.MatchAfterLast()))
            )

            // The previous match index is excluded, so the first filter is retried with the earlier literal 0.
            assertEquals(
                listOf(3, 6),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                )
            )

            // Literal 1 is not after the last literal 0 at index 6,
            // so the first filter is retried with the literal 0 at index 3.
            assertEquals(
                listOf(3, 4),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1, location = InstructionLocation.MatchAfterImmediately()),
                )
            )
            assertEquals(
                listOf(3, 4),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1),
                )
            )

            // Same as above, but when not the first filter.
            assertEquals(
                listOf(2, 3, 4),
                matchIndexes(
                    opcode(Opcode.NOP),
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1, location = InstructionLocation.MatchAfterImmediately()),
                )
            )

            withFirstFilterRetriedOnly {
                // Only the last literal 0 at index 6 is used.
                assertNull(
                    matchIndexes(
                        literal(0, location = InstructionLocation.MatchAfterLast()),
                        literal(0, location = InstructionLocation.MatchAfterLast()),
                    )
                )
                assertNull(
                    matchIndexes(
                        literal(0, location = InstructionLocation.MatchAfterLast()),
                        literal(1, location = InstructionLocation.MatchAfterImmediately()),
                    )
                )
                assertNull(
                    matchIndexes(
                        literal(0, location = InstructionLocation.MatchAfterLast()),
                        literal(1),
                    )
                )
                assertNull(
                    matchIndexes(
                        opcode(Opcode.NOP),
                        literal(0, location = InstructionLocation.MatchAfterLast()),
                        literal(1, location = InstructionLocation.MatchAfterImmediately()),
                    )
                )
            }
        }
    }

    @Test
    fun `MatchBeforeImmediately and MatchBeforeWithin match before the previous match`() {
        mockInstructionLocationMethod()

        with(patcher.context.bytecodeContext) {
            // Nothing is before the first two constants, and the first filter is retried.
            assertEquals(
                listOf(3, 2),
                matchIndexes(
                    opcode(Opcode.CONST_4),
                    opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeImmediately()),
                )
            )

            // Nothing is before the first instruction.
            assertNull(
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchFirst()),
                    literal(0, location = InstructionLocation.MatchBeforeImmediately()),
                )
            )

            // The closest match before is used.
            assertEquals(
                listOf(5, 3),
                matchIndexes(literal(2), literal(0, location = InstructionLocation.MatchBeforeWithin(5)))
            )
            assertEquals(
                listOf(5, 3),
                matchIndexes(literal(2), literal(0, location = InstructionLocation.MatchBeforeWithin(1)))
            )
            assertNull(
                matchIndexes(literal(2), literal(0, location = InstructionLocation.MatchBeforeWithin(0)))
            )

            assertThrows<IllegalArgumentException> {
                matchIndexes(literal(0, location = InstructionLocation.MatchBeforeImmediately()))
            }
            assertThrows<IllegalArgumentException> {
                matchIndexes(literal(0, location = InstructionLocation.MatchBeforeWithin(1)))
            }
        }
    }

    @Test
    fun `MatchBeforeAnywhere matches the closest instruction before the previous match`() {
        mockInstructionLocationMethod()

        with(patcher.context.bytecodeContext) {
            // The closest match is used, and not the first match.
            assertEquals(
                listOf(5, 3),
                matchIndexes(literal(2), literal(0, location = InstructionLocation.MatchBeforeAnywhere()))
            )
            assertEquals(
                listOf(6, 4),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1, location = InstructionLocation.MatchBeforeAnywhere()),
                )
            )

            // The previous match index is excluded.
            assertNull(
                matchIndexes(opcode(Opcode.NOP), opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeAnywhere()))
            )

            // Nothing is before the first constant, and the first filter is retried.
            assertEquals(
                listOf(3, 2),
                matchIndexes(
                    opcode(Opcode.CONST_4),
                    opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeAnywhere()),
                )
            )

            assertThrows<IllegalArgumentException> {
                matchIndexes(literal(0, location = InstructionLocation.MatchBeforeAnywhere()))
            }
        }
    }

    @Test
    fun `MatchBeforeFirst matches the first instruction before the previous match`() {
        mockInstructionLocationMethod()

        with(patcher.context.bytecodeContext) {
            // The first match is used, and not the closest match.
            assertEquals(
                listOf(5, 0),
                matchIndexes(literal(2), literal(0, location = InstructionLocation.MatchBeforeFirst()))
            )
            assertEquals(
                listOf(5, 0, 1),
                matchIndexes(
                    literal(2),
                    literal(0, location = InstructionLocation.MatchBeforeFirst()),
                    literal(1, location = InstructionLocation.MatchAfterImmediately()),
                )
            )

            // Nothing is before the first literal 0 at index 0,
            // so the second filter is retried with the literal 0 at index 3 that is after a nop.
            assertEquals(
                listOf(5, 3, 2),
                matchIndexes(
                    literal(2),
                    literal(0, location = InstructionLocation.MatchBeforeFirst()),
                    opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeImmediately()),
                )
            )
            withFirstFilterRetriedOnly {
                // Only the first literal 0 at index 0 is used.
                assertNull(
                    matchIndexes(
                        literal(2),
                        literal(0, location = InstructionLocation.MatchBeforeFirst()),
                        opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeImmediately()),
                    )
                )
            }

            // The previous match index is excluded.
            assertNull(
                matchIndexes(opcode(Opcode.NOP), opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeFirst()))
            )

            // Nothing is before the first two constants, and the first filter is retried.
            assertEquals(
                listOf(3, 2),
                matchIndexes(
                    opcode(Opcode.CONST_4),
                    opcode(Opcode.NOP, location = InstructionLocation.MatchBeforeFirst()),
                )
            )

            assertEquals(
                listOf(6, 1),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1, location = InstructionLocation.MatchBeforeFirst()),
                )
            )

            assertThrows<IllegalArgumentException> {
                matchIndexes(literal(0, location = InstructionLocation.MatchBeforeFirst()))
            }
        }
    }

    @Test
    fun `filters can change search direction between each filter`() {
        mockInstructionLocationMethod()

        with(patcher.context.bytecodeContext) {
            assertEquals(
                listOf(6, 4),
                matchIndexes(
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                    literal(1, location = InstructionLocation.MatchBeforeWithin(5)),
                )
            )
            assertEquals(
                listOf(2, 1, 6),
                matchIndexes(
                    opcode(Opcode.NOP),
                    literal(1, location = InstructionLocation.MatchBeforeImmediately()),
                    literal(0, location = InstructionLocation.MatchAfterLast()),
                )
            )

            // The last filter matches the same instruction as the first filter.
            assertEquals(
                listOf(5, 4, 5),
                matchIndexes(
                    literal(2),
                    literal(1, location = InstructionLocation.MatchBeforeImmediately()),
                    literal(2, location = InstructionLocation.MatchAfterImmediately()),
                )
            )
        }
    }

    @Test
    fun `every filter is retried if a later filter does not match`() {
        mockCandidateMethod(
            """
                const-wide/high16 v0, 0x4000000000000000L
                invoke-static { }, Lcandidate;->log()V
                nop
                invoke-static { }, Lcandidate;->value()Ljava/lang/Object;
                move-result-object v2
                const-wide/high16 v0, 0x4000000000000000L
                invoke-static { }, Lcandidate;->value()Ljava/lang/Object;
                move-result-object v2
                return-void
            """
        )
        val filters = arrayOf(
            literal(2.0),
            opcode(Opcode.INVOKE_STATIC),
            opcode(Opcode.MOVE_RESULT_OBJECT, InstructionLocation.MatchAfterImmediately()),
        )

        with(patcher.context.bytecodeContext) {
            // The first invoke-static is not followed by move-result-object, so the second filter is retried.
            assertEquals(listOf(0, 3, 4), matchIndexes(*filters))

            withFirstFilterRetriedOnly {
                assertEquals(listOf(5, 6, 7), matchIndexes(*filters))
            }
        }
    }

    @Test
    fun `filters are retried back to the first filter`() {
        mockCandidateMethod(
            """
                const/4 v0, 0x1
                const/4 v1, 0x2
                nop
                const/4 v1, 0x2
                nop
                const/4 v0, 0x1
                nop
                const/4 v1, 0x2
                nop
                const/4 v1, 0x2
                const/4 v2, 0x3
                return-void
            """
        )
        val filters = arrayOf(
            literal(1),
            literal(2, location = InstructionLocation.MatchAfterWithin(3)),
            literal(3, location = InstructionLocation.MatchAfterImmediately()),
        )

        with(patcher.context.bytecodeContext) {
            // No literal 2 within 3 instructions after the literal 1 at index 0 is followed by literal 3,
            // so the first filter is retried, and then the second filter is retried again.
            assertEquals(listOf(5, 9, 10), matchIndexes(*filters))

            withFirstFilterRetriedOnly {
                assertNull(matchIndexes(*filters))
            }
        }
    }

    @Test
    fun `filters that match before the previous match are retried`() {
        mockCandidateMethod(
            """
                const/4 v1, 0x2
                const/4 v2, 0x3
                const/4 v1, 0x2
                const/4 v0, 0x1
                return-void
            """
        )
        val filters = arrayOf(
            literal(1),
            literal(2, location = InstructionLocation.MatchBeforeAnywhere()),
            literal(3),
        )

        with(patcher.context.bytecodeContext) {
            // Literal 3 is not after the closest literal 2 at index 2, but it is after the literal 2 at index 0.
            assertEquals(listOf(3, 0, 1), matchIndexes(*filters))

            withFirstFilterRetriedOnly {
                assertNull(matchIndexes(*filters))
            }
        }
    }

    @Test
    fun `retried filters search after each previous match index only once`() {
        val instructionCount = 100
        mockCandidateMethod("const/4 v0, 0x1\n".repeat(instructionCount - 1) + "return-void")

        var calls = 0
        fun neverMatches(searchLocation: InstructionLocation) = object : InstructionFilter {
            override val location = searchLocation

            override fun matches(enclosingMethod: Method, instruction: Instruction): Boolean {
                calls++
                return false
            }
        }

        with(patcher.context.bytecodeContext) {
            assertNull(matchIndexes(literal(1), literal(1), neverMatches(InstructionLocation.MatchAfterAnywhere())))
            assertTrue(calls < instructionCount, "a filter searching all later instructions is called $calls times")

            calls = 0
            val within = Array(4) { literal(1, location = InstructionLocation.MatchAfterWithin(3)) }
            assertNull(
                matchIndexes(literal(1), *within, neverMatches(InstructionLocation.MatchAfterImmediately()))
            )
            assertTrue(calls < instructionCount, "a filter searching one instruction is called $calls times")
        }
    }

    @Test
    fun `fingerprints match the superclass and interfaces of the class`() {
        fun method(definingClass: String) = ImmutableMethod(
            definingClass, "run", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, null,
        )
        fun classDef(type: String, superclass: String?, interfaces: List<String>?) = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, interfaces, null, null, null, listOf(method(type)),
        )

        val baseClass = classDef("Lbase;", "Ljava/lang/Object;", null)
        val subClass = classDef("Lsub;", "Lbase;", listOf("Lcom/test/A;", "Lcom/test/B;"))
        val otherClass = classDef("Lother;", "Lbase;", listOf("Lcom/test/A;"))
        every { patcher.context.bytecodeContext.patchClasses } returns
                PatchClasses(setOf(baseClass, subClass, otherClass))

        with(patcher.context.bytecodeContext) {
            fun Fingerprint.matchedTypes() = matchAllOrNull()?.map { it.originalClassDef.type }?.toSet()

            assertEquals(setOf("Lsub;", "Lother;"), Fingerprint(name = "run", superclass = "Lbase;").matchedTypes())
            assertEquals(setOf("Lsub;", "Lother;"), Fingerprint(superclass = "Lbase").matchedTypes())
            assertEquals(setOf("Lbase;"), Fingerprint(superclass = "Ljava/lang/Object;").matchedTypes())
            assertNull(Fingerprint(superclass = "Lmissing;").matchedTypes())

            assertEquals(
                setOf("Lsub;", "Lother;"),
                Fingerprint(interfaces = listOf("Lcom/test/A;")).matchedTypes()
            )
            // Declared in any order, and all must be implemented.
            assertEquals(
                setOf("Lsub;"),
                Fingerprint(interfaces = listOf("Lcom/test/B;", "Lcom/test/A;")).matchedTypes()
            )
            assertEquals(setOf("Lsub;"), Fingerprint(interfaces = listOf("test/B;")).matchedTypes())
            assertNull(Fingerprint(interfaces = listOf("Lcom/test/A;", "Lcom/test/C;")).matchedTypes())

            assertEquals(
                "Lother;",
                Fingerprint(
                    superclass = "Lbase;",
                    interfaces = listOf("Lcom/test/A;"),
                    custom = { _, classDef -> classDef.interfaces.size == 1 },
                ).match().originalClassDef.type
            )

            // Matching a given class or method also checks the class.
            val fingerprint = Fingerprint(name = "run", interfaces = listOf("Lcom/test/B;"))
            assertNull(fingerprint.matchOrNull(otherClass))
            assertNull(fingerprint.matchOrNull(otherClass.methods.first(), otherClass))
            assertNull(fingerprint.matchAllOrNull(otherClass))
            assertNotNull(fingerprint.matchOrNull(subClass))

            val baseFingerprint = Fingerprint(definingClass = "Lbase;", name = "run")
            assertNotNull(
                Fingerprint(classFingerprint = baseFingerprint, superclass = "Ljava/lang/Object;").matchOrNull()
            )
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

    @Test
    fun `validate fingerprints requires a single match`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod("Lclass1;", "method1", "const-string v0, \"shared\"\nreturn-void"),
                    dexBackedMethod("Lclass1;", "method2", "const-string v0, \"unique\"\nreturn-void"),
                ),
                "Lclass2;" to listOf(
                    dexBackedMethod("Lclass2;", "method3", "const-string v0, \"shared\"\nreturn-void"),
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            // Without validation the first match is used.
            assertEquals("method1", Fingerprint(strings = listOf("shared")).match().originalMethod.name)

            every { validate } returns true

            assertThrows<PatchException> {
                Fingerprint(strings = listOf("shared")).match()
            }
            assertThrows<PatchException> {
                Fingerprint(filters = listOf(string("shared"))).matchOrNull()
            }
            assertNull(Fingerprint(strings = listOf("missing")).matchOrNull())

            val unique = Fingerprint(strings = listOf("unique"))
            assertEquals("method2", unique.match().originalMethod.name)
            assertSame(unique.match(), unique.match())

            // Matching against a class only checks the methods of the class.
            val class2 = classDefBy("Lclass2;")
            assertEquals("method3", Fingerprint(strings = listOf("shared")).match(class2).originalMethod.name)
            assertThrows<PatchException> {
                Fingerprint(returnType = "V").match(classDefBy("Lclass1;"))
            }

            // A class fingerprint can match more than one method of the same class.
            val sameClassFingerprint = Fingerprint(definingClass = "Lclass1;", returnType = "V")
            assertThrows<PatchException> { sameClassFingerprint.match() }
            assertEquals(
                "method2",
                Fingerprint(classFingerprint = sameClassFingerprint, strings = listOf("unique"))
                    .match().originalMethod.name
            )
            assertEquals(
                2,
                Fingerprint(classFingerprint = sameClassFingerprint, returnType = "V").matchAll(2 .. 2).size
            )
            // A class fingerprint can match more than one class if only one class has a match.
            val sharedFingerprint = Fingerprint(strings = listOf("shared"))
            assertEquals(
                "method2",
                Fingerprint(classFingerprint = sharedFingerprint, strings = listOf("unique"))
                    .match().originalMethod.name
            )
            assertEquals(
                1,
                Fingerprint(classFingerprint = sharedFingerprint, strings = listOf("unique")).matchAll().size
            )
            assertThrows<PatchException> {
                Fingerprint(classFingerprint = sharedFingerprint, returnType = "V").match()
            }
            assertThrows<PatchException> {
                Fingerprint(classFingerprint = sharedFingerprint, returnType = "V").matchAll()
            }

            // matchAll is unaffected.
            assertEquals(2, Fingerprint(strings = listOf("shared")).matchAll().size)
            assertEquals(2, Fingerprint(strings = listOf("shared")).matchAll(2 .. 3).size)
            assertEquals(2, Fingerprint(strings = listOf("shared")).matchAll(0 .. 3).size)
            assertEquals(2, Fingerprint(filters = listOf(string("shared"))).matchAll(2 .. 3).size)
            assertEquals(2, Fingerprint(returnType = "V").matchAll(classDefBy("Lclass1;"), 2 .. 3).size)

            val classFingerprint = Fingerprint(strings = listOf("unique"))
            assertEquals(
                2,
                Fingerprint(classFingerprint = classFingerprint, returnType = "V").matchAll(2 .. 3).size
            )
        }
    }

    @Test
    fun `fingerprint matches methods with empty string filters`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod("Lclass1;", "methodWithEmptyString", "const-string v0, \"\"\nreturn-void"),
                    dexBackedMethod("Lclass1;", "methodWithText", "const-string v0, \"text\"\nreturn-void"),
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            val emptyStringFingerprint = Fingerprint(
                filters = listOf(string(""))
            )
            val match = emptyStringFingerprint.match()
            assertEquals("methodWithEmptyString", match.originalMethod.name)
        }
    }

    @Test
    fun `validate logs slow fingerprints`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod("Lclass1;", "method1", "const-string v0, \"slow\"\nreturn-void"),
                ),
                "Lclass2;" to listOf(
                    dexBackedMethod("Lclass2;", "method2", "const-string v0, \"fast\"\nreturn-void"),
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        val messages = mutableListOf<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                messages += record.message
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(Fingerprint::class.java.name)
        logger.addHandler(handler)

        try {
            with(patcher.context.bytecodeContext) {
                val slowClass = Fingerprint(
                    strings = listOf("slow"),
                    custom = { _, _ ->
                        Thread.sleep(60)
                        true
                    },
                )

                // Not logged without validation.
                slowClass.match()
                assertTrue(messages.isEmpty())

                every { validate } returns true

                val fast = Fingerprint(strings = listOf("fast"))
                fast.match()
                assertTrue(messages.isEmpty())

                slowClass.clearMatch()
                slowClass.match()
                assertEquals(1, messages.size)
                assertTrue(messages.single().contains("ms to resolve"))

                // The time of the slow class fingerprint is not included in the outer fingerprint.
                messages.clear()
                slowClass.clearMatch()
                Fingerprint(classFingerprint = slowClass, name = "method1").match()
                assertEquals(1, messages.size)
                assertTrue(messages.single().contains("slow"))
            }
        } finally {
            logger.removeHandler(handler)
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

            withLegacyRawStrings {
                val legacyFingerprint = Fingerprint(strings = listOf("_other"))
                assertEquals(listOf("Lclass3;"), legacyFingerprint.matchAll().map { it.originalClassDef.type })
                assertEquals("Lclass3;", legacyFingerprint.match().originalClassDef.type)
            }
        }
    }

    @Test
    fun `fingerprints with strings only check classes that can have the strings`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(dexBackedMethod("Lclass1;", "method1", "const-string v0, \"first\"\nreturn-void")),
                "Lclass2;" to listOf(dexBackedMethod("Lclass2;", "method2", "return-void")),
                "Lclass3;" to listOf(dexBackedMethod("Lclass3;", "method3", "return-void")),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            var visitedMethods = 0
            fun fingerprint(vararg strings: String) = Fingerprint(
                strings = strings.toList(),
                custom = { _, _ -> visitedMethods++; true },
            )

            assertNull(fingerprint("first", "second").matchOrNull())
            assertNull(fingerprint("first", "second").matchAllOrNull())
            assertEquals(1, visitedMethods, "Only the class with the first string is checked")

            // Strings added to a mutable class are not in the indexes.
            patchClasses.classMap.getValue("Lclass3;").getMutableClass().methods.single()
                .addInstructions(0, "const-string v0, \"first\"\nconst-string v0, \"second\"")

            visitedMethods = 0
            assertEquals("Lclass3;", fingerprint("first", "second").match().originalClassDef.type)
            assertEquals(2, visitedMethods)
            assertEquals(
                listOf("Lclass3;"),
                fingerprint("first", "second").matchAll().map { it.originalClassDef.type },
            )
            assertEquals(
                listOf("Lclass1;", "Lclass3;"),
                fingerprint("first").matchAll().map { it.originalClassDef.type },
            )
        }
    }

    @Test
    fun `fingerprints do not match classes that were replaced`() {
        fun classWithString(methodName: String, string: String) = ImmutableClassDef(
            "Lclass1;", 0, null, null, null, null, null,
            listOf(dexBackedMethod("Lclass1;", methodName, "const-string v0, \"$string\"\nreturn-void")),
        )
        val patchClasses = PatchClasses(setOf(classWithString("old", "replaced")))
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses
        patchClasses.getClassesByReferenceMap()
        patchClasses.addClass(classWithString("new", "replacement"))

        with(patcher.context.bytecodeContext) {
            assertNull(Fingerprint(strings = listOf("replaced")).matchOrNull())
            assertNull(Fingerprint(filters = listOf(string("replaced"))).matchOrNull())
            assertNull(Fingerprint(filters = listOf(anyInstruction(string("replaced"), opcode(Opcode.NOP)))).matchOrNull())
            assertNull(Fingerprint(strings = listOf("replaced")).matchAllOrNull())
            assertEquals("new", Fingerprint(strings = listOf("replacement")).match().originalMethod.name)
        }

        val config = mockk<PatcherConfig>(relaxed = true) {
            every { patchedFiles } returns Files.createTempDirectory("morphe-test").toFile()
        }
        BytecodePatchContext(config, mockk(relaxed = true)).apply { this.patchClasses = patchClasses }.use { context ->
            fun List<ClassDef>.methodNames() = flatMap { classDef -> classDef.methods.map { it.name } }

            assertEquals(emptyList(), context.classDefByStrings("replaced"))
            assertEquals(listOf("new"), context.classDefByStrings("repl", StringComparisonType.STARTS_WITH).methodNames())
            assertEquals(listOf("new"), context.getAllClassesWithStrings().methodNames())
        }
    }

    @Test
    fun `anyInstruction filters check the classes of any of their filters`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(dexBackedMethod("Lclass1;", "method1", "const-string v0, \"first\"\nreturn-void")),
                "Lclass2;" to listOf(
                    dexBackedMethod("Lclass2;", "method2", "invoke-static { }, Lclass9;->calledMethod()V\nreturn-void")
                ),
                "Lclass3;" to listOf(dexBackedMethod("Lclass3;", "method3", "const v0, 0x5678\nreturn-void")),
                "Lclass4;" to listOf(dexBackedMethod("Lclass4;", "method4", "return-void")),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            var visitedMethods = 0
            fun fingerprint(vararg filters: InstructionFilter): Fingerprint {
                visitedMethods = 0
                return Fingerprint(filters = filters.toList(), custom = { _, _ -> visitedMethods++; true })
            }
            fun Fingerprint.matchedClasses() = matchAllOrNull()?.map { it.originalClassDef.type }

            assertEquals(
                listOf("Lclass1;", "Lclass2;", "Lclass3;"),
                fingerprint(
                    anyInstruction(string("first"), methodCall(name = "calledMethod"), literal(0x5678))
                ).matchedClasses(),
            )
            assertEquals(3, visitedMethods)

            assertEquals(
                listOf("Lclass1;", "Lclass2;"),
                fingerprint(
                    anyInstruction(string("first"), methodCall(name = "calledMethod")),
                    opcode(Opcode.RETURN_VOID),
                ).matchedClasses(),
            )
            assertEquals(2, visitedMethods)

            assertEquals(
                listOf("Lclass1;", "Lclass3;"),
                fingerprint(
                    anyInstruction(string("fir", StringComparisonType.STARTS_WITH), literal(0x5678))
                ).matchedClasses(),
            )
            assertEquals(2, visitedMethods)

            assertNull(fingerprint(anyInstruction(string("missing"), literal(0x9999))).matchedClasses())
            assertEquals(0, visitedMethods)
            assertNull(fingerprint(anyInstruction(string("missing"), literal(0x9999))).matchOrNull())
            assertEquals(0, visitedMethods)
        }
    }

    @Test
    fun `fingerprints find field and method references by the types of their signatures`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod("Lclass1;", "method1", "sget-object v0, Lclass9;->field:Lcom/test/FieldType;\nreturn-void")
                ),
                "Lclass2;" to listOf(
                    dexBackedMethod(
                        "Lclass2;",
                        "method2",
                        "invoke-static { v0, v1 }, Lclass9;->call(Lcom/test/ParameterType;I)Lcom/test/ReturnType;\n" +
                            "return-void",
                    )
                ),
                "Lclass3;" to listOf(dexBackedMethod("Lclass3;", "method3", "return-void")),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            var visitedMethods = 0
            fun matchedClasses(filter: InstructionFilter): List<String>? {
                visitedMethods = 0
                return Fingerprint(filters = listOf(filter), custom = { _, _ -> visitedMethods++; true })
                    .matchAllOrNull()?.map { it.originalClassDef.type }
            }

            assertEquals(listOf("Lclass1;"), matchedClasses(fieldAccess(type = "Lcom/test/FieldType;")))
            assertEquals(1, visitedMethods)
            assertEquals(listOf("Lclass2;"), matchedClasses(methodCall(returnType = "Lcom/test/ReturnType;")))
            assertEquals(1, visitedMethods)
            assertEquals(
                listOf("Lclass2;"),
                matchedClasses(methodCall(parameters = listOf("Lcom/test/ParameterType;", "I"))),
            )
            assertEquals(1, visitedMethods)
            assertNull(matchedClasses(fieldAccess(type = "Lcom/test/Missing;")))
            assertEquals(0, visitedMethods)

            // Partial and primitive types are not indexed.
            assertEquals(listOf("Lclass2;"), matchedClasses(methodCall(parameters = listOf("Lcom/test/", "I"))))
            assertEquals(3, visitedMethods)
        }
    }

    @Test
    fun `raw fingerprint strings use the raw string comparison`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(dexBackedMethod("Lclass1;", "method1", "const-string v0, \"prefix_value\"\nreturn-void")),
                "Lclass2;" to listOf(dexBackedMethod("Lclass2;", "method2", "const-string v0, \"value\"\nreturn-void")),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            fun matchedClasses() = Fingerprint(strings = listOf("value")).matchAll().map { it.originalClassDef.type }

            assertEquals(listOf("Lclass2;"), matchedClasses())
            assertNull(Fingerprint(strings = listOf("prefix_")).matchOrNull())

            withLegacyRawStrings {
                assertEquals(listOf("Lclass1;", "Lclass2;"), matchedClasses().sorted())
                assertEquals(
                    StringComparisonType.STARTS_WITH,
                    Fingerprint(strings = listOf(string("prefix_", StringComparisonType.STARTS_WITH)))
                        .stringFilters!!.single().comparison,
                    "declared string filters keep their comparison",
                )
            }
        }
    }

    @Test
    fun `fingerprints match unordered string filters`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod(
                        "Lclass1;",
                        "method1",
                        """
                            const-string v0, "prefix_value"
                            const-string v0, "exact"
                            const-string v0, "a_suffix"
                            return-void
                        """.trimIndent(),
                    )
                ),
                "Lclass2;" to listOf(
                    dexBackedMethod(
                        "Lclass2;",
                        "method2",
                        """
                            const-string v0, "exact_value"
                            const-string v0, "a_suffix"
                            return-void
                        """.trimIndent(),
                    )
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            val fingerprint = Fingerprint(
                strings = listOf(
                    string("_suffix", StringComparisonType.ENDS_WITH),
                    string("exact"),
                    string("prefix_", StringComparisonType.STARTS_WITH),
                )
            )
            val match = fingerprint.match()
            assertEquals("Lclass1;", match.originalClassDef.type)
            assertEquals(listOf("a_suffix", "exact", "prefix_value"), match.stringMatches.map { it.string })
            assertEquals(listOf(2, 1, 0), match.stringMatches.map { it.index })
            assertEquals(listOf("Lclass1;"), fingerprint.matchAll().map { it.originalClassDef.type })

            // Raw strings use contains, so "exact" also matches "exact_value".
            withLegacyRawStrings {
                val rawFingerprint = Fingerprint(strings = listOf("_suffix", "exact"))
                assertEquals(
                    listOf("Lclass1;", "Lclass2;"),
                    rawFingerprint.matchAll().map { it.originalClassDef.type }.sorted(),
                )
            }

            val noMatchFingerprint = Fingerprint(strings = listOf(string("exact"), string("missing")))
            assertNull(noMatchFingerprint.matchOrNull())
            assertNull(noMatchFingerprint.matchAllOrNull())
        }
    }

    @Test
    fun `legacy partial string matches are in declared order`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lclass1;" to listOf(
                    dexBackedMethod(
                        "Lclass1;",
                        "method1",
                        """
                            const-string v0, "first_value"
                            const-string v0, "second_value"
                            return-void
                        """.trimIndent(),
                    )
                ),
            )
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            withLegacyRawStrings {
                val fingerprint = Fingerprint(strings = listOf("second", "first"))
                assertEquals(listOf(1, 0), fingerprint.match().stringMatches.map { it.index })
            }
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

    @Test
    fun `fingerprint matches methods with Object signature types`() {
        val patchClasses = PatchClasses(
            dexBackedClasses(
                "Lcom/test/SubClass;" to listOf(
                    dexBackedMethod(
                        "Lcom/test/SubClass;",
                        "lookup",
                        """
                            sget-object v0, Lcom/test/Holder;->key:Ljava/lang/Object;
                            sget-object v1, Lcom/test/Holder;->map:Ljava/util/Map;
                            invoke-interface {v1, v0}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
                            return-void
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        every { patcher.context.bytecodeContext.patchClasses } returns patchClasses

        with(patcher.context.bytecodeContext) {
            val fingerprint = Fingerprint(
                returnType = "V",
                filters = listOf(
                    fieldAccess(type = "Ljava/lang/Object;"),
                    methodCall(
                        returnType = "Ljava/lang/Object;",
                        parameters = listOf("Ljava/lang/Object;"),
                    ),
                ),
            )
            val match = fingerprint.matchOrNull()
            assertNotNull(match)
            assertEquals("lookup", match.method.name)
        }
    }

    private operator fun Set<Patch<*>>.invoke(): List<PatchResult> {
        every { patcher.context.executablePatches } returns toMutableSet()
        every { with(patcher.context.bytecodeContext) { mergeExtension(any<BytecodePatch>()) } } just runs

        return runBlocking { patcher().toList() }
    }

    private operator fun Patch<*>.invoke() = setOf(this)().first()

    /**
     * Compares raw fingerprint strings as patches built for a version older than 2.0.0 expect.
     */
    private inline fun withLegacyRawStrings(block: () -> Unit) {
        Fingerprint.rawStringComparison = StringComparisonType.CONTAINS
        try {
            block()
        } finally {
            Fingerprint.rawStringComparison = StringComparisonType.EQUALS
        }
    }

    /**
     * Matches instruction filters as patches built for a version older than 2.0.0 expect.
     */
    private inline fun withFirstFilterRetriedOnly(block: () -> Unit) {
        Fingerprint.retryAllFilters = false
        try {
            block()
        } finally {
            Fingerprint.retryAllFilters = true
        }
    }

    private fun Any.setPrivateField(field: String, value: Any) {
        this::class.java.getDeclaredField(field).apply {
            this.isAccessible = true
            set(this@setPrivateField, value)
        }
    }
}
