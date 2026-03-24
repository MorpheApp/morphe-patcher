/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package app.morphe.patcher

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.PatchClasses
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

open class Fingerprint private constructor(
    val classFingerprint: Fingerprint? = null,
    val definingClass: String? = null,
    val name: String? = null,
    accessFlags: List<AccessFlags>? = null,
    returnType: String? = null,
    val parameters: List<String>? = null,
    val filters: List<InstructionFilter>? = null,
    val strings: List<String>? = null,
    val custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
) {
    /**
     * A fingerprint for a method. A fingerprint is a partial description of a method,
     * used to uniquely match a method by its characteristics.
     *
     * See the patcher documentation for more detailed explanations and example fingerprinting.
     *
     * @param classFingerprint Fingerprint that finds the class this fingerprint resolves against.
     * @param name Exact method name.
     * @param accessFlags The exact access flags using values of [AccessFlags].
     * @param returnType The return type. Type declaration follow the semantics described in [StringComparisonType].
     * @param parameters The parameters. Type declaration follow the semantics described in [StringComparisonType].
     * @param filters A list of filters to match, declared in the same order the instructions appear in the method.
     * @param strings A list of strings that appear anywhere in the method in any order. Compared using [String.contains].
     * @param custom A custom condition for this fingerprint.
     */
    constructor(
        classFingerprint: Fingerprint? = null,
        name: String? = null,
        accessFlags: List<AccessFlags>? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: List<String>? = null,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        classFingerprint,
        null,
        name,
        accessFlags,
        returnType,
        parameters,
        filters,
        strings,
        custom
    )

    /**
     * A fingerprint for a method. A fingerprint is a partial description of a method,
     * used to uniquely match a method by its characteristics.
     *
     * See the patcher documentation for more detailed explanations and example fingerprinting.
     *
     * @param name Exact method name.
     * @param accessFlags The exact access flags using values of [AccessFlags].
     * @param returnType The return type. Type declaration follow the semantics described in [StringComparisonType].
     * @param parameters The parameters. Type declaration follow the semantics described in [StringComparisonType].
     * @param filters A list of filters to match, declared in the same order the instructions appear in the method.
     * @param strings A list of strings that appear anywhere in the method in any order. Compared using [String.contains].
     * @param custom A custom condition for this fingerprint.
     */
    constructor(
        name: String? = null,
        accessFlags: List<AccessFlags>? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: List<String>? = null,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        null,
        null,
        name,
        accessFlags,
        returnType,
        parameters,
        filters,
        strings,
        custom
    )

    /**
     * A fingerprint for a method. A fingerprint is a partial description of a method,
     * used to uniquely match a method by its characteristics.
     *
     * See the patcher documentation for more detailed explanations and example fingerprinting.
     *
     * @param definingClass Defining class. Type declaration follow the semantics described in [StringComparisonType].
     * @param name Exact method name.
     * @param accessFlags The exact access flags using values of [AccessFlags].
     * @param returnType The return type. Type declaration follow the semantics described in [StringComparisonType].
     * @param parameters The parameters. Type declaration follow the semantics described in [StringComparisonType].
     * @param filters A list of filters to match, declared in the same order the instructions appear in the method.
     * @param strings A list of strings that appear anywhere in the method in any order. Compared using [String.contains].
     * @param custom A custom condition for this fingerprint.
     */
    constructor( // Required to disambiguate if defining class or class fingerprint is not specified.
        definingClass: String? = null,
        name: String? = null,
        accessFlags: List<AccessFlags>? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: List<String>? = null,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        null,
        definingClass,
        name,
        accessFlags,
        returnType,
        parameters,
        filters,
        strings,
        custom
    )

    // @Deprecated("Here only for backwards compatibility") // TODO: Remove after next major version bump.
    constructor(
        accessFlags: List<AccessFlags>? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: List<String>? = null,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        null,
        null,
        null,
        accessFlags,
        returnType,
        parameters,
        filters,
        strings,
        custom
    )

    // Holds a reference to all constructed fingerprints so that they can be later cleared.
    internal companion object {
        private val fingerprintList = mutableListOf<WeakReference<Fingerprint>>()

        fun clearFingerprints() {
            fingerprintList.forEach { it.get()?.clearMatch() }
            fingerprintList.clear()
        }
    }

    private val definingClassComparison = StringComparisonType.typeDeclarationToComparison(definingClass)

    private val returnTypeComparison = StringComparisonType.typeDeclarationToComparison(returnType)

    private val parameterTypeComparison = StringComparisonType.typeDeclarationToComparison(parameters)

    val accessFlags: Int? = accessFlags?.fold(0) { acc, it -> acc or it.value }

    // Constructor always has return type of void.
    val returnType: String? = if (this.accessFlags != null && AccessFlags.CONSTRUCTOR.isSet(this.accessFlags)
        && returnType == "V"
    ) null else returnType

    init {
        // Verify an empty fingerprint wasn't declared.
        if (definingClass == null  && name == null && accessFlags == null && returnType == null
            && parameters == null && filters == null && strings == null && custom == null
        ) {
            throw IllegalArgumentException("At least one field must be set")
        }

        if (definingClass != null && classFingerprint != null) {
            throw IllegalArgumentException("Cannot specify both definingClass and classFingerprint")
        }

        fingerprintList.add(WeakReference(this))
    }

    @Suppress("ktlint:standard:backing-property-naming")
    // Backing field needed for lazy initialization.
    private var _matchOrNull: Match? = null

    /**
     * Clears the current match, forcing this fingerprint to resolve again.
     * This method should only be used if this fingerprint is re-used after it's modified,
     * and the prior match indexes are no longer correct.
     */
    fun clearMatch() {
        _matchOrNull = null
    }

    /**
     * The match for this [Fingerprint], or `null` if no matches exist.
     */
    context(BytecodePatchContext)
    fun matchOrNull(): Match? {
        if (_matchOrNull != null) return _matchOrNull

        // Must check first.
        val classFingerprintLocal = classFingerprint
        if (classFingerprint != null) {
            val match = match(classFingerprintLocal.originalClassDef)
            _matchOrNull = match
            return match
        }

        // Use string declarations to first check only the classes
        // that contain one or more fingerprint strings.
        val filterStrings = mutableListOf<String>()
        findFilterStrings(filterStrings)

        fun machAllClassMethods(value: PatchClasses.ClassDefWrapper): Match? {
            val classDef = value.classDef
            classDef.methods.forEach { method ->
                val match = matchOrNull(method, classDef)
                if (match != null) {
                    _matchOrNull = match
                    return match
                }
            }
            return null
        }

        val definingClassLocal = definingClass
        if (definingClassLocal != null) {
            val type = classDefByOrNull(definingClassLocal)
            if (type != null) {
                val match = matchOrNull(type)
                if (match != null) {
                    _matchOrNull = match
                    return match
                }
            }

            val definingClassComparisonLocal = definingClassComparison
            if (definingClassComparisonLocal != StringComparisonType.EQUALS) {
                patchClasses.classMap.values.forEach { value ->
                    if (definingClassComparisonLocal.compare(value.classDef.type, definingClassLocal)) {
                        val value = machAllClassMethods(value)
                        if (value != null) {
                            return value
                        }
                    }
                }
            }
            return null
        }

        if (filterStrings.isNotEmpty()) {
            filterStrings.forEach { string ->
                patchClasses.getClassesFromOpcodeStringLiteral(string)?.forEach { stringClass ->
                    val value = machAllClassMethods(stringClass)
                    if (value != null) {
                        return value
                    }
                }
            }

            // Fingerprint has partial string matches. Check all classes with strings.
            patchClasses.getAllClassesWithStrings().forEach { stringClass ->
                val value = machAllClassMethods(stringClass)
                if (value != null) {
                    return value
                }
            }
        }

        // Check all classes.
        patchClasses.classMap.values.forEach { value ->
            val value = machAllClassMethods(value)
            if (value != null) {
                return value
            }
        }

        return null
    }

    context(BytecodePatchContext)
    private fun checkClassFingerprintMatchesDefiningClass(classDef: String) {
        val classFingerprintLocal = classFingerprint
        if (classFingerprintLocal != null) {
            val originalClassDef = classFingerprintLocal.originalClassDef.type
            if (originalClassDef != classDef) {
                throw IllegalArgumentException("Fingerprint class fingerprint: $classFingerprintLocal " +
                        "resolves to a different class: $originalClassDef than the " +
                        "match classDef parameter: $classDef")
            }

            val definingClassLocal = definingClass
            if (definingClassLocal != null) {
                if (!definingClassComparison.compare(definingClassLocal, originalClassDef)) {
                    throw IllegalArgumentException("Fingerprint class fingerprint: $classFingerprintLocal " +
                            "resolves to a different class: $originalClassDef than this fingerprint " +
                            "definingClass: $definingClassLocal")
                }
            }
        }
    }

    /**
     * Match using a [ClassDef].
     *
     * @param classDef The class to match against.
     * @return The [Match] if a match was found or if the
     *         fingerprint is already matched to a method, null otherwise.
     */
    context(BytecodePatchContext)
    fun matchOrNull(
        classDef: ClassDef
    ): Match? {
        checkClassFingerprintMatchesDefiningClass(classDef.type)

        if (_matchOrNull != null) return _matchOrNull

        for (method in classDef.methods) {
            val match = matchOrNull(method, classDef)
            if (match != null) {
                _matchOrNull = match
                return match
            }
        }

        return null
    }

    /**
     * Match using a [Method].
     * The class is retrieved from the method.
     *
     * @param method The method to match against.
     * @return The [Match] if a match was found or if the fingerprint is previously matched to a method,
     *         otherwise `null`.
     */
    context(BytecodePatchContext)
    fun matchOrNull(
        method: Method,
    ): Match? {
        checkClassFingerprintMatchesDefiningClass(method.definingClass)

        if (_matchOrNull != null) return _matchOrNull

        return matchOrNull(method, classDefBy(method.definingClass))
    }

    /**
     * Match using a [Method].
     *
     * @param method The method to match against.
     * @param classDef The class the method is a member of.
     * @return The [Match] if a match was found or if the fingerprint is previously matched to a method,
     *         otherwise `null`.
     */
    context(BytecodePatchContext)
    fun matchOrNull(
        method: Method,
        classDef: ClassDef
    ): Match? {
        if (_matchOrNull != null) return _matchOrNull

        // Store local to avoid duplicate field access and Kotlin intrinsic null check calls.
        val nameLocal = name
        if (nameLocal != null && nameLocal != method.name) {
            return null
        }

        val definingClassLocal = definingClass
        if (definingClassLocal != null && !definingClassComparison.compare(classDef.type, definingClassLocal)) {
            return null
        }

        val returnTypeLocal = returnType
        if (returnTypeLocal != null) {
            if (!returnTypeComparison.compare(method.returnType, returnTypeLocal)) {
                return null
            }
        }

        val accessFlagsLocal = accessFlags
        if (accessFlagsLocal != null && accessFlagsLocal != method.accessFlags) {
            return null
        }

        val parametersLocal = parameters
        if (parametersLocal != null && !parametersMatch(
                method.parameterTypes,
                parametersLocal,
                parameterTypeComparison
            )) {
            return null
        }

        val customLocal = custom
        if (customLocal != null && !customLocal.invoke(method, classDef)) {
            return null
        }

        // Legacy string declarations.
        val stringsLocal = strings
        var stringMatches: List<Match.StringMatch>? = if (stringsLocal == null) {
            null
        } else {
            buildList {
                val instructions = method.instructionsOrNull ?: return null

                var stringsList : MutableList<String>? = null

                instructions.forEachIndexed { instructionIndex, instruction ->
                    if (
                        instruction.opcode != Opcode.CONST_STRING &&
                        instruction.opcode != Opcode.CONST_STRING_JUMBO
                    ) {
                        return@forEachIndexed
                    }

                    val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                    if (stringsList == null) {
                        stringsList = stringsLocal.toMutableList()
                    }
                    val index = stringsList.indexOfFirst(string::contains)
                    if (index < 0) return@forEachIndexed

                    add(Match.StringMatch(string, instructionIndex))
                    stringsList.removeAt(index)
                }

                if (stringsList == null || stringsList.isNotEmpty()) return null
            }
        }

        val filtersLocal = filters
        val instructionMatches = if (filtersLocal == null) {
            null
        } else {
            val instructions = method.instructionsOrNull?.toList() ?: return null

            fun matchFilters(): List<Match.InstructionMatch>? {
                val lastMethodIndex = instructions.lastIndex
                var instructionMatches : MutableList<Match.InstructionMatch>? = null

                var firstInstructionIndex = 0
                var lastMatchIndex = -1

                firstFilterLoop@ while (true) {
                    // Matched index of the first filter.
                    var firstFilterIndex = -1
                    var subIndex = firstInstructionIndex

                    for (filterIndex in filtersLocal.indices) {
                        val filter = filtersLocal[filterIndex]
                        val location = filter.location
                        var instructionsMatched = false

                        while (subIndex <= lastMethodIndex &&
                            location.indexIsValidForMatching(
                                lastMatchIndex, subIndex
                            )
                        ) {
                            val instruction = instructions[subIndex]
                            if (filter.matches(method, instruction)) {
                                lastMatchIndex = subIndex

                                if (filterIndex == 0) {
                                    firstFilterIndex = subIndex
                                }
                                if (instructionMatches == null) {
                                    instructionMatches = ArrayList(filtersLocal.size)
                                }
                                instructionMatches += Match.InstructionMatch(filter, subIndex, instruction)
                                instructionsMatched = true
                                subIndex++
                                break
                            }
                            subIndex++
                        }

                        if (!instructionsMatched) {
                            if (filterIndex == 0) {
                                return null // First filter has no more matches to start from.
                            }

                            // Try again with the first filter, starting from
                            // the next possible first filter index.
                            firstInstructionIndex = firstFilterIndex + 1
                            lastMatchIndex = -1
                            instructionMatches?.clear()
                            continue@firstFilterLoop
                        }
                    }

                    // All instruction filters matches.
                    return instructionMatches
                }
            }

            matchFilters() ?: return null
        }

        // Sort legacy string match results to the same order declared in the fingerprint.
        if (stringMatches != null) {
            val map = stringMatches.groupBy { it.string }
                .mapValues { it.value.toMutableList() }

            stringMatches = strings!!.mapNotNull { key ->
                map[key]?.removeFirstOrNull()
            }
        }

        _matchOrNull = Match(
            classDef,
            method,
            instructionMatches,
            stringMatches,
        )

        return _matchOrNull
    }

    private fun findFilterStrings(stringEqualMatch: MutableList<String>): Boolean {
        var hasPartialMatchStrings = false

        if (strings != null) {
            // Old unordered string declarations.
            // Can be either equal or partial matches.
            stringEqualMatch.addAll(strings)
            hasPartialMatchStrings = true
        }

        if (filters != null) {
            fun filterStringFilterInstances(list: List<InstructionFilter>) =
                list.filterIsInstance<StringFilter>()

            fun addStringFilterLiterals(list: List<StringFilter>) {
                list.forEach { filter ->
                    stringEqualMatch.add(filter.stringValue)

                    if (filter.comparison != StringComparisonType.EQUALS) {
                        hasPartialMatchStrings = true
                    }
                }
            }

            addStringFilterLiterals(filterStringFilterInstances(filters))

            // Use strings declared inside anyInstruction.
            filters.filterIsInstance<AnyInstruction>().forEach { anyFilter ->
                addStringFilterLiterals(filterStringFilterInstances(anyFilter.filters))
            }
        }

        return hasPartialMatchStrings
    }

    /**
     * Matches all methods in the class that match, or returns NULL if none match.
     */
    context(BytecodePatchContext)
    fun matchAllOrNull(classDef: ClassDef): List<Match>? {
        val matches = mutableListOf<Match>()

        for (method in classDef.methods) {
            val match = matchOrNull(method, classDef)
            if (match != null) {
                matches += match
                clearMatch()
            }
        }

        return matches.ifEmpty { null }
    }

    /**
     * Matches all methods in the target app that match, or returns NULL if none match.
     * Match method index will be the first match in the method.
     */
    context(BytecodePatchContext)
    fun matchAllOrNull(): List<Match>? {
        if (classFingerprint != null) {
            return matchAll(classFingerprint.classDef)
        }

        val matches = mutableListOf<Match>()

        fun machAllClassMethods(value: PatchClasses.ClassDefWrapper) {
            val classDef = value.classDef
            classDef.methods.forEach { method ->
                val match = matchOrNull(method, classDef)
                if (match != null) {
                    matches += match
                    clearMatch()
                }
            }
        }

        // If using built-in filters and not using anyFilter, and contain String literals,
        // then can speed up matching by only checking classes with matching strings.
        if (filters?.all { BUNDLED_INSTRUCTION_FILTERS.contains(it::class)} == true) {
            val filterStrings = mutableListOf<String>()
            val hasPartialMatchStrings = findFilterStrings(filterStrings)

            if (filterStrings.isNotEmpty()) {
                if (hasPartialMatchStrings) {
                    patchClasses.getAllClassesWithStrings().forEach { stringClass ->
                        machAllClassMethods(stringClass)
                    }
                } else {
                    filterStrings.forEach { string ->
                        patchClasses.getClassesFromOpcodeStringLiteral(string)
                            ?.forEach { stringClass ->
                                machAllClassMethods(stringClass)
                            }
                    }
                }

                return matches.ifEmpty { null }
            }
        }

        // Check all classes.
        patchClasses.classMap.values.forEach { value ->
            machAllClassMethods(value)
        }

        return matches.ifEmpty { null }
    }

    fun patchException() = PatchException("Failed to match the fingerprint: $this")

    /**
     * The match for this [Fingerprint].
     *
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the [Fingerprint] failed to match.
     */
    context(BytecodePatchContext)
    fun match() = matchOrNull() ?: throw patchException()

    /**
     * Match using a [ClassDef].
     *
     * @param classDef The class to match against.
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(BytecodePatchContext)
    fun match(
        classDef: ClassDef,
    ) = matchOrNull(classDef) ?: throw patchException()

    /**
     * Match using a [Method].
     * The class is retrieved from the method.
     *
     * @param method The method to match against.
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(BytecodePatchContext)
    fun match(
        method: Method,
    ) = matchOrNull(method) ?: throw patchException()

    /**
     * Match using a [Method].
     *
     * @param method The method to match against.
     * @param classDef The class the method is a member of.
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(BytecodePatchContext)
    fun match(
        method: Method,
        classDef: ClassDef,
    ) = matchOrNull(method, classDef) ?: throw patchException()

    /**
     * Matches all methods in the target app.
     *
     * @param classDef The class the method is a member of.
     * @return All methods that match.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(BytecodePatchContext)
    fun matchAll(
        classDef: ClassDef,
    ) = matchAllOrNull(classDef) ?: throw patchException()

    /**
     * Match all methods in the target app.
     *
     * @return All methods that match.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(BytecodePatchContext)
    fun matchAll() = matchAllOrNull() ?: throw patchException()

    /**
     * The class the matching method is a member of, or null if this fingerprint did not match.
     */
    context(BytecodePatchContext)
    val originalClassDefOrNull
        get() = matchOrNull()?.originalClassDef

    /**
     * The matching method, or null of this fingerprint did not match.
     */
    context(BytecodePatchContext)
    val originalMethodOrNull
        get() = matchOrNull()?.originalMethod

    /**
     * The mutable version of [originalClassDefOrNull].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalClassDefOrNull] if mutable access is not required.
     */
    context(BytecodePatchContext)
    val classDefOrNull
        get() = matchOrNull()?.classDef

    /**
     * The mutable version of [originalMethodOrNull].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalMethodOrNull] if mutable access is not required.
     */
    context(BytecodePatchContext)
    val methodOrNull
        get() = matchOrNull()?.method

    /**
     * The match for the instruction filters, or null if this fingerprint did not match.
     */
    context(BytecodePatchContext)
    val instructionMatchesOrNull
        get() = matchOrNull()?.instructionMatchesOrNull

    /**
     * The class the matching method is a member of.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(BytecodePatchContext)
    val originalClassDef
        get() = match().originalClassDef

    /**
     * The matching method.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(BytecodePatchContext)
    val originalMethod
        get() = match().originalMethod

    /**
     * The mutable version of [originalClassDef].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalClassDef] if mutable access is not required.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(BytecodePatchContext)
    val classDef
        get() = match().classDef

    /**
     * The mutable version of [originalMethod].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalMethod] if mutable access is not required.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(BytecodePatchContext)
    val method
        get() = match().method

    /**
     * Instruction filter matches.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(BytecodePatchContext)
    val instructionMatches
        get() = match().instructionMatches

    /**
     * The matches for the strings declared using `strings()`.
     * This does not give matches for strings declared using [string] instruction filters.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    // TODO: Possibly deprecate this in the future.
    context(BytecodePatchContext)
    val stringMatches
        get() = match().stringMatches

    //
    // Old legacy non-unified matching objects.
    //

    /**
     * The matches for strings declared in [Fingerprint.strings].
     *
     * **Note**: Strings declared as instruction filters are not included in these legacy match results.
     *
     * This property may be deprecated in the future.
     * Consider changing to [InstructionFilter] and [string] declarations.
     */
    // TODO: Possibly deprecate this in the future.
    context(BytecodePatchContext)
    val stringMatchesOrNull
        get() = matchOrNull()?.stringMatchesOrNull
}

