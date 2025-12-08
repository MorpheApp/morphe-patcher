package app.revanced.patcher

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method


/**
 * A builder for [Fingerprint].
 *
 * @property accessFlags The exact access flags using values of [AccessFlags].
 * @property returnType The return type compared using [String.startsWith].
 * @property parameters The parameters of the method. Partial matches allowed and follow the same rules as [returnType].
 * @property instructionFilters Filters to match the method instructions.
 * @property strings A list of the strings compared each using [String.contains].
 * @property customBlock A custom condition for this fingerprint.
 *
 * @constructor Create a new [FingerprintBuilder].
 */
@Deprecated(message = "DSL provides no functional benefits over class declarations " +
        "and can make stack traces impossible to know what fingerprint failed to resolve",
    replaceWith = ReplaceWith("app.morphe.patcher.Fingerprint()"))
class FingerprintBuilder() {
    private var accessFlags: List<AccessFlags>? = null
    private var returnType: String? = null
    private var parameters: List<String>? = null
    private var instructionFilters: List<InstructionFilter>? = null
    private var strings: List<String>? = null
    private var customBlock: ((method: Method, classDef: ClassDef) -> Boolean)? = null

    /**
     * Set the access flags.
     *
     * @param accessFlags The exact access flags using values of [AccessFlags].
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun accessFlags(vararg accessFlags: AccessFlags) {
        require(this.accessFlags == null) {
            "AccessFlags already set"
        }
        this.accessFlags = accessFlags.toList()
    }

    /**
     * Set the return type.
     *
     * If [accessFlags] includes [AccessFlags.CONSTRUCTOR], then there is no need to
     * set a return type set since constructors are always void return type.
     *
     * @param returnType The return type compared using [String.startsWith].
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun returns(returnType: String) {
        require(this.returnType == null) {
            "Returns already set"
        }
        this.returnType = returnType
    }

    /**
     * Set the parameters.
     *
     * @param parameters The parameters of the method.
     *                   Partial matches allowed and follow the same rules as [returnType].
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun parameters(vararg parameters: String) {
        require(this.parameters == null) {
            "Parameters already set"
        }
        this.parameters = parameters.toList()
    }

    private fun verifyNoFiltersSet() {
        require(this.instructionFilters == null) {
            "Instruction filters already set"
        }
    }

    /**
     * A pattern of opcodes, where each opcode must appear immediately after the previous.
     *
     * To use opcodes with other [InstructionFilter] objects,
     * instead use [instructions] with individual opcodes declared using [opcode].
     *
     * This method is identical to declaring individual opcode filters
     * with [InstructionFilter.location] set to [InstructionLocation.MatchAfterImmediately]
     * for all but the first opcode.
     *
     * Unless absolutely necessary, it is recommended to instead use [instructions]
     * with more fine grained filters.
     *
     * ```
     * opcodes(
     *    Opcode.INVOKE_VIRTUAL, // First opcode matches anywhere in the method.
     *    Opcode.MOVE_RESULT_OBJECT, // Must match exactly after INVOKE_VIRTUAL.
     *    Opcode.IPUT_OBJECT // Must match exactly after MOVE_RESULT_OBJECT.
     * )
     * ```
     * is identical to:
     * ```
     * instructions(
     *    opcode(Opcode.INVOKE_VIRTUAL), // First opcode matches anywhere in the method.
     *    opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()), // Must match exactly after INVOKE_VIRTUAL.
     *    opcode(Opcode.IPUT_OBJECT, MatchAfterImmediately()) // Must match exactly after MOVE_RESULT_OBJECT.
     * )
     * ```
     *
     * @param opcodes An opcode pattern of instructions.
     *                Wildcard or unknown opcodes can be specified by `null`.
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun opcodes(vararg opcodes: Opcode?) {
        verifyNoFiltersSet()
        if (opcodes.isEmpty()) throw IllegalArgumentException("One or more opcodes is required")

        this.instructionFilters = OpcodesFilter.opcodesToFilters(*opcodes)
    }

    /**
     * A list of instruction filters to match.
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun instructions(vararg instructionFilters: InstructionFilter) {
        verifyNoFiltersSet()
        if (instructionFilters.isEmpty()) throw IllegalArgumentException("One or more instructions is required")

        this.instructionFilters = instructionFilters.toList()
    }

    /**
     * Set the strings.
     *
     * @param strings A list of strings compared each using [String.contains].
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun strings(vararg strings: String) {
        require(this.strings == null) {
            "String block is already set"
        }
        this.strings = strings.toList()
    }

    /**
     * Set a custom condition for this fingerprint.
     *
     * @param customBlock A custom condition for this fingerprint.
     */
    @Deprecated(message = "DSL provides no functional benefits over class declarations " +
            "and can make stack traces impossible to know what fingerprint failed to resolve")
    fun custom(customBlock: (method: Method, classDef: ClassDef) -> Boolean) {
        require(this.customBlock == null) {
            "Custom block is already set. Fingerprints only support one custom block."
        }
        this.customBlock = customBlock
    }

    internal fun build(): Fingerprint {
        return Fingerprint(
            accessFlags,
            returnType,
            parameters,
            instructionFilters,
            strings,
            customBlock,
        )
    }
}

/**
 * Deprecated and will be removed at a future time. Migrate to non-DSL fingerprints.
 */
@Deprecated(message = "DSL provides no functional benefits over class declarations " +
        "and can make stack traces impossible to know what fingerprint failed to resolve",
    replaceWith = ReplaceWith("app.morphe.patcher.Fingerprint()"))
fun fingerprint(
    block: FingerprintBuilder.() -> Unit,
) = FingerprintBuilder().apply(block).build()
