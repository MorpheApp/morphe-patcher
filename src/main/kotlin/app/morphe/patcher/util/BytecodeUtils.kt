/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/util/BytecodeUtils.kt
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

@file:Suppress("unused", "SpellCheckingInspection")

package app.morphe.patcher.util

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode.RETURN
import com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT
import com.android.tools.smali.dexlib2.Opcode.RETURN_WIDE
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodParameter
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * Iterate across all method indexes that match an [InstructionFilter].
 *
 * This is only a simple helper method to call [Fingerprint.matchAllMethodIndicesForEach].
 *
 * @param requireMatches If true and no matches exist, an exception is thrown.
 * @param block Method iteration block. Indexes are iterated from last to first.
 */
context(patchContext: BytecodePatchContext)
fun InstructionFilter.matchAllMethodIndicesForEach(
    requireMatches: Boolean = true,
    block: MutableMethod.(Int) -> Unit
) = Fingerprint(filters = listOf(this)).matchAllMethodIndicesForEach(
    requireMatches,
    block
)

/**
 * Verify exactly one match exists. This is the equivalent of calling [matchAll]
 * with a range of `1 .. 1`. This can be useful for fragile fingerprints that may match
 * unrelated methods. This is an exhaustive search and will always be slower than the first match
 * that [match] provides.
 *
 * **Note**: If the patches targert a specific app and specific releases, it is highly recommended
 *           to not use this method and instead use `--validate` CLI argument that changes all calls
 *           of `match()` to `matchSingle()`. Then the overhead of an exhaustive search is done only
 *           during development/validating and not everytime during patching for the end user.
 *
 * An exception is thrown if no matches exist or more than 1 match exists.
 */
context(patchContext: BytecodePatchContext)
fun Fingerprint.matchSingle() = matchAll(1 .. 1).first()

/**
 * Iterate across all method indexes that match an [Fingerprint].
 * At this time, only a single [InstructionFilter] is supported.
 *
 * This differs from using [matchAll] as this matches multiple instruction
 * indexes in the same method and [matchAll] matches only the first index
 * of each method.
 *
 * @param requireMatches If true and no matches exist, an exception is thrown.
 * @param block Method iteration block. Indexes are iterated from last to first.
 */
context(patchContext: BytecodePatchContext)
fun Fingerprint.matchAllMethodIndicesForEach(
    requireMatches: Boolean = true,
    block: MutableMethod.(Int) -> Unit
) {
    requireNotNull(filters)
    require(filters.size == 1) {
        "Fingerprint must contain exactly 1 filter"
    }

    val matches = matchAllOrNull()
    if (matches == null) {
        if (requireMatches) throw PatchException("Could not find any matches of $this")
        return
    }

    val filter = filters.first()
    matches.forEach { match ->
        val method = match.method
        method.findInstructionIndicesReversedOrThrow(filter).forEach { index ->
            block(method, index)
        }
    }
}

/**
 * Find the [MutableMethod] from a given [Method] in a [MutableClass].
 *
 * @param method The [Method] to find.
 * @return The [MutableMethod].
 */
fun MutableClass.findMutableMethodOf(method: MethodReference) = this.methods.first {
    MethodUtil.methodSignaturesMatch(it, method)
}

/**
 * Inserts instructions at a given index, using the existing control flow label at that index.
 * Inserted instructions can have its own control flow labels as well.
 *
 * Effectively this changes the code from:
 * :label
 * (original code)
 *
 * Into:
 * :label
 * (patch code)
 * (original code)
 */
fun MutableMethod.addInstructionsAtControlFlowLabel(
    insertIndex: Int,
    instructions: String,
    vararg externalLabels: ExternalLabel
) {
    // Duplicate original instruction and add to +1 index.
    addInstruction(insertIndex + 1, getInstruction(insertIndex))

    // Add patch code at same index as duplicated instruction,
    // so it uses the original instruction control flow label.
    addInstructionsWithLabels(insertIndex + 1, instructions, *externalLabels)

    // Remove original non duplicated instruction.
    removeInstruction(insertIndex)

    // Original instruction is now after the inserted patch instructions,
    // and the original control flow label is on the first instruction of the patch code.
}