/**
 * A match of a [Fingerprint].
 */
context(BytecodePatchContext)
class Match internal constructor(
    val originalClassDef: ClassDef,
    val originalMethod: Method,
    private val _instructionMatches: List<InstructionMatch>?,
    private val _stringMatches: List<StringMatch>?,
) {
    /**
     * The mutable version of [originalClassDef].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalClassDef] if mutable access is not required.
     */
    val classDef by lazy { mutableClassDefBy(originalClassDef) }

    /**
     * The mutable version of [originalMethod].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalMethod] if mutable access is not required.
     */
    val method by lazy { classDef.methods.first { MethodUtil.methodSignaturesMatch(it, originalMethod) } }

    /**
     * Matches corresponding to the [InstructionFilter] declared in the [Fingerprint].
     */
    val instructionMatches
        get() = _instructionMatches ?: throw PatchException("Fingerprint declared no instruction filters")
    val instructionMatchesOrNull = _instructionMatches

    /**
     * A match for an [InstructionFilter].
     * @param filter The filter that matched
     * @param index The instruction index it matched with.
     * @param instruction The instruction that matched.
     */
    class InstructionMatch internal constructor(
        val filter : InstructionFilter,
        val index: Int,
        val instruction: Instruction
    ) {
        /**
         * Helper method to simplify casting the instruction to it's known and expected type.
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> getInstruction(): T = instruction as T

        override fun toString(): String {
            return "InstructionMatch{filter='${filter.javaClass.simpleName}, opcode='${instruction.opcode}, 'index=$index}"
        }
    }

    //
    // Old legacy non-unified matching objects.
    //

    /**
     * The matches for strings declared in [Fingerprint.strings].
     *
     * Match results will be in the same order declared in the fingerprint string list.
     *
     * **Note**: Strings declared as instruction filters are not included in these legacy match results.
     *
     * This property may be deprecated in the future.
     * Consider changing to [InstructionFilter] and [string] declarations.
     */
    // TODO: Possibly deprecate this in the future.
    val stringMatches
        get() = _stringMatches ?: throw PatchException("Fingerprint declared no strings")
    val stringMatchesOrNull = _stringMatches

    /**
     * A match for a string declared in [Fingerprint.stringMatches].
     *
     * **Note**: Strings declared as instruction filters are not included in this legacy match object.
     *
     * This legacy match type may be deprecated in the future.
     * Consider changing to [InstructionFilter] and [StringFilter] declarations.
     *
     * @param string The string that matched.
     * @param index The index of the instruction in the method.
     */
    // TODO: Possibly deprecate this in the future.
    class StringMatch internal constructor(val string: String, val index: Int)

    override fun toString(): String {
        return "Match(originalMethod=$originalMethod, " +
                "instructionMatches=$_instructionMatches, " +
                "stringMatches=$_stringMatches)"
    }
}

/**
 * Matches two lists of parameters.
 *
 * @param targetMethodParameters Method parameters to search in.
 * @param fingerprintParameters Parameters to check. Uses [StringComparisonType] type semantics.
 */
@Deprecated(
    "Method was renamed and moved to StringComparisonType",
    replaceWith = ReplaceWith("parametersMatch(targetMethodParameters, fingerprintParameters)")
)
fun parametersStartsWith(  // TODO: Delete on next major version release.
    targetMethodParameters: Iterable<CharSequence>,
    fingerprintParameters: Iterable<CharSequence>,
) = parametersMatch(targetMethodParameters, fingerprintParameters)

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
            definingClass = null,
            name = null,
            accessFlags = accessFlags,
            returnType = returnType,
            parameters = parameters,
            filters = instructionFilters,
            strings = strings,
            custom = customBlock,
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