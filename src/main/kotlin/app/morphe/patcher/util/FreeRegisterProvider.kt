/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.util.FreeRegisterProvider.Companion.conditionalBranchOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.logFreeRegisterSearch
import app.morphe.patcher.util.FreeRegisterProvider.Companion.readModifyWriteOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.returnOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.switchOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.unconditionalBranchOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.wideFirstRegisterReadOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.wideSecondRegisterOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.wideThirdRegisterOpcodes
import app.morphe.patcher.util.FreeRegisterProvider.Companion.writeOpcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcode.*
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.util.EnumSet

/**
 * Finds free registers at a specific index in a method.
 * Allows allocating multiple free registers for a given index.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 *
 * @param index Index you need to use a free register at.
 * @param numberOfFreeRegistersNeeded The minimum free registers to find.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 *
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every
 *                                  register before any registers are written to, or if the paths of
 *                                  a branch or switch statement have no common free registers.
 */
fun Method.getFreeRegisterProvider(index: Int, numberOfFreeRegistersNeeded: Int, registersToExclude: List<Int>) =
    FreeRegisterProvider(this, index, numberOfFreeRegistersNeeded, registersToExclude)

/**
 * Finds free registers at a specific index in a method.
 * Allows allocating multiple free registers for a given index.
 *
 * If you only need a single free register, instead use [findFreeRegister].
 *
 * @param index Index you need to use a free register at.
 * @param numberOfFreeRegistersNeeded The minimum free registers to find.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 *
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every
 *                                  register before any registers are written to, or if the paths of
 *                                  a branch or switch statement have no common free registers.
 */
fun Method.getFreeRegisterProvider(index: Int, numberOfFreeRegistersNeeded: Int, vararg registersToExclude: Int) =
    FreeRegisterProvider(this, index, numberOfFreeRegistersNeeded, *registersToExclude)