/**
 * Get the [Reference] of an [Instruction] as [T].
 *
 * @param T The type of [Reference] to cast to.
 * @return The [Reference] as [T] or null
 * if the [Instruction] is not a [ReferenceInstruction] or the [Reference] is not of type [T].
 * @see ReferenceInstruction
 */
inline fun <reified T : Reference> Instruction.getReference() = (this as? ReferenceInstruction)?.reference as? T

/**
 * @return The mutable method for this method call reference.
 */
context(patchContext: BytecodePatchContext)
fun MethodReference.getMutableMethod(): MutableMethod {
    return patchContext.mutableClassDefBy(this.definingClass).methods.first { classMethod ->
        MethodUtil.methodSignaturesMatch(classMethod, this@getMutableMethod)
    }
}

/**
 * @return A list of indices of the instructions in reverse order.
 * _Returns an empty list if no indices are found_
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversed(filter: InstructionFilter): List<Int> {
    val method = this
    return instructions
        .withIndex()
        .filter { (_, instruction) -> filter.matches(method, instruction) }
        .map { (index, _) -> index }
        .asReversed()
}

/**
 * @return A list of indices of the instructions in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(filter: InstructionFilter): List<Int> {
    val indexes = findInstructionIndicesReversed(filter)
    if (indexes.isEmpty()) throw PatchException("No matching instructions found in: $this")

    return indexes
}

/**
 * Additional registers effectively take the place of the pX parameters (p0, p1, p2, etc.)
 * and contain the original contents of the method parameters.
 * Added registers always start at index: `originalMethod.implementation!!.registerCount` of the
 * original uncloned method.
 *
 * **Fingerprint match indexes will be increased positively by [numberOfParameterRegistersLogical]**.
 */
context(patchContext: BytecodePatchContext)
fun Method.cloneParameters() = cloneParameters(
    patchContext.mutableClassDefBy(definingClass)
)

/**
 * Additional registers effectively take the place of the pX parameters (p0, p1, p2, etc.)
 * and contain the original contents of the method parameters.
 * Added registers always start at index: `originalMethod.implementation!!.registerCount` of the
 * original uncloned method.
 *
 * **Fingerprint match indexes will be increased positively by [numberOfParameterRegistersLogical]**.
 */
internal fun Method.cloneParameters(mutableClass : MutableClass) : MutableMethod {
    check (!AccessFlags.STATIC.isSet(accessFlags) || parameters.isNotEmpty()) {
        "Static method has no no parameter registers to preserve"
    }

    val clonedMethod = cloneMutable(
        additionalRegisters = numberOfParameterRegisters
    )

    // Replace existing method with cloned with more registers.
    mutableClass.methods.apply {
        remove(this@cloneParameters)
        add(clonedMethod)
    }

    return clonedMethod
}

/**
 * Clones this method with additional free registers and replaces the original method in its class.
 *
 * In most cases, if you only need a few extra registers and the method has parameters,
 * then instead use [cloneParameters] and use the p0-px registers as your free registers.
 * `addFreeRegisters()` is intended for when there are not enough parameter registers.
 *
 * Instructions are added to the start of the cloned method to copy the pX parameter registers
 * (p0, p1, p2, etc.) to the registers they used in the original method, so all existing code
 * is unchanged and can still access the parameters. The free registers are numbered from
 * `registerCount` of the original method up to `registerCount + numberOfAdditionalRegisters - 1`,
 * and are free to use anywhere after the added instructions.
 *
 * Because the free registers come after all existing registers, they may be too large for
 * instructions that only accept 4-bit registers (v0 - v15).
 *
 * This method becomes stale and must not be modified after calling this,
 * instead the returned cloned method must be used.
 *
 * @param numberOfAdditionalRegisters The number of free registers to add.
 * @return The cloned method that replaced this method, and the number of instructions added
 *         to the start of the cloned method. Instruction indexes of this method, such as
 *         Fingerprint match indexes, must be increased by the number of added instructions.
 */
