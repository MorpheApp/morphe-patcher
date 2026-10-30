/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.util.smali

import app.morphe.patcher.Match
import com.android.tools.smali.dexlib2.iface.instruction.Instruction

/**
 * A class that represents a label for an instruction.
 * The label points to either an [instruction] or the instruction at [targetIndex].
 *
 * @param name The label name.
 * @param instruction The instruction that this label is for.
 * @param targetIndex The index of the instruction that this label is for,
 * as the index was before any instructions are added.
 */
class ExternalLabel private constructor(
    internal val name: String,
    internal val instruction: Instruction?,
    internal val targetIndex: Int,
) {
    init {
        require(instruction != null || targetIndex >= 0) { "Invalid target index: $targetIndex" }
    }

    constructor(name: String, instruction: Instruction) : this(name, instruction, -1)
    constructor(name: String, targetIndex: Int) : this(name, null, targetIndex)
    constructor(name: String, match: Match.InstructionMatch) : this(name, null, match.index)

    fun copy(
        name: String = this.name,
        instruction: Instruction = checkNotNull(this.instruction) { "Label does not use an instruction: $this" },
    ): ExternalLabel = ExternalLabel(name, instruction)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExternalLabel) return false

        return name == other.name && instruction == other.instruction && targetIndex == other.targetIndex
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (instruction?.hashCode() ?: 0)
        result = 31 * result + targetIndex
        return result
    }

    override fun toString(): String =
        "ExternalLabel(name=$name, instruction=$instruction, targetIndex=$targetIndex)"
}