@Suppress("unused")
class FreeRegisterProvider internal constructor(
    val method: Method,
    index: Int,
    numberOfFreeRegistersNeeded: Int,
    registersToExclude: List<Int>
) {
    internal constructor(
        method: Method,
        index: Int,
        numberOfFreeRegistersNeeded: Int,
        vararg registersToExclude: Int
    ) : this(method, index, numberOfFreeRegistersNeeded, registersToExclude.toList())

    // Cannot use MutableList because removeFirst() crashes on older Android devices.
    // https://youtrack.jetbrains.com/issue/KT-71375/
    private var freeRegisters: ArrayDeque<Int> = ArrayDeque(
        method.findFreeRegisters(index, numberOfFreeRegistersNeeded, registersToExclude)
    )

    private val originallyExcludedRegisters = registersToExclude
    private val allocatedFreeRegisters = mutableListOf<Int>()

    /**
     * Returns a free register and removes it from the available list.
     *
     * @return A free register number
     * @throws IllegalStateException if no free registers are available
     */
    fun getFreeRegister(): Int {
        if (freeRegisters.isEmpty()) {
            throw IllegalStateException("No free registers available")
        }
        val register = freeRegisters.removeFirst()
        allocatedFreeRegisters.add(register)
        return register
    }

    /**
     * Returns a free 4-bit register (v0-v15) and removes it from the available list.
     *
     * @return A free register number
     * @throws IllegalStateException if no free 4-bit registers are available.
     */
    fun getFreeRegister4Bit(): Int {
        val register = getFreeRegister()
        if (register >= 16) {
            throw IllegalStateException("Lowest free register is: $register")
        }
        return register
    }

    /**
     * Returns all registers that have been allocated via [getFreeRegister].
     * This does not include the originally excluded registers.
     *
     * @return List of registers that have been allocated, in allocation order
     */
    fun getAllocatedFreeRegisters(): List<Int> = allocatedFreeRegisters.toList()

    /**
     * Returns all registers that are considered "in use" or unsafe to use. This includes the
     * excluded registers originally passed in, all registers that are unsuitable to use
     * (registers are read from by the original code), and all free registers previously provided
     * by this class using [getFreeRegister].
     *
     * @return List of all registers that are unsafe to use at this time.
     */
    fun getUsedAndUnAvailableRegisters(): List<Int> {
        val allRegisters = 0 until method.implementation!!.registerCount
        return (allocatedFreeRegisters + originallyExcludedRegisters + (allRegisters - freeRegisters.toSet()))
            .distinct()
    }

    /**
     * @return The number of free registers still available.
     */
    fun availableCount(): Int = freeRegisters.size

    /**
     * Checks if there are any free registers available.
     */
    fun hasFreeRegisters(): Boolean = freeRegisters.isNotEmpty()

    internal companion object {
        val conditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
            IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
            IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ
        )

        val unconditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
            GOTO, GOTO_16, GOTO_32
        )

        val switchOpcodes: EnumSet<Opcode> = EnumSet.of(
            PACKED_SWITCH, SPARSE_SWITCH
        )

        val returnOpcodes: EnumSet<Opcode> = EnumSet.of(
            RETURN_VOID, RETURN, RETURN_WIDE, RETURN_OBJECT, RETURN_VOID_NO_BARRIER,
            THROW
        )

        val writeOpcodes: EnumSet<Opcode> = EnumSet.of(
            ARRAY_LENGTH,
            INSTANCE_OF,
            NEW_INSTANCE, NEW_ARRAY,
            MOVE, MOVE_FROM16, MOVE_16, MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16, MOVE_OBJECT,
            MOVE_OBJECT_FROM16, MOVE_OBJECT_16, MOVE_RESULT, MOVE_RESULT_WIDE, MOVE_RESULT_OBJECT, MOVE_EXCEPTION,
            CONST, CONST_4, CONST_16, CONST_HIGH16, CONST_WIDE_16, CONST_WIDE_32,
            CONST_WIDE, CONST_WIDE_HIGH16, CONST_STRING, CONST_STRING_JUMBO,
            CONST_CLASS, CONST_METHOD_HANDLE, CONST_METHOD_TYPE,
            CMP_LONG, CMPL_FLOAT, CMPG_FLOAT, CMPL_DOUBLE, CMPG_DOUBLE,
            IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT,
            IGET_VOLATILE, IGET_WIDE_VOLATILE, IGET_OBJECT_VOLATILE,
            SGET, SGET_WIDE, SGET_OBJECT, SGET_BOOLEAN, SGET_BYTE, SGET_CHAR, SGET_SHORT,
            SGET_VOLATILE, SGET_WIDE_VOLATILE, SGET_OBJECT_VOLATILE,
            AGET, AGET_WIDE, AGET_OBJECT, AGET_BOOLEAN, AGET_BYTE, AGET_CHAR, AGET_SHORT,
            // Arithmetic and logical operations.
            ADD_DOUBLE_2ADDR, ADD_DOUBLE, ADD_FLOAT_2ADDR, ADD_FLOAT, ADD_INT_2ADDR,
            ADD_INT_LIT8, ADD_INT, ADD_LONG_2ADDR, ADD_LONG, ADD_INT_LIT16,
            AND_INT_2ADDR, AND_INT_LIT8, AND_INT_LIT16, AND_INT, AND_LONG_2ADDR, AND_LONG,
            DIV_DOUBLE_2ADDR, DIV_DOUBLE, DIV_FLOAT_2ADDR, DIV_FLOAT, DIV_INT_2ADDR,
            DIV_INT_LIT16, DIV_INT_LIT8, DIV_INT, DIV_LONG_2ADDR, DIV_LONG,
            DOUBLE_TO_FLOAT, DOUBLE_TO_INT, DOUBLE_TO_LONG,
            FLOAT_TO_DOUBLE, FLOAT_TO_INT, FLOAT_TO_LONG,
            INT_TO_BYTE, INT_TO_CHAR, INT_TO_DOUBLE, INT_TO_FLOAT, INT_TO_LONG, INT_TO_SHORT,
            LONG_TO_DOUBLE, LONG_TO_FLOAT, LONG_TO_INT,
            MUL_DOUBLE_2ADDR, MUL_DOUBLE, MUL_FLOAT_2ADDR, MUL_FLOAT, MUL_INT_2ADDR,
            MUL_INT_LIT16, MUL_INT_LIT8, MUL_INT, MUL_LONG_2ADDR, MUL_LONG,
            NEG_DOUBLE, NEG_FLOAT, NEG_INT, NEG_LONG,
            NOT_INT, NOT_LONG,
            OR_INT_2ADDR, OR_INT_LIT16, OR_INT_LIT8, OR_INT, OR_LONG_2ADDR, OR_LONG,
            REM_DOUBLE_2ADDR, REM_DOUBLE, REM_FLOAT_2ADDR, REM_FLOAT, REM_INT_2ADDR,
            REM_INT_LIT16, REM_INT_LIT8, REM_INT, REM_LONG_2ADDR, REM_LONG,
            RSUB_INT_LIT8, RSUB_INT,
            SHL_INT_2ADDR, SHL_INT_LIT8, SHL_INT, SHL_LONG_2ADDR, SHL_LONG,
            SHR_INT_2ADDR, SHR_INT_LIT8, SHR_INT, SHR_LONG_2ADDR, SHR_LONG,
            SUB_DOUBLE_2ADDR, SUB_DOUBLE, SUB_FLOAT_2ADDR, SUB_FLOAT, SUB_INT_2ADDR,
            SUB_INT, SUB_LONG_2ADDR, SUB_LONG,
            USHR_INT_2ADDR, USHR_INT_LIT8, USHR_INT, USHR_LONG_2ADDR, USHR_LONG,
            XOR_INT_2ADDR, XOR_INT_LIT16, XOR_INT_LIT8, XOR_INT, XOR_LONG_2ADDR, XOR_LONG,
        )

        /**
         * Write opcodes that also read the register they write to,
         * but list that register only once.
         */
        val readModifyWriteOpcodes: EnumSet<Opcode> = EnumSet.of(
            ADD_DOUBLE_2ADDR, ADD_FLOAT_2ADDR, ADD_INT_2ADDR, ADD_LONG_2ADDR,
            AND_INT_2ADDR, AND_LONG_2ADDR,
            DIV_DOUBLE_2ADDR, DIV_FLOAT_2ADDR, DIV_INT_2ADDR, DIV_LONG_2ADDR,
            MUL_DOUBLE_2ADDR, MUL_FLOAT_2ADDR, MUL_INT_2ADDR, MUL_LONG_2ADDR,
            OR_INT_2ADDR, OR_LONG_2ADDR,
            REM_DOUBLE_2ADDR, REM_FLOAT_2ADDR, REM_INT_2ADDR, REM_LONG_2ADDR,
            SHL_INT_2ADDR, SHL_LONG_2ADDR,
            SHR_INT_2ADDR, SHR_LONG_2ADDR,
            SUB_DOUBLE_2ADDR, SUB_FLOAT_2ADDR, SUB_INT_2ADDR, SUB_LONG_2ADDR,
            USHR_INT_2ADDR, USHR_LONG_2ADDR,
            XOR_INT_2ADDR, XOR_LONG_2ADDR,
        )

        /**
         * Opcodes that read a wide (long or double) value from their first register.
         * Wide writes to the first register are identified by [Opcode.setsWideRegister].
         */
        val wideFirstRegisterReadOpcodes: EnumSet<Opcode> = EnumSet.of(
            RETURN_WIDE,
            APUT_WIDE,
            IPUT_WIDE, IPUT_WIDE_VOLATILE,
            SPUT_WIDE, SPUT_WIDE_VOLATILE,
        )

        /**
         * Opcodes whose second register is a wide (long or double) value.
         */
        val wideSecondRegisterOpcodes: EnumSet<Opcode> = EnumSet.of(
            MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16,
            NEG_LONG, NOT_LONG, NEG_DOUBLE,
            LONG_TO_INT, LONG_TO_FLOAT, LONG_TO_DOUBLE,
            DOUBLE_TO_INT, DOUBLE_TO_LONG, DOUBLE_TO_FLOAT,
            CMP_LONG, CMPL_DOUBLE, CMPG_DOUBLE,
            ADD_LONG, SUB_LONG, MUL_LONG, DIV_LONG, REM_LONG,
            AND_LONG, OR_LONG, XOR_LONG, SHL_LONG, SHR_LONG, USHR_LONG,
            ADD_DOUBLE, SUB_DOUBLE, MUL_DOUBLE, DIV_DOUBLE, REM_DOUBLE,
            // Shift 2addr opcodes are not included, since the second register is the int shift amount.
            ADD_LONG_2ADDR, SUB_LONG_2ADDR, MUL_LONG_2ADDR, DIV_LONG_2ADDR, REM_LONG_2ADDR,
            AND_LONG_2ADDR, OR_LONG_2ADDR, XOR_LONG_2ADDR,
            ADD_DOUBLE_2ADDR, SUB_DOUBLE_2ADDR, MUL_DOUBLE_2ADDR, DIV_DOUBLE_2ADDR, REM_DOUBLE_2ADDR,
        )

        /**
         * Opcodes whose third register is a wide (long or double) value.
         */
        val wideThirdRegisterOpcodes: EnumSet<Opcode> = EnumSet.of(
            CMP_LONG, CMPL_DOUBLE, CMPG_DOUBLE,
            // Shift opcodes are not included, since the third register is the int shift amount.
            ADD_LONG, SUB_LONG, MUL_LONG, DIV_LONG, REM_LONG,
            AND_LONG, OR_LONG, XOR_LONG,
            ADD_DOUBLE, SUB_DOUBLE, MUL_DOUBLE, DIV_DOUBLE, REM_DOUBLE,
        )

        /**
         * For debugging and development.
         */
        internal const val logFreeRegisterSearch = false
    }
}