context(patchContext: BytecodePatchContext)
fun MutableMethod.addFreeRegisters(numberOfAdditionalRegisters: Int) = addFreeRegisters(
    numberOfAdditionalRegisters,
    patchContext.mutableClassDefBy(definingClass)
)

internal fun MutableMethod.addFreeRegisters(
    numberOfAdditionalRegisters: Int,
    mutableClass: MutableClass
): Pair<MutableMethod, Int> {
    check(numberOfAdditionalRegisters > 0) {
        "Number of additional registers must be greater than zero: $numberOfAdditionalRegisters"
    }
    checkNotNull(implementation) {
        "Method has no implementation: $this"
    }

    val clonedMethod = cloneMutable(
        additionalRegisters = numberOfAdditionalRegisters
    )
    val addedInstructions = clonedMethod.instructions.size - instructions.size

    // Replace existing method with cloned with more registers.
    mutableClass.methods.apply {
        remove(this@addFreeRegisters)
        add(clonedMethod)
    }

    return Pair(clonedMethod, addedInstructions)
}

/**
 * Adapted from BiliRoamingX:
 * https://github.com/BiliRoamingX/BiliRoamingX/blob/ae58109f3acdd53ec2d2b3fb439c2a2ef1886221/patches/src/main/kotlin/app/revanced/patches/bilibili/utils/Extenstions.kt#L51
 *
 * Additional registers effectively take the place of the pX parameters (p0, p1, p2, etc.)
 * and contain the original contents of the method parameters.
 * Added registers always start at index: `originalMethod.implementation!!.registerCount` of the
 * original uncloned method.
 *
 * **Fingerprint match indexes will be increased positively by
 * [numberOfParameterRegistersLogical] of the cloned method**.
 */
internal fun Method.cloneMutable(
    name: String = this.name,
    accessFlags: Int = this.accessFlags,
    parameters: List<MethodParameter> = this.parameters,
    returnType: String = this.returnType,
    additionalRegisters: Int = 0,
): MutableMethod {
    check(additionalRegisters >= 0) {
        "Additional registers cannot be negative"
    }

    val implementationExists = implementation != null
    val oldFirstParameterRegister = if (implementationExists) p0Register else 0

    val clonedImplementation = implementation?.let {
        ImmutableMethodImplementation(
            it.registerCount + additionalRegisters,
            it.instructions,
            it.tryBlocks,
            it.debugItems,
        )
    }

    return ImmutableMethod(
        definingClass,
        name,
        parameters,
        returnType,
        accessFlags,
        annotations,
        hiddenApiRestrictions,
        clonedImplementation
    ).toMutable().apply {
        var insertIndex = 0
        var addedInstructions = 0
        val isNotStatic = !AccessFlags.STATIC.isSet(accessFlags)

        if (implementationExists && additionalRegisters > 0 && (parameters.isNotEmpty() || isNotStatic)) {
            var destReg = oldFirstParameterRegister
            var pReg = 0

            // Handle `this`.
            if (isNotStatic) {
                addInstructions(insertIndex++, "move-object/from16 v$destReg, p0")
                addedInstructions++
                destReg += 1
                pReg += 1
            }

            // Handle method parameters.
            for (parameter in parameters) {
                val opcode = when (parameter.type) {
                    "J", "D" -> "move-wide/from16"
                    else -> {
                        if (parameter.type.startsWith('L') || parameter.type.startsWith('[')) {
                            "move-object/from16"
                        } else {
                            "move/from16"
                        }
                    }
                }

                addInstructions(insertIndex++, "$opcode v$destReg, p$pReg")
                addedInstructions++

                val width = if (opcode.startsWith("move-wide")) 2 else 1
                destReg += width
                pReg += width
            }

            if (addedInstructions != numberOfParameterRegistersLogical) {
                throw IllegalStateException(
                    "Added instructions do not match additional registers " +
                            "addedInstructions: $addedInstructions " +
                            "numberOfParameterRegistersLogical: $numberOfParameterRegistersLogical"
                )
            }
        }
    }
}

