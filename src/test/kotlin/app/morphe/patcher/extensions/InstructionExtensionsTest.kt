@file:Suppress("DEPRECATION_ERROR")

package app.morphe.patcher.extensions

import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object InstructionExtensionsTest {
    private lateinit var testMethod: MutableMethod
    private lateinit var testMethodImplementation: MutableMethodImplementation

    @BeforeEach
    fun createTestMethod() =
        ImmutableMethod(
            "TestClass;",
            "testMethod",
            null,
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(16).also { testMethodImplementation = it }.apply {
                repeat(10) { i -> this.addInstruction(TestInstruction(i)) }
            },
        ).let { testMethod = it.toMutable() }

    @Test
    fun addInstructionsToImplementationIndexed() =
        applyToImplementation {
            addInstructions(5, getTestInstructions(5..6)).also {
                assertRegisterIs(5, 5)
                assertRegisterIs(6, 6)

                assertRegisterIs(5, 7)
            }
        }

    @Test
    fun addInstructionsToImplementation() =
        applyToImplementation {
            addInstructions(getTestInstructions(10..11)).also {
                assertRegisterIs(10, 10)
                assertRegisterIs(11, 11)
            }
        }

    @Test
    fun removeInstructionsFromImplementationIndexed() =
        applyToImplementation {
            removeInstructions(5, 5).also { assertRegisterIs(4, 4) }
        }

    @Test
    fun removeInstructionsFromImplementation() =
        applyToImplementation {
            removeInstructions(0).also { assertRegisterIs(9, 9) }
            removeInstructions(1).also { assertRegisterIs(1, 0) }
            removeInstructions(2).also { assertRegisterIs(3, 0) }
        }

    @Test
    fun replaceInstructionsInImplementationIndexed() =
        applyToImplementation {
            replaceInstructions(5, getTestInstructions(0..1)).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(7, 7)
            }
        }

    @Test
    fun addInstructionToMethodIndexed() =
        applyToMethod {
            addInstruction(5, TestInstruction(0)).also { assertRegisterIs(0, 5) }
        }

    @Test
    fun addInstructionToMethod() =
        applyToMethod {
            addInstruction(TestInstruction(0)).also { assertRegisterIs(0, 10) }
        }

    @Test
    fun addSmaliInstructionToMethodIndexed() =
        applyToMethod {
            addInstruction(5, getTestSmaliInstruction(0)).also { assertRegisterIs(0, 5) }
        }

    @Test
    fun addSmaliInstructionToMethod() =
        applyToMethod {
            addInstruction(getTestSmaliInstruction(0)).also { assertRegisterIs(0, 10) }
        }

    @Test
    fun addInstructionsToMethodIndexed() =
        applyToMethod {
            addInstructions(5, getTestInstructions(0..1)).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)

                assertRegisterIs(5, 7)
            }
        }

    @Test
    fun addInstructionsToMethod() =
        applyToMethod {
            addInstructions(getTestInstructions(0..1)).also {
                assertRegisterIs(0, 10)
                assertRegisterIs(1, 11)

                assertRegisterIs(9, 9)
            }
        }

    @Test
    fun addSmaliInstructionsToMethodIndexed() =
        applyToMethod {
            addInstructionsWithLabels(5, getTestSmaliInstructions(0..1)).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)

                assertRegisterIs(5, 7)
            }
        }

    @Test
    fun addSmaliInstructionsToMethod() =
        applyToMethod {
            addInstructions(implementation!!.instructions.size, getTestSmaliInstructions(0..1)).also {
                assertRegisterIs(0, 10)
                assertRegisterIs(1, 11)

                assertRegisterIs(9, 9)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelToMethodIndexed() =
        applyToMethod {
            val label = ExternalLabel("testLabel", getInstruction(5))

            addInstructionsWithLabels(
                5,
                getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                label,
            ).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(5, 8)

                val gotoTarget =
                    getInstruction<BuilderOffsetInstruction>(7)
                        .target.location.instruction as OneRegisterInstruction

                assertEquals(5, gotoTarget.registerA)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelTargetIndexAfterInsert() =
        applyToMethod {
            val label = ExternalLabel("testLabel", 5)

            addInstructionsWithLabels(
                5,
                getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                label,
            ).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(5, 8)

                val gotoTarget =
                    getInstruction<BuilderOffsetInstruction>(7)
                        .target.location.instruction as OneRegisterInstruction

                assertEquals(5, gotoTarget.registerA)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelTargetIndexBeforeInsert() =
        applyToMethod {
            val label = ExternalLabel("testLabel", 2)

            addInstructionsWithLabels(
                5,
                getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                label,
            ).also {
                val gotoTarget =
                    getInstruction<BuilderOffsetInstruction>(7)
                        .target.location.instruction as OneRegisterInstruction

                assertEquals(2, gotoTarget.registerA)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelInstructionMatch() =
        applyToMethod {
            val instructionMatch = Match.InstructionMatch(opcode(Opcode.CONST_16), 5, getInstruction(5))
            val label = ExternalLabel("testLabel", instructionMatch)

            addInstructionsWithLabels(
                5,
                getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                label,
            ).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(5, 8)

                val gotoTarget =
                    getInstruction<BuilderOffsetInstruction>(7)
                        .target.location.instruction as OneRegisterInstruction

                assertEquals(5, gotoTarget.registerA)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelImmutableInstruction() =
        applyToMethod {
            // Instruction from immutable sourceMethod (e.g. as returned by Fingerprint match)
            val immutableInstruction = sourceMethod.implementation!!.instructions.elementAt(5)
            val label = ExternalLabel("testLabel", immutableInstruction)

            addInstructionsWithLabels(
                5,
                getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                label,
            ).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(5, 8)

                val gotoTarget =
                    getInstruction<BuilderOffsetInstruction>(7)
                        .target.location.instruction as OneRegisterInstruction

                assertEquals(5, gotoTarget.registerA)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelInvalidInstructionThrows() =
        applyToMethod {
            val foreignInstruction = TestInstruction(99)
            val label = ExternalLabel("testLabel", foreignInstruction)

            assertFailsWith<PatchException> {
                addInstructionsWithLabels(
                    5,
                    getTestSmaliInstructions(0..1).plus("\n").plus("goto :${label.name}"),
                    label,
                )
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelImmutableInstructionAfterChangeThrows() =
        applyToMethod {
            val immutableInstruction = sourceMethod.implementation!!.instructions.elementAt(5)
            val label = ExternalLabel("testLabel", immutableInstruction)

            // The source method indices no longer match the indices of the changed method.
            addInstructions(0, getTestSmaliInstruction(0))

            assertFailsWith<PatchException> {
                addInstructionsWithLabels(5, "goto :${label.name}", label)
            }
        }

    @Test
    fun addSmaliInstructionsWithExternalLabelTargetIndexOutOfBoundsThrows() =
        applyToMethod {
            val label = ExternalLabel("testLabel", 10)

            assertFailsWith<PatchException> {
                addInstructionsWithLabels(5, "goto :${label.name}", label)
            }

            // The method is not changed if a label is invalid.
            assertEquals(10, implementation!!.instructions.size)
        }

    @Test
    fun removeInstructionFromMethodIndexed() =
        applyToMethod {
            removeInstruction(5).also {
                assertRegisterIs(4, 4)
                assertRegisterIs(6, 5)
            }
        }

    @Test
    fun removeInstructionsFromMethodIndexed() =
        applyToMethod {
            removeInstructions(5, 5).also { assertRegisterIs(4, 4) }
        }

    @Test
    fun removeInstructionsFromMethod() =
        applyToMethod {
            removeInstructions(0).also { assertRegisterIs(9, 9) }
            removeInstructions(1).also { assertRegisterIs(1, 0) }
            removeInstructions(2).also { assertRegisterIs(3, 0) }
        }

    @Test
    fun replaceInstructionInMethodIndexed() =
        applyToMethod {
            replaceInstruction(5, TestInstruction(0)).also { assertRegisterIs(0, 5) }
        }

    @Test
    fun replaceInstructionsInMethodIndexed() =
        applyToMethod {
            replaceInstructions(5, getTestInstructions(0..1)).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(7, 7)
            }
        }

    @Test
    fun replaceSmaliInstructionsInMethodIndexed() =
        applyToMethod {
            replaceInstructions(5, getTestSmaliInstructions(0..1)).also {
                assertRegisterIs(0, 5)
                assertRegisterIs(1, 6)
                assertRegisterIs(7, 7)
            }
        }

    // region Helper methods

    private fun applyToImplementation(block: MutableMethodImplementation.() -> Unit) {
        testMethodImplementation.apply(block)
    }

    private fun applyToMethod(block: MutableMethod.() -> Unit) {
        testMethod.apply(block)
    }

    private fun MutableMethodImplementation.assertRegisterIs(
        register: Int,
        atIndex: Int,
    ) = assertEquals(
        register,
        getInstruction<OneRegisterInstruction>(atIndex).registerA,
    )

    private fun MutableMethod.assertRegisterIs(
        register: Int,
        atIndex: Int,
    ) = implementation!!.assertRegisterIs(register, atIndex)

    private fun getTestInstructions(range: IntRange) = range.map { TestInstruction(it) }

    private fun getTestSmaliInstruction(register: Int) = "const/16 v$register, 0"

    private fun getTestSmaliInstructions(range: IntRange) =
        range.joinToString("\n") {
            getTestSmaliInstruction(it)
        }

    // endregion

    private class TestInstruction(register: Int) : BuilderInstruction21s(Opcode.CONST_16, register, 0)
}