/**
 * Starting from and including the instruction at index [index],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method should work for all situations including inserting at a branch or switch statement,
 * but this may not work if the branch paths have no common free registers.
 *
 * If you need multiple free registers, then instead use [Method.getFreeRegisterProvider].
 *
 * @param index Index you need to use a free register at.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return A free register at the given index. 4-bit registers are preferred over larger registers.
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are written to, or if the index is at/before a branch
 *                                  or switch statement and the method has an unusually high amount of branching
 *                                  where no common free registers exist in all branch paths.
 */
fun Method.findFreeRegister(
    index: Int,
    vararg registersToExclude: Int
) = findFreeRegisters(
    startIndex = index,
    numberOfFreeRegistersNeeded = 1,
    registersToExclude = registersToExclude.toList()
).first()

/**
 * Starting from and including the instruction at index [index],
 * finds the next register that is written to and not read from. If a return instruction
 * is encountered, then the lowest unused register is returned.
 *
 * This method should work for all situations including inserting at a branch or switch statement,
 * but this may not work if the branch paths have no common free registers.
 *
 * If you need multiple free registers, then instead use [Method.getFreeRegisterProvider].
 *
 * @param index Index you need to use a free register at.
 * @param registersToExclude Registers to exclude, and consider as used. For most use cases,
 *                           all registers used in injected code should be specified.
 * @return A free register at the given index. 4-bit registers are preferred over larger registers.
 * @throws IllegalArgumentException If no free registers can be found at the given index.
 *                                  This includes unusual method indexes that read from every register
 *                                  before any registers are written to, or if the index is at/before a branch
 *                                  or switch statement and the method has an unusually high amount of branching
 *                                  where no common free registers exist in all branch paths.
 */