/**
 * @return The number of registers for all parameters, including p0.
 * This includes 2 registers for each wide parameter.
 */
val Method.numberOfParameterRegisters: Int
    get() {
        var count = 0

        if (!AccessFlags.STATIC.isSet(accessFlags)) {
            count += 1
        }

        for (param in parameters) {
            count += when (param.type) {
                "J", "D" -> 2   // wide
                else -> 1       // normal
            }
        }

        return count
    }

/**
 * @return The number of parameter registers, including p0 as 'this' if method is not static.
 *   This differs from [numberOfParameterRegisters] in that long/double parameters are counted only once each.
 */
val Method.numberOfParameterRegistersLogical: Int
    get() = parameters.count() + if (AccessFlags.STATIC.isSet(accessFlags)) {
        0
    } else {
        1
    }

/**
 * @return the actual register number of p0 for this method.
 * Throws if the method has no implementation.
 */
val Method.p0Register: Int
    get() {
        val impl = implementation ?: throw IllegalStateException("Method has no implementation: $this")
        var paramRegs = 0

        // Count explicit parameters (wide types take 2 registers).
        for (type in this.parameterTypes) {
            paramRegs += if (type == "J" || type == "D") 2 else 1
        }

        // Add implicit 'this' for non-static methods.
        if (!AccessFlags.STATIC.isSet(this.accessFlags)) {
            paramRegs += 1
        }

        val totalRegs = impl.registerCount

        return totalRegs - paramRegs
    }

private const val RETURN_TYPE_MISMATCH = "Mismatch between override type and Method return type"

/**
 * Overrides the first instruction of a method with a return-void instruction.
 * None of the method code will ever execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly() {
    check(returnType.first() == 'V') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue("0x0", false)
}

/**
 * Overrides the first instruction of a method with a constant `Boolean` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Boolean) {
    check(returnType.first() == 'Z') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(if (value) "0x1" else "0x0", false)
}

/**
 * Overrides the first instruction of a method with a constant `Byte` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Byte) {
    check(returnType.first() == 'B') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Short` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Short) {
    check(returnType.first() == 'S') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Char` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Char) {
    check(returnType.first() == 'C') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.code.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Int` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Int) {
    check(returnType.first() == 'I') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant `Long` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Long) {
    check(returnType.first() == 'J') { RETURN_TYPE_MISMATCH }
    overrideReturnValue("${value}L", false)
}

/**
 * Overrides the first instruction of a method with a constant `Float` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Float) {
    check(returnType.first() == 'F') { RETURN_TYPE_MISMATCH }
    overrideReturnValue("${value}F", false)
}

/**
 * Overrides the first instruction of a method with a constant `Double` return value.
 * None of the original method code will execute.
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Double) {
    check(returnType.first() == 'D') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), false)
}

/**
 * Overrides the first instruction of a method with a constant String return value.
 * None of the original method code will execute.
 *
 * Target method must have return type
 * Ljava/lang/String; or Ljava/lang/CharSequence;
 *
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: String) {
    check(returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;") {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(value, false)
}

/**
 * Overrides the first instruction of a method with a constant `NULL` return value.
 * None of the original method code will execute.
 *
 * @param value Value must be `Null`.
 * @see returnLate
 */