fun Method.findFreeRegister(
    index: Int,
    registersToExclude: List<Int>
) = findFreeRegisters(
    startIndex = index,
    numberOfFreeRegistersNeeded = 1,
    registersToExclude = registersToExclude
).first()

private fun Method.findFreeRegisters(
    startIndex: Int,
    numberOfFreeRegistersNeeded: Int,
    registersToExclude: List<Int>
): List<Int> {
    if (logFreeRegisterSearch) println(" Searching startIndex: $startIndex method: $this")

    val freeRegisters = findFreeRegistersInternal(
        startIndex = startIndex,
        numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
        currentDepth = 0,
        foundFreeRegistersAtIndex = mutableMapOf(),
        registersToExclude = registersToExclude,
        offsetArray = instructionCodeOffsets()
    )

    if (freeRegisters.isEmpty()) {
        // Should only happen if the branch paths have no common free registers.
        throw IllegalArgumentException("Could not find a free register from startIndex: " +
                "$startIndex excluding: $registersToExclude")
    }

    if (logFreeRegisterSearch) println(" final free registers found: $freeRegisters")

    // Use 4-bit registers first, but keep sorting stable among 4-bit vs not 4-bit.
    return freeRegisters.sortedWith { first, second ->
        val firstIsFourBit = first < 16
        val secondIsFourBit = second < 16
        if (firstIsFourBit == secondIsFourBit) {
            return@sortedWith 0
        }

        if (firstIsFourBit) -1 else 1
    }
}

/**
 * Returns all free registers found starting from [startIndex]. Follows all branch and switch paths.
 *
 * @param startIndex Inclusive starting index.
 * @param numberOfFreeRegistersNeeded The minimum free registers to ensure will be returned.
 * @param currentDepth Current branching depth. Value of zero means no branching has been followed yet.
 * @param foundFreeRegistersAtIndex Map of instruction index to list of free registers previously found.
 * @param registersToExclude Registers to exclude from consideration.
 * @param offsetArray Map from instruction index to code offset.
 * @return List of all free registers found.
 */
private fun Method.findFreeRegistersInternal(
    startIndex: Int,
    numberOfFreeRegistersNeeded: Int,
    currentDepth: Int,
    foundFreeRegistersAtIndex: MutableMap<Int, Set<Int>?>,
    registersToExclude: List<Int>,
    offsetArray: IntArray
): List<Int> {
    check(implementation != null) {
        "Method has no implementation: $this"
    }
    check(startIndex >= 0 && startIndex < instructions.count()) {
        "startIndex out of bounds: $startIndex methodInstructionCount: ${instructions.count()}"
    }
    check(numberOfFreeRegistersNeeded > 0) {
        "numberOfFreeRegistersNeeded must be greater than zero: $numberOfFreeRegistersNeeded"
    }

    fun Collection<Int>.numberOf4BitRegisters() = this.count { it < 16 }

    foundFreeRegistersAtIndex[startIndex]?.let {
        // Recursive call to a branch index that has already been explored.
        return (it - registersToExclude.toSet()).toList()
    }

    val usedRegisters = registersToExclude.toMutableSet()
    // The map holds this set, so registers added to it after exploring branches are also
    // available to later visits of this index. A loop back to this index while it is still
    // being explored sees only the registers found so far, which every path from here writes to.
    val freeRegisters = mutableSetOf<Int>()
    foundFreeRegistersAtIndex[startIndex] = freeRegisters

    for (i in startIndex until instructions.count()) {
        val instruction = getInstruction(i)
        val instructionRegisters = instruction.registersAccessed

        // Check for write-only registers.
        // 2addr opcodes always read the register they write to.
        val writeRegister = instruction.writeRegister
        if (writeRegister != null && instruction.opcode !in readModifyWriteOpcodes) {
            // A wide write replaces both registers of the pair.
            val writtenRegisters = if (instruction.opcode.setsWideRegister()) {
                listOf(writeRegister, writeRegister + 1)
            } else {
                listOf(writeRegister)
            }

            for (register in writtenRegisters) {
                if (register in usedRegisters) continue

                // Check if this register is ONLY written to (not also read)
                // If it appears only once, it's write-only (to write).
                // If it appears more than once, it's also read.
                val occurrences = instructionRegisters.count { it == register }
                if (occurrences > 1) continue

                if (logFreeRegisterSearch) println(" found free register at $i: $register " +
                        "opcode: " + instruction.opcode + " reference: " + (instruction as? ReferenceInstruction)?.reference)
                freeRegisters.add(register)
                // If the requested number of free registers is found and this is not a branch,
                // then no additional searching is needed.
                // But if this is a branch, then this all free registers should be found
                // because the intersection of free registers from different branches may be
                // less than the requested number of registers.
                if (currentDepth == 0 && freeRegisters.numberOf4BitRegisters() >= numberOfFreeRegistersNeeded) {
                    return freeRegisters.toList()
                }
            }
        }

        // Mark all registers used by this instruction as "used".
        usedRegisters.addAll(instructionRegisters)

        // If we hit a return, all unused registers on this path are free.
        if (instruction.isReturnInstruction) {
            val allRegisters = (0 until implementation!!.registerCount).toList()
            val unusedRegisters = allRegisters - usedRegisters
            freeRegisters.addAll(unusedRegisters)
            if (logFreeRegisterSearch) println(" encountered return index: $i and found: $freeRegisters")
            return freeRegisters.toList()
        }

        if (instruction.isSwitchInstruction) {
            if (logFreeRegisterSearch) println(" encountered switch index: $i opcode: " + instruction.opcode)
            val payloadIndex = getBranchTargetInstructionIndex(instruction, i, offsetArray)
            val payload = getInstruction(payloadIndex) as SwitchPayload

            // Case offsets are relative to the switch instruction, not the payload.
            val targetIndices = payload.switchElements.map {
                findInstructionIndexByOffset(offsetArray[i] + it.offset, offsetArray)
            } + (i + 1) // No case matched.

            // A register is free only if it is free on every path.
            val usedRegistersList = usedRegisters.toList()
            val commonFreeRegisters = targetIndices.distinct().map { targetIndex ->
                findFreeRegistersInternal(
                    startIndex = targetIndex,
                    numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                    currentDepth = currentDepth + 1,
                    foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                    registersToExclude = usedRegistersList,
                    offsetArray = offsetArray
                ).toSet()
            }.reduce { common, registers -> common intersect registers }
            if (logFreeRegisterSearch) println(" switch registers: $commonFreeRegisters")

            freeRegisters.addAll(commonFreeRegisters)
            return freeRegisters.toList()
        }

        if (instruction.isUnconditionalBranchInstruction) {
            if (logFreeRegisterSearch) println(" encountered unconditional branch index: $i opcode: " + instruction.opcode)

            // Continue searching from the go-to index.
            freeRegisters.addAll(findFreeRegistersInternal(
                startIndex = getBranchTargetInstructionIndex(instruction, i, offsetArray),
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth, // Same depth since it's a continuation of single path.
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegisters.toList(),
                offsetArray = offsetArray
            ))
            return freeRegisters.toList()
        }

        if (instruction.isConditionalBranchInstruction) {
            if (logFreeRegisterSearch) println(" encountered conditional branch index: $i opcode: " + instruction.opcode)
            val usedRegistersList = usedRegisters.toList()

            val branchFreeRegisters = findFreeRegistersInternal(
                startIndex = getBranchTargetInstructionIndex(instruction, i, offsetArray),
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth + 1,
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegistersList,
                offsetArray = offsetArray
            )
            if (logFreeRegisterSearch) println(" branch registers: $branchFreeRegisters")

            val fallThruFreeRegisters = findFreeRegistersInternal(
                startIndex = i + 1,
                numberOfFreeRegistersNeeded = numberOfFreeRegistersNeeded,
                currentDepth = currentDepth + 1,
                foundFreeRegistersAtIndex = foundFreeRegistersAtIndex,
                registersToExclude = usedRegistersList,
                offsetArray = offsetArray
            )
            if (logFreeRegisterSearch) println(" fall thru registers: $fallThruFreeRegisters")

            freeRegisters.addAll(branchFreeRegisters.intersect(fallThruFreeRegisters.toSet()))
            return freeRegisters.toList()
        }
    }

    // A return or branch instruction will be encountered before all instructions can be iterated.
    // Some methods have switch payload instructions after the last actual instruction,
    // but these cannot be reached through normal control flow.
    throw IllegalArgumentException("Start index is outside normal control flow: $startIndex")
}