fun MutableMethod.returnEarly(value: Void?) {
    val returnType = returnType.first()
    check(returnType == 'L' || returnType == '[') {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(null, false)
}

/**
 * Overrides the first instruction of a method with a boxed `java.lang.Boolean` return value.
 * None of the method code will ever execute.
 */
fun MutableMethod.returnBoxedBooleanEarly(value: Boolean) {
    check(returnType == "Ljava/lang/Boolean;" || returnType == "Ljava/lang/Object;") {
        RETURN_TYPE_MISMATCH
    }

    addInstructions(
        0,
        """
            sget-object v0, Ljava/lang/Boolean;->${if (value) "TRUE" else "FALSE" }:Ljava/lang/Boolean;
            return-object v0
        """
    )
}

/**
 * Overrides all return statements with a constant `Boolean` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Boolean) {
    check(this.returnType.first() == 'Z') {
        RETURN_TYPE_MISMATCH
    }

    overrideReturnValue(if (value) "0x1" else "0x0", true)
}

/**
 * Overrides all return statements with a constant `Byte` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Byte) {
    check(returnType.first() == 'B') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Short` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Short) {
    check(returnType.first() == 'S') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Char` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Char) {
    check(returnType.first() == 'C') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.code.toString(), true)
}

/**
 * Overrides all return statements with a constant `Int` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Int) {
    check(returnType.first() == 'I') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant `Long` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Long) {
    check(returnType.first() == 'J') { RETURN_TYPE_MISMATCH }
    overrideReturnValue("${value}L", true)
}

/**
 * Overrides all return statements with a constant `Float` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Float) {
    check(returnType.first() == 'F') { RETURN_TYPE_MISMATCH }
    overrideReturnValue("${value}F", true)
}

/**
 * Overrides all return statements with a constant `Double` value.
 * All method code is executed the same as unpatched.
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Double) {
    check(returnType.first() == 'D') { RETURN_TYPE_MISMATCH }
    overrideReturnValue(value.toString(), true)
}

/**
 * Overrides all return statements with a constant String value.
 * All method code is executed the same as unpatched.
 *
 * Target method must have return type
 * Ljava/lang/String; or Ljava/lang/CharSequence;
 *
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: String) {
    check(returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;") {
        RETURN_TYPE_MISMATCH
    }
    overrideReturnValue(value, true)
}

/**
 * Overrides all return statements with a constant `Null` value.
 * All method code is executed the same as unpatched.
 *
 * @param value Value must be `Null`.
 * @see returnEarly
 */
fun MutableMethod.returnLate(value: Void?) {
    val returnType = returnType.first()
    check(returnType == 'L' || returnType == '[') {
        RETURN_TYPE_MISMATCH
    }

    overrideReturnValue(null, true)
}

private fun MutableMethod.overrideReturnValue(value: String?, returnLate: Boolean) {
    val instructions = if (value != null && (returnType == "Ljava/lang/String;" || returnType == "Ljava/lang/CharSequence;")) {
        """
            const-string v0, "$value"
            return-object v0
        """
    } else when (returnType.first()) {
        // If return type is an object, always return null.
        'L', '[' -> {
            """
                const/4 v0, 0x0
                return-object v0
            """
        }

        'V' -> {
            "return-void"
        }

        'B', 'Z' -> {
            """
                const/4 v0, $value
                return v0
            """
        }

        'S', 'C' -> {
            """
                const/16 v0, $value
                return v0
            """
        }

        'I', 'F' -> {
            """
                const v0, $value
                return v0
            """
        }

        'J', 'D' -> {
            """
                const-wide v0, $value
                return-wide v0
            """
        }

        else -> throw Exception("Return type is not supported: $this")
    }

    if (returnLate) {
        val returnIndices = implementation!!.instructions.withIndex()
            .filter { (_, instruction) ->
                instruction.opcode.let { it == RETURN || it == RETURN_WIDE || it == RETURN_OBJECT }
            }
            .map { (index, _) -> index }
            .asReversed()
        if (returnIndices.isEmpty()) throw PatchException("No matching instructions found in: $this")

        returnIndices.forEach { index ->
            addInstructionsAtControlFlowLabel(index, instructions)
        }
    } else {
        addInstructions(0, instructions)
    }
}

/**
 * Remove the given AccessFlags from the field.
 */
fun MutableField.removeFlags(vararg flags: AccessFlags) {
    val bitField = flags.map { it.value }.reduce { acc, flag -> acc or flag }
    this.accessFlags = this.accessFlags and bitField.inv()
}