/**
 * Returns an instruction index for a given branch instruction.
 *
 * @param instruction The branch instruction
 * @param index Current instruction index
 * @param offsetArray Array mapping instruction index to code offset.
 */
private fun Method.getBranchTargetInstructionIndex(
    instruction: Instruction,
    index: Int,
    offsetArray: IntArray
): Int {
    check (index >= 0 && index < offsetArray.size) {
        "Invalid index: $index"
    }
    val currentOffset = offsetArray[index]

    return when (instruction.opcode) {
        GOTO, GOTO_16, GOTO_32,
        IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
        IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ,
        // Switch opcodes target the payload that holds the case targets.
        PACKED_SWITCH, SPARSE_SWITCH -> {
            val offset = (instruction as OffsetInstruction).codeOffset
            val targetOffset = currentOffset + offset
            // Find the instruction index at this offset.
            findInstructionIndexByOffset(targetOffset, offsetArray)
        }
        else -> throw IllegalStateException("Unsupported opcode: ${instruction.opcode}")
    }
}

/**
 * Finds the instruction index for a given code offset.
 *
 * @param targetOffset Target code offset in 16-bit units
 * @param offsetArray Array mapping instruction index to code offset.
 * @return Instruction index at the target offset.
 * @throws IllegalArgumentException If no instruction starts at the target offset.
 */
private fun Method.findInstructionIndexByOffset(
    targetOffset: Int,
    offsetArray: IntArray
): Int {
    // Simple linear search using indexOfFirst
    val index = offsetArray.indexOfFirst { it == targetOffset }
    if (index >= 0) {
        return index
    }

    // Should never happen.
    // Code has been tested on hundreds of random methods on all instruction indices,
    // but maybe some weird code exists that this has overlooked.
    throw IllegalArgumentException("Could not find exact instruction offset for method: " +
            "$this at offset: $targetOffset. Please file a bug report in the Morphe patcher repo"
    )
}

/**
 * @return The registers used by this instruction.
 */
val Instruction.registersUsed: List<Int>
    get() = when (this) {
        is FiveRegisterInstruction -> {
            when (registerCount) {
                0 -> listOf()
                1 -> listOf(registerC)
                2 -> listOf(registerC, registerD)
                3 -> listOf(registerC, registerD, registerE)
                4 -> listOf(registerC, registerD, registerE, registerF)
                else -> listOf(registerC, registerD, registerE, registerF, registerG)
            }
        }

        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        is RegisterRangeInstruction -> (startRegister until (startRegister + registerCount)).toList()
        else -> emptyList()
    }

/**
 * A wide (long or double) value occupies the named register and the register after it.
 *
 * @return The registers used by this instruction, including the second register of each wide value.
 */
private val Instruction.registersAccessed: List<Int>
    get() {
        val registers = registersUsed
        val wideSecondHalves = registers.filterIndexed { position, _ ->
            when (position) {
                0 -> opcode.setsWideRegister() || opcode in wideFirstRegisterReadOpcodes
                1 -> opcode in wideSecondRegisterOpcodes
                2 -> opcode in wideThirdRegisterOpcodes
                else -> false
            }
        }.map { it + 1 }
        return registers + wideSecondHalves
    }

/**
 * @return The register that is written to by this instruction,
 *         or NULL if this is not a write opcode.
 */
val Instruction.writeRegister: Int?
    get() {
        if (this.opcode !in writeOpcodes) {
            return null
        }
        if (this !is OneRegisterInstruction) {
            throw IllegalStateException("Not a write instruction: $this")
        }
        return registerA
    }

/**
 * Unlike [isUnconditionalBranchInstruction], this does not include unconditional goto.
 *
 * @return If this instruction is a conditional branch (multiple branch paths).
 */
internal val Instruction.isConditionalBranchInstruction: Boolean
    get() = this.opcode in conditionalBranchOpcodes

/**
 * @return If this instruction is a GOTO opcode.
 */
internal val Instruction.isUnconditionalBranchInstruction: Boolean
    get() = this.opcode in unconditionalBranchOpcodes

/**
* @return If this instruction is a switch opcode.
*/
internal val Instruction.isSwitchInstruction: Boolean
    get() = this.opcode in switchOpcodes

/**
 * @return If this instruction returns or throws.
 */
internal val Instruction.isReturnInstruction: Boolean
    get() = this.opcode in returnOpcodes

/**
 * Maps each instruction index to its starting code unit offset, so that branch targets
 * (given as code unit offsets) can be resolved back to instruction indices.
 */
private fun Method.instructionCodeOffsets(): IntArray {
    val instructionList = this.implementation?.instructions?.toList() ?: return IntArray(0)
    val offsets = IntArray(instructionList.size)
    var offset = 0
    instructionList.forEachIndexed { index, instruction ->
        offsets[index] = offset
        offset += instruction.codeUnits
    }
    return offsets
}

