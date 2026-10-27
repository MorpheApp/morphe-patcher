/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package app.morphe.patcher

import app.morphe.patcher.Fingerprint.Companion.SLOW_MATCH_NANOS
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.PatchClasses
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * @param classFingerprint Fingerprint that finds the class this fingerprint resolves against.
 * @param filters A list of filters to match, declared in the order they are matched.
 *                Each filter matches relative to the previous filter match, using the
 *                [InstructionFilter.location] of the filter. Some locations, such as
 *                [InstructionLocation.MatchBeforeImmediately], match an instruction that appears
 *                before the previous filter match. If a filter cannot match, then the filters
 *                declared before it are retried with their next matching instruction.
 * @param stringFilters A list of string filters that match anywhere in the method in any order.
 * @param custom A custom condition for this fingerprint.
 */
open class Fingerprint private constructor(
    val classFingerprint: Fingerprint? = null,
    definingClass: String? = null,
    name: String? = null,
    accessFlags: List<AccessFlags>? = null,
    returnType: String? = null,
    parameters: List<String>? = null,
    val filters: List<InstructionFilter>? = null,
    val stringFilters: List<StringFilter>? = null,
    val custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    isStatic: Boolean? = null,
    superclass: String? = null,
    interfaces: List<String>? = null,
) {
    internal val definingClass: String? = definingClass
    internal val name: String? = name
    internal val parameters: List<String>? = parameters
    internal val isStatic: Boolean? = isStatic
    internal val superclass: String? = superclass
    internal val interfaces: List<String>? = interfaces

    /**
     * The string values of [stringFilters].
     */
    val strings: List<String>?
        get() = stringFilters?.map { it.stringValue }

    @Deprecated(
        "Instead use matched method fields",
        replaceWith = ReplaceWith("method.definingClass"),
        level = DeprecationLevel.ERROR // TODO: Change this to hidden level
    )
    fun getDefiningClass(): String? = definingClass

    @Deprecated(
        "Instead use matched method fields",
        replaceWith = ReplaceWith("method.name"),
        level = DeprecationLevel.ERROR // TODO: Change this to hidden level
    )
    fun getName(): String? = name

    @Deprecated(
        "Instead use matched method fields",
        replaceWith = ReplaceWith("method.returnType"),
        level = DeprecationLevel.ERROR // TODO: Change this to hidden level
    )
    fun getReturnType(): String? = returnType

    @Deprecated(
        "Instead use matched method fields",
        replaceWith = ReplaceWith("method.parameterTypes"),
        level = DeprecationLevel.ERROR // TODO: Change this to hidden level
    )
    fun getParameters(): List<String>? = parameters

    /**
     * A fingerprint for a method. A fingerprint is a partial description of a method,
     * used to uniquely match a method by its characteristics.
     *
     * See the patcher documentation for more detailed explanations and example fingerprinting.
     *
     * @param classFingerprint Fingerprint that finds the class this fingerprint resolves against.
     *                         Cannot be declared with [definingClass].
     * @param definingClass Defining class. Type declaration follow the semantics described in [StringComparisonType].
     *                      Cannot be declared with [classFingerprint].
     * @param superclass The direct superclass of the defining class.
     *                   Type declaration follow the semantics described in [StringComparisonType].
     * @param interfaces Interfaces the defining class directly implements, declared in any order.
     *                   The class can implement other interfaces not declared here.
     *                   Type declarations follow the semantics described in [StringComparisonType].
     * @param name Exact method name.
     * @param accessFlags The exact access flags using values of [AccessFlags].
     *                    Access flags can change between app versions. If only static or
     *                    non-static matters, instead declare [isStatic].
     * @param isStatic If the method is static. All other access flags are ignored.
     *                 Cannot be declared with [accessFlags]. To match a constructor declare
     *                 `name = "<init>"`, or for a static initializer declare `name = "<clinit>"`.
     * @param returnType The return type. Type declaration follow the semantics described in [StringComparisonType].
     * @param parameters The parameters. Type declaration follow the semantics described in [StringComparisonType].
     * @param filters A list of filters to match, declared in the order they are matched.
     *                Each filter matches relative to the previous filter match, using the
     *                [InstructionFilter.location] of the filter. Some locations, such as
     *                [InstructionLocation.MatchBeforeImmediately], match an instruction that appears
     *                before the previous filter match. If a filter cannot match, then the filters
     *                declared before it are retried with their next matching instruction.
     * @param strings A list of strings that appear anywhere in the method in any order. Compared using [String.equals].
     * @param custom A custom condition for this fingerprint.
     */
    constructor(
        classFingerprint: Fingerprint? = null,
        definingClass: String? = null,
        superclass: String? = null,
        interfaces: List<String>? = null,
        name: String? = null,
        accessFlags: List<AccessFlags>? = null,
        isStatic: Boolean? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: List<String>? = null,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        classFingerprint,
        definingClass,
        name,
        accessFlags,
        returnType,
        parameters,
        filters,
        rawStringFilters(strings),
        custom,
        isStatic,
        superclass,
        interfaces,
    )

    /**
     * A fingerprint for a method. A fingerprint is a partial description of a method,
     * used to uniquely match a method by its characteristics.
     *
     * See the patcher documentation for more detailed explanations and example fingerprinting.
     *
     * The strings parameter is a Collection and not a List because List<StringFilter>
     * has the same JVM signature as List<String>. The parameter is required and not nullable,
     * so calls that do not declare string filters always resolve to the List<String> constructor.
     *
     * @param classFingerprint Fingerprint that finds the class this fingerprint resolves against.
     *                         Cannot be declared with [definingClass].
     * @param definingClass Defining class. Type declaration follow the semantics described in [StringComparisonType].
     *                      Cannot be declared with [classFingerprint].
     * @param superclass The direct superclass of the defining class.
     *                   Type declaration follow the semantics described in [StringComparisonType].
     * @param interfaces Interfaces the defining class directly implements, declared in any order.
     *                   The class can implement other interfaces not declared here.
     *                   Type declarations follow the semantics described in [StringComparisonType].
     * @param name Exact method name.
     * @param accessFlags The exact access flags using values of [AccessFlags].
     *                    Access flags can change between app versions. If only static or
     *                    non-static matters, instead declare [isStatic].
     * @param isStatic If the method is static. All other access flags are ignored.
     *                 Cannot be declared with [accessFlags]. To match a constructor declare
     *                 `name = "<init>"`, or for a static initializer declare `name = "<clinit>"`.
     * @param returnType The return type. Type declaration follow the semantics described in [StringComparisonType].
     * @param parameters The parameters. Type declaration follow the semantics described in [StringComparisonType].
     * @param filters A list of filters to match, declared in the order they are matched.
     *                Each filter matches relative to the previous filter match, using the
     *                [InstructionFilter.location] of the filter. Some locations, such as
     *                [InstructionLocation.MatchBeforeImmediately], match an instruction that appears
     *                before the previous filter match. If a filter cannot match, then the filters
     *                declared before it are retried with their next matching instruction.
     * @param strings A list of [string] filters that match anywhere in the method in any order.
     *                Filters cannot declare an [InstructionLocation].
     * @param custom A custom condition for this fingerprint.
     */
    constructor(
        classFingerprint: Fingerprint? = null,
        definingClass: String? = null,
        superclass: String? = null,
        interfaces: List<String>? = null,
        name: String? = null,
        accessFlags: List<AccessFlags>? = null,
        isStatic: Boolean? = null,
        returnType: String? = null,
        parameters: List<String>? = null,
        filters: List<InstructionFilter>? = null,
        strings: Collection<StringFilter>,
        custom: ((method: Method, classDef: ClassDef) -> Boolean)? = null,
    ) : this(
        classFingerprint,
        definingClass,
        name,
        accessFlags,
        returnType,
        parameters,
        filters,
        strings.toList(),
        custom,
        isStatic,
        superclass,
        interfaces,
    )

    //
    // Constructors of Morphe Patcher 1.x, kept for binary compatibility with patches built
    // for older versions. Hidden so calls from source always resolve to the constructors above.
    //

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
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
        rawStringFilters(strings),
        custom
    )

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    constructor(
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
        rawStringFilters(strings),
        custom
    )

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
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
        rawStringFilters(strings),
        custom
    )

    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
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
        rawStringFilters(strings),
        custom
    )

    // Holds a reference to all constructed fingerprints so that they can be later cleared.
    internal companion object {
        private val fingerprintList = mutableListOf<WeakReference<Fingerprint>>()

        private val logger = Logger.getLogger(Fingerprint::class.java.name)

        /**
         * Resolving a fingerprint that takes longer than this is logged if
         * [PatcherConfig.validate] is set.
         */
        private val SLOW_MATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(30)

        /**
         * How raw strings declared with [strings] are compared. Only patches built for a version of
         * Morphe Patcher older than 2.0.0 use [StringComparisonType.CONTAINS], see
         * [PatcherBehavior.EXACT_FINGERPRINT_STRINGS].
         */
        @Volatile
        internal var rawStringComparison = StringComparisonType.EQUALS

        /**
         * If the filters before an instruction filter that does not match are retried with their next
         * matching instruction. Only patches built for a version of Morphe Patcher older than 2.0.0
         * retry only the first filter, see [PatcherBehavior.RETRY_ALL_INSTRUCTION_FILTERS].
         */
        @Volatile
        internal var retryAllFilters = true

        /**
         * Raw strings are matched using [rawStringComparison].
         */
        private fun rawStringFilters(strings: List<String>?) =
            strings?.map { string -> StringFilter({ string }, null, InstructionLocation.MatchAfterAnywhere()) }

        fun clearFingerprints() {
            fingerprintList.forEach { it.get()?.clearMatch() }
            fingerprintList.clear()
        }
    }

    private val definingClassComparison = StringComparisonType.typeDeclarationToComparison(definingClass)

    private val returnTypeComparison = StringComparisonType.typeDeclarationToComparison(returnType)

    private val parameterTypeComparison = StringComparisonType.typeDeclarationToComparison(parameters)

    private val superclassComparison = StringComparisonType.typeDeclarationToComparison(superclass)

    private val interfaceComparisons = StringComparisonType.typeDeclarationToComparison(interfaces)

    internal val accessFlags: Int? = accessFlags?.fold(0) { acc, it -> acc or it.value }

    /**
     * Bits of the method access flags that must equal [accessFlagsValue].
     * Exact [accessFlags] compare all bits, [isStatic] compares only the static bit,
     * and zero compares nothing.
     */
    private val accessFlagsMask: Int = when {
        this.accessFlags != null -> -1
        isStatic != null -> AccessFlags.STATIC.value
        else -> 0
    }

    private val accessFlagsValue: Int = this.accessFlags
        ?: if (isStatic == true) AccessFlags.STATIC.value else 0

    // Constructor always has return type of void.
    internal val returnType: String? = if (this.accessFlags != null && AccessFlags.CONSTRUCTOR.isSet(this.accessFlags)
        && returnType == "V"
    ) null else returnType

    init {
        // Verify an empty fingerprint wasn't declared.
        if (definingClass == null  && name == null && accessFlags == null && isStatic == null
            && returnType == null && parameters == null && filters == null && stringFilters == null
            && custom == null && superclass == null && interfaces == null
        ) {
            throw IllegalArgumentException("At least one field must be set")
        }

        require(interfaces == null || interfaces.isNotEmpty()) {
            "Interfaces cannot be empty"
        }

        require(filters == null || filters.isNotEmpty()) {
            "Filters cannot be empty"
        }

        require(accessFlags == null || isStatic == null) {
            "Cannot specify both accessFlags and isStatic"
        }

        stringFilters?.forEach { filter ->
            require(filter.location is InstructionLocation.MatchAfterAnywhere) {
                "Strings match in any order and cannot declare an instruction location: ${filter.location}"
            }
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
    // TODO: On next major version bump change this to return the fingerprint.
    fun clearMatch() {
        _matchOrNull = null
        classFingerprint?.clearMatch()
    }

    /**
     * The match for this [Fingerprint], or `null` if no matches exist.
     */
    context(patchContext: BytecodePatchContext)
    fun matchOrNull(): Match? {
        if (_matchOrNull != null) return _matchOrNull

        if (patchContext.validate) {
            return logIfSlow {
                val classFingerprintLocal = classFingerprint
                val matches = if (classFingerprintLocal != null) {
                    classFingerprintLocal.classFingerprintClasses().flatMap { classDef ->
                        matchAllInClassOrNull(classDef) ?: emptyList()
                    }.ifEmpty { null }
                } else {
                    matchAllOrNullUntimed()
                }
                singleMatchOrNull(matches)
            }
        }

        // Must check first.
        val classFingerprintLocal = classFingerprint
        if (classFingerprint != null) {
            val match = match(classFingerprintLocal.match().originalClassDef)
            _matchOrNull = match
            return match
        }

        // Use string declarations to first check only the classes
        // that contain one or more fingerprint strings.
        val filterStrings = mutableListOf<String>()
        findFilterStrings(filterStrings)

        fun machAllClassMethods(
            value: PatchClasses.ClassDefWrapper,
            methods: Iterable<Method> = value.methods,
        ): Match? {
            val classDef = value.classDef
            if (!classMatches(classDef)) return null

            methods.forEach { method ->
                val match = matchMethodOrNull(method, classDef)
                if (match != null) {
                    return match
                }
            }
            return null
        }

        if (definingClass != null) {
            forEachDefiningClass { value ->
                val match = machAllClassMethods(value)
                if (match != null) {
                    return match
                }
            }
            return null
        }

        filterStrings.forEach { string ->
            patchContext.patchClasses.getClassesFromOpcodeStringLiteral(string)?.forEach { stringClass ->
                val value = machAllClassMethods(stringClass)
                if (value != null) {
                    return value
                }
            }
        }

        indexedCandidates()?.let { candidates ->
            candidates.classes.forEach { value ->
                val match = machAllClassMethods(value, candidates.methodsOf(value))
                if (match != null) return match
            }

            // Every matching method has the indexed values, so no other class can match.
            return null
        }

        if (filterStrings.isNotEmpty()) {
            // The strings are declared only in anyInstruction filters, which can also match
            // instructions without strings. Check the classes with strings first.
            patchContext.patchClasses.getAllClassesWithStrings().forEach { stringClass ->
                val value = machAllClassMethods(stringClass)
                if (value != null) {
                    return value
                }
            }
        }

        // Check all classes.
        patchContext.patchClasses.classMap.values.forEach { value ->
            val value = machAllClassMethods(value)
            if (value != null) {
                return value
            }
        }

        return null
    }

    context(patchContext: BytecodePatchContext)
    private fun checkClassFingerprintMatchesDefiningClass(classDef: String) {
        val classFingerprintLocal = classFingerprint
        if (classFingerprintLocal != null) {
            val originalClassDef = if (patchContext.validate) {
                val classTypes = classFingerprintLocal.classFingerprintClasses().map { it.type }
                if (classDef in classTypes) classDef else classTypes.joinToString()
            } else {
                classFingerprintLocal.match().classType
            }
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
    context(patchContext: BytecodePatchContext)
    fun matchOrNull(
        classDef: ClassDef
    ): Match? {
        checkClassFingerprintMatchesDefiningClass(classDef.type)

        if (_matchOrNull != null) return _matchOrNull

        if (patchContext.validate) {
            return singleMatchOrNull(matchAllInClassOrNull(classDef))
        }

        if (!classMatches(classDef)) return null

        for (method in methodsOf(classDef)) {
            val match = matchMethodOrNull(method, classDef)
            if (match != null) {
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
    context(patchContext: BytecodePatchContext)
    fun matchOrNull(
        method: Method,
    ): Match? {
        checkClassFingerprintMatchesDefiningClass(method.definingClass)

        if (_matchOrNull != null) return _matchOrNull

        return matchOrNull(method, patchContext.classDefBy(method.definingClass))
    }

    /**
     * Match using a [Method].
     *
     * @param method The method to match against.
     * @param classDef The class the method is a member of.
     * @return The [Match] if a match was found or if the fingerprint is previously matched to a method,
     *         otherwise `null`.
     */
    context(patchContext: BytecodePatchContext)
    fun matchOrNull(
        method: Method,
        classDef: ClassDef
    ): Match? {
        if (_matchOrNull != null) return _matchOrNull

        if (!classMatches(classDef)) return null

        return matchMethodOrNull(method, classDef)
    }

    /**
     * All methods of [classDef] that match, or null if none match.
     * Unlike [matchAllOrNull], this does not clear the match of [classFingerprint].
     */
    context(patchContext: BytecodePatchContext)
    private fun matchAllInClassOrNull(classDef: ClassDef): List<Match>? {
        if (!classMatches(classDef)) return null

        val matches = methodsOf(classDef).mapNotNull { method ->
            matchMethodOrNull(method, classDef)
        }
        _matchOrNull = null

        return matches.ifEmpty { null }
    }

    /**
     * The classes this fingerprint matches when it is the [classFingerprint] of another fingerprint.
     * Used only if [PatcherConfig.validate] is set, so the other fingerprint can be checked
     * against every class instead of only the first match.
     *
     * @throws PatchException If this fingerprint does not match.
     */
    context(patchContext: BytecodePatchContext)
    private fun classFingerprintClasses(): List<ClassDef> {
        val matches = matchAllOrNull() ?: throw patchException()
        _matchOrNull = null

        return matches.distinctBy { it.classType }.map { it.originalClassDef }
    }

    /**
     * Runs [block] and logs if resolving this fingerprint took longer than [SLOW_MATCH_NANOS].
     *
     * The time does not include building the instruction indexes, as that is done only once
     * by whichever fingerprint resolves first. The time of other fingerprints resolved by [block],
     * such as [classFingerprint], is also not included as those are timed on their own.
     */
    context(patchContext: BytecodePatchContext)
    private inline fun <T> logIfSlow(block: () -> T): T {
        val patchClasses = patchContext.patchClasses
        val indexStartNanos = patchClasses.indexBuildNanos
        val nestedStartNanos = patchContext.fingerprintMatchNanos
        val startNanos = System.nanoTime()

        val result = block()

        val excludedNanos = (patchClasses.indexBuildNanos - indexStartNanos) +
                (patchContext.fingerprintMatchNanos - nestedStartNanos)
        val matchNanos = System.nanoTime() - startNanos - excludedNanos
        // Lets an outer fingerprint exclude the time of this fingerprint.
        patchContext.fingerprintMatchNanos += matchNanos

        if (matchNanos > SLOW_MATCH_NANOS) {
            logger.info(
                "Fingerprint took ${TimeUnit.NANOSECONDS.toMillis(matchNanos)} ms to resolve: $this"
            )
        }

        return result
    }

    /**
     * Sets the match to the only entry of [matches], used if [PatcherConfig.validate] is set.
     *
     * @throws PatchException If more than one method matches.
     */
    private fun singleMatchOrNull(matches: List<Match>?): Match? {
        if (matches == null) return null

        if (matches.size > 1) {
            throw PatchException(
                "Fingerprint validation failed. Expected a single match but found ${matches.size} matches: $this " +
                    "Matched methods: ${matches.joinToString { it.methodReference.toString() }}"
            )
        }

        _matchOrNull = matches.first()
        return _matchOrNull
    }

    /**
     * If the class matches [superclass] and [interfaces].
     * Checked once for each class, so methods of classes that cannot match are not loaded.
     */
    private fun classMatches(classDef: ClassDef): Boolean {
        val superclassLocal = superclass
        if (superclassLocal != null) {
            val classSuperclass = classDef.superclass ?: return false
            if (!superclassComparison.compare(classSuperclass, superclassLocal)) return false
        }

        val interfacesLocal = interfaces
        if (interfacesLocal != null) {
            val classInterfaces = classDef.interfaces
            for (index in interfacesLocal.indices) {
                val declaredInterface = interfacesLocal[index]
                val comparison = interfaceComparisons[index]
                if (classInterfaces.none { comparison.compare(it, declaredInterface) }) return false
            }
        }

        return true
    }

    /**
     * Match using a [Method] without checking for a previous match.
     * The caller must check [classMatches].
     *
     * Values that need no decoding are checked first, because each read of a
     * name or a type of dex backed method decodes the string again.
     */
    context(patchContext: BytecodePatchContext)
    private fun matchMethodOrNull(
        method: Method,
        classDef: ClassDef
    ): Match? {
        if ((method.accessFlags and accessFlagsMask) != accessFlagsValue) {
            return null
        }

        // Store local to avoid duplicate field access and Kotlin intrinsic null check calls.
        val parametersLocal = parameters
        val parameterTypes = if (parametersLocal == null) {
            null
        } else {
            method.parameterTypes.also { parameterTypes ->
                if (parameterTypes.size != parametersLocal.size) return null
            }
        }

        val nameLocal = name
        if (nameLocal != null && nameLocal != method.name) {
            return null
        }

        val returnTypeLocal = returnType
        if (returnTypeLocal != null) {
            if (!returnTypeComparison.compare(method.returnType, returnTypeLocal)) {
                return null
            }
        }

        if (parameterTypes != null && !parametersMatch(
                parameterTypes,
                parametersLocal!!,
                parameterTypeComparison
            )) {
            return null
        }

        val definingClassLocal = definingClass
        if (definingClassLocal != null && !definingClassComparison.compare(classDef.type, definingClassLocal)) {
            return null
        }

        val customLocal = custom
        if (customLocal != null && !customLocal.invoke(method, classDef)) {
            return null
        }

        if (method is MutableMethod && !method.isImplementationCreated && !sourceInstructionsCanMatch(method)) {
            return null
        }

        // Unordered string declarations.
        val stringFiltersLocal = stringFilters
        val stringMatches: List<Match.StringMatch>? = if (stringFiltersLocal == null) {
            null
        } else {
            val instructions = method.instructionsOrNull ?: return null

            // Indexed by the declared string filter, so the results are in the declared order.
            val matches = arrayOfNulls<Match.StringMatch>(stringFiltersLocal.size)
            var remaining = matches.size

            for ((instructionIndex, instruction) in instructions.withIndex()) {
                if (remaining == 0) break

                if (
                    instruction.opcode != Opcode.CONST_STRING &&
                    instruction.opcode != Opcode.CONST_STRING_JUMBO
                ) {
                    continue
                }

                val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                val index = stringFiltersLocal.indices.firstOrNull { index ->
                    val filter = stringFiltersLocal[index]
                    matches[index] == null && filter.comparison.compare(string, filter.stringValue)
                } ?: continue

                matches[index] = Match.StringMatch(string, instructionIndex)
                remaining--
            }

            if (remaining > 0) return null
            matches.map { it!! }
        }

        val filtersLocal = filters
        val instructionMatches = if (filtersLocal == null) {
            null
        } else {
            val instructions = method.instructionsOrNull?.let { it as? List<Instruction> ?: it.toList() } ?: return null

            if (retryAllFilters) {
                matchFilters(method, instructions, filtersLocal)
            } else {
                matchFiltersRetryingFirstFilter(method, instructions, filtersLocal)
            } ?: return null
        }

        _matchOrNull = Match(
            patchContext,
            classDef,
            method,
            instructionMatches,
            stringMatches,
        )

        return _matchOrNull
    }

    /**
     * Matches each filter relative to the match of the filter declared before it. If a filter
     * cannot match, then the filter before it is retried with its next matching instruction, and so on
     * back to the first filter. Each filter tries instructions in the order its location searches,
     * and the first combination of instructions that matches every filter is used.
     *
     * @return The matches in the order the filters are declared, or null if the filters do not match.
     */
    private fun matchFilters(
        method: Method,
        instructions: List<Instruction>,
        filters: List<InstructionFilter>,
    ): List<Match.InstructionMatch>? {
        val lastMethodIndex = instructions.lastIndex
        val lastFilterIndex = filters.lastIndex
        // The instruction index each filter matched.
        val matchIndexes = IntArray(filters.size)
        // The instruction index each filter continues searching from.
        val searchIndexes = IntArray(filters.size)
        // Created once a filter after the first filter does not match.
        var failures: FilterFailures? = null

        var filterIndex = 0
        searchIndexes[0] = filters.first().location.searchStartIndex(-1, lastMethodIndex)

        while (true) {
            val filter = filters[filterIndex]
            val location = filter.location
            val previousIndex = if (filterIndex == 0) -1 else matchIndexes[filterIndex - 1]
            val searchStep = if (location.searchesBackwards) -1 else 1
            var subIndex = searchIndexes[filterIndex]
            var matchedIndex = -1

            while (subIndex in 0..lastMethodIndex &&
                location.indexIsValidForMatching(previousIndex, subIndex)
            ) {
                if (filter.matches(method, instructions[subIndex])) {
                    matchedIndex = subIndex
                    break
                }
                subIndex += searchStep
            }

            if (matchedIndex < 0) {
                if (filterIndex == 0) {
                    return null // First filter has no more matches to start from.
                }

                if (failures == null) failures = FilterFailures(filters.size, instructions.size)
                failures.add(filterIndex, location, previousIndex)

                // Retry the previous filter.
                filterIndex--
                continue
            }

            matchIndexes[filterIndex] = matchedIndex
            // If this filter is retried, then searching continues after this match.
            searchIndexes[filterIndex] = matchedIndex + searchStep

            if (filterIndex == lastFilterIndex) {
                return List(filters.size) { index ->
                    val instructionIndex = matchIndexes[index]
                    Match.InstructionMatch(filters[index], instructionIndex, instructions[instructionIndex])
                }
            }

            val nextLocation = filters[filterIndex + 1].location
            if (failures != null && failures.contains(filterIndex + 1, nextLocation, matchedIndex)) {
                continue // The next filter already failed after this index, so retry this filter.
            }

            filterIndex++
            searchIndexes[filterIndex] = nextLocation.searchStartIndex(matchedIndex, lastMethodIndex)
        }
    }

    /**
     * Matches as patches built for a version of Morphe Patcher older than 2.0.0 expect.
     * If a filter cannot match, then only the first filter is retried with its next matching
     * instruction, and the first filter is not retried if it searches backwards.
     *
     * @return The matches in the order the filters are declared, or null if the filters do not match.
     */
    private fun matchFiltersRetryingFirstFilter(
        method: Method,
        instructions: List<Instruction>,
        filters: List<InstructionFilter>,
    ): List<Match.InstructionMatch>? {
        val lastMethodIndex = instructions.lastIndex
        var instructionMatches : MutableList<Match.InstructionMatch>? = null

        val firstLocation = filters.first().location
        var firstInstructionIndex = firstLocation.searchStartIndex(-1, lastMethodIndex)
        var lastMatchIndex = -1

        firstFilterLoop@ while (true) {
            // Matched index of the first filter.
            var firstFilterIndex = -1

            for (filterIndex in filters.indices) {
                val filter = filters[filterIndex]
                val location = filter.location
                val searchStep = if (location.searchesBackwards) -1 else 1
                var subIndex = if (filterIndex == 0) {
                    firstInstructionIndex
                } else {
                    location.searchStartIndex(lastMatchIndex, lastMethodIndex)
                }
                var instructionsMatched = false

                while (subIndex in 0..lastMethodIndex &&
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
                            instructionMatches = ArrayList(filters.size)
                        }
                        instructionMatches += Match.InstructionMatch(filter, subIndex, instruction)
                        instructionsMatched = true
                        break
                    }
                    subIndex += searchStep
                }

                if (!instructionsMatched) {
                    if (filterIndex == 0) {
                        return null // First filter has no more matches to start from.
                    }

                    if (firstLocation.searchesBackwards) {
                        return null // First filter is not retried with a different index.
                    }

                    // Retrying moves the first filter match forward, which can only move the
                    // previous match of this filter forward. A filter that searches everything
                    // after the previous match cannot match after retrying.
                    if (location is InstructionLocation.MatchAfterAnywhere ||
                        location is InstructionLocation.MatchAfterLast
                    ) {
                        return null // Filter does not match anywhere, no need to continue.
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

    /**
     * Until the implementation of a mutable method is created, the instructions of the method
     * are the instructions of the source method. Checks if each string and filter matches any
     * instruction of the source method, so the implementation of a mutable method that
     * cannot match is not created.
     *
     * @return False if the method cannot match.
     */
    private fun sourceInstructionsCanMatch(method: MutableMethod): Boolean {
        val stringFiltersLocal = stringFilters
        val filtersLocal = filters
        if (stringFiltersLocal == null && filtersLocal == null) return true
        // Custom filters can depend on more than the values of the instructions.
        if (filtersLocal != null && !filtersLocal.all(::isBundledFilter)) return true

        val instructions = method.sourceMethod.instructionsOrNull ?: return true

        val stringsMatched = BooleanArray(stringFiltersLocal?.size ?: 0)
        val filtersMatched = BooleanArray(filtersLocal?.size ?: 0)
        var remaining = stringsMatched.size + filtersMatched.size

        for (instruction in instructions) {
            stringFiltersLocal?.forEachIndexed { index, filter ->
                if (!stringsMatched[index] && filter.matches(method, instruction)) {
                    stringsMatched[index] = true
                    remaining--
                }
            }

            filtersLocal?.forEachIndexed { index, filter ->
                if (!filtersMatched[index] && filter.matches(method, instruction)) {
                    filtersMatched[index] = true
                    remaining--
                }
            }

            if (remaining == 0) return true
        }

        return false
    }

    private fun isBundledFilter(filter: InstructionFilter): Boolean =
        if (filter is AnyInstruction) filter.filters.all(::isBundledFilter)
        else filter::class in BUNDLED_INSTRUCTION_FILTERS

    private fun findFilterStrings(stringEqualMatch: MutableList<String>) {
        fun addStringFilterLiterals(list: List<StringFilter>) {
            list.forEach { filter ->
                stringEqualMatch.add(filter.stringValue)
            }
        }

        // Unordered string declarations.
        stringFilters?.let(::addStringFilterLiterals)

        if (filters != null) {
            fun filterStringFilterInstances(list: List<InstructionFilter>) =
                list.filterIsInstance<StringFilter>()

            addStringFilterLiterals(filterStringFilterInstances(filters))

            // Use strings declared inside anyInstruction.
            filters.filterIsInstance<AnyInstruction>().forEach { anyFilter ->
                addStringFilterLiterals(filterStringFilterInstances(anyFilter.filters))
            }
        }
    }

    /**
     * Matches all methods in the class that match, or returns NULL if none match.
     */
    context(patchContext: BytecodePatchContext)
    fun matchAllOrNull(classDef: ClassDef): List<Match>? {
        if (!classMatches(classDef)) return null

        val matches = mutableListOf<Match>()

        for (method in methodsOf(classDef)) {
            val match = matchMethodOrNull(method, classDef)
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
    context(patchContext: BytecodePatchContext)
    fun matchAllOrNull(): List<Match>? {
        if (patchContext.validate) {
            return logIfSlow { matchAllOrNullUntimed() }
        }

        return matchAllOrNullUntimed()
    }

    context(patchContext: BytecodePatchContext)
    private fun matchAllOrNullUntimed(): List<Match>? {
        // matchAll is an independent search, not an extension of a cached match.
        clearMatch()

        if (classFingerprint != null) {
            if (!patchContext.validate) {
                return matchAll(classFingerprint.match().classDef)
            }

            // Only one of the classes the class fingerprint matches can contain matches.
            val matchesByClass = classFingerprint.classFingerprintClasses().mapNotNull { classDef ->
                matchAllInClassOrNull(classDef)
            }
            if (matchesByClass.size > 1) {
                throw PatchException(
                    "Fingerprint validation failed. Expected matches in a single class but found " +
                        "${matchesByClass.size} classes: $this Matched classes: " +
                        matchesByClass.joinToString { it.first().classType }
                )
            }
            return matchesByClass.firstOrNull()
        }

        val matches = mutableListOf<Match>()

        fun machAllClassMethods(
            value: PatchClasses.ClassDefWrapper,
            methods: Iterable<Method> = value.methods,
        ) {
            val classDef = value.classDef
            if (!classMatches(classDef)) return

            methods.forEach { method ->
                val match = matchMethodOrNull(method, classDef)
                if (match != null) {
                    matches += match
                    clearMatch()
                }
            }
        }

        if (definingClass != null) {
            forEachDefiningClass { value -> machAllClassMethods(value) }
            return matches.ifEmpty { null }
        }

        // Custom instruction filters could do logical filtering,
        // so only fingerprints with bundled filters use the instruction indexes.
        if (filters?.all(::isBundledFilter) ?: (stringFilters != null)) {
            indexedCandidates()?.let { candidates ->
                candidates.classes.forEach { value ->
                    machAllClassMethods(value, candidates.methodsOf(value))
                }
                return matches.ifEmpty { null }
            }
        }

        // Check all classes.
        patchContext.patchClasses.classMap.values.forEach { value ->
            machAllClassMethods(value)
        }

        return matches.ifEmpty { null }
    }

    /**
     * Iterates all classes that match [definingClass].
     */
    context(patchContext: BytecodePatchContext)
    private inline fun forEachDefiningClass(action: (PatchClasses.ClassDefWrapper) -> Unit) {
        val definingClassLocal = definingClass!!
        val classMap = patchContext.patchClasses.classMap

        val definingClassComparisonLocal = definingClassComparison
        if (definingClassComparisonLocal == StringComparisonType.EQUALS) {
            classMap[definingClassLocal]?.let(action)
            return
        }

        classMap.forEach { (type, wrapper) ->
            // Mutable classes can be renamed. The type of immutable classes is
            // the map key, which avoids decoding the type of dex backed classes.
            val classDef = wrapper.classDef
            val classType = if (classDef is MutableClass) classDef.type else type
            if (definingClassComparisonLocal.compare(classType, definingClassLocal)) {
                action(wrapper)
            }
        }
    }

    /**
     * The methods of [classDef]. Uses the methods cached by the class map if possible.
     */
    context(patchContext: BytecodePatchContext)
    private fun methodsOf(classDef: ClassDef): Iterable<Method> {
        if (classDef is MutableClass) return classDef.methods

        val wrapper = patchContext.patchClasses.classMap[classDef.type]
        return if (wrapper != null && wrapper.classDef === classDef) wrapper.methods else classDef.methods
    }

    /**
     * Classes that can have a matching method, or null if the instruction indexes cannot limit the classes.
     *
     * Each matching method has an instruction for each string and each filter that is not part of
     * another filter, so a class without the indexed values of one of them cannot match.
     * Custom instruction filters could do logical filtering, so filters are used only if all
     * filters are bundled filters.
     */
    context(patchContext: BytecodePatchContext)
    private fun indexedCandidates(): PatchClasses.Candidates? {
        val patchClasses = patchContext.patchClasses
        val conditions = ArrayList<PatchClasses.IndexCondition>()
        conditions += requiredStringConditions()

        val filtersLocal = filters
        if (filtersLocal != null && filtersLocal.all(::isBundledFilter)) {
            filtersLocal.forEach { filter ->
                // A literal that does not exist in this app cannot match anywhere.
                if (filter is LiteralFilter && filter.literalValue == null) {
                    return PatchClasses.Candidates(emptyList(), emptyMap())
                }

                // Strings that all matching methods have are already added.
                if (filter !is StringFilter) {
                    filter.addIndexConditions(patchClasses, conditions)
                }
            }
        }
        if (conditions.isEmpty()) return null

        return patchClasses.candidateConditionClasses(conditions)
    }

    /**
     * Adds a condition for each value this filter requires an instruction to have.
     *
     * @return False if this filter can match instructions without indexed values.
     */
    context(patchContext: BytecodePatchContext)
    private fun InstructionFilter.addIndexConditions(
        patchClasses: PatchClasses,
        conditions: MutableList<PatchClasses.IndexCondition>,
    ): Boolean {
        val conditionsSize = conditions.size

        fun addType(type: String?) {
            if (type != null && isIndexedType(type)) {
                conditions += PatchClasses.IndexCondition(patchClasses.getIndexedClassesReferencingType(type)) {
                    it.hasType(type)
                }
            }
        }

        fun addMemberName(name: String?) {
            if (name != null) {
                conditions += PatchClasses.IndexCondition(patchClasses.getIndexedClassesReferencingMemberName(name)) {
                    it.hasMemberName(name)
                }
            }
        }

        when (this) {
            is LiteralFilter -> {
                val literal = literalValue
                conditions += if (literal == null) {
                    // A literal that does not exist in this app matches no instruction.
                    PatchClasses.IndexCondition(emptyList()) { false }
                } else {
                    PatchClasses.IndexCondition(patchClasses.getIndexedClassesContainingLiteral(literal)) {
                        it.hasLiteral(literal)
                    }
                }
            }
            is StringFilter -> conditions += stringConditions(listOf(this)).single()
            is MethodCallFilter -> {
                addType(definingClass)
                addMemberName(name)
                addType(returnType)
                parameters?.forEach(::addType)
            }
            is FieldAccessFilter -> {
                addType(definingClass)
                addMemberName(name)
                addType(type)
            }
            is NewInstanceFilter -> addType(typeValue)
            is InstanceOfFilter -> addType(typeValue)
            is CheckCastFilter -> addType(typeValue)
            is AnyInstruction -> {
                // A class can match if it can match any of the filters.
                val alternatives = filters.map { filter ->
                    val alternative = ArrayList<PatchClasses.IndexCondition>()
                    if (!filter.addIndexConditions(patchClasses, alternative)) return false
                    alternative
                }
                val indexedClasses = patchClasses.indexedClassesOfAny(
                    alternatives.map { alternative -> alternative.map { it.indexedClasses } }
                )
                conditions += PatchClasses.IndexCondition(indexedClasses) { values ->
                    alternatives.any { alternative -> alternative.all { it.isSatisfiedBy(values) } }
                }
            }
        }

        return conditions.size > conditionsSize
    }

    /**
     * A condition for each string that all matching methods have,
     * from the unordered strings and the string filters that are not part of another filter.
     */
    context(patchContext: BytecodePatchContext)
    private fun requiredStringConditions(): List<PatchClasses.IndexCondition> {
        val requiredStrings = ArrayList<StringFilter>()
        stringFilters?.let(requiredStrings::addAll)
        filters?.filterIsInstanceTo(requiredStrings)

        return stringConditions(requiredStrings)
    }

    /**
     * A condition for the string of each filter, in the order of the filters.
     */
    context(patchContext: BytecodePatchContext)
    private fun stringConditions(stringFilters: List<StringFilter>): List<PatchClasses.IndexCondition> =
        stringIndexedClasses(stringFilters).mapIndexed { index, indexedClasses ->
            val string = stringFilters[index].stringValue
            val comparison = stringFilters[index].comparison
            PatchClasses.IndexCondition(indexedClasses) { values ->
                if (comparison == StringComparisonType.EQUALS) string in values.strings
                else values.strings.any { comparison.compare(it, string) }
            }
        }

    /**
     * Indexed classes with a string that each filter can match, in the order of the filters.
     */
    context(patchContext: BytecodePatchContext)
    private fun stringIndexedClasses(
        stringFilters: List<StringFilter>,
    ): List<List<PatchClasses.ClassDefWrapper>> {
        if (stringFilters.isEmpty()) return emptyList()

        val stringMap = patchContext.patchClasses.getClassesByReferenceMap()

        val indexedClasses = stringFilters.map { filter ->
            if (filter.comparison == StringComparisonType.EQUALS) stringMap[filter.stringValue].orEmpty() else ArrayList()
        }

        val partialMatches = stringFilters.indices.filter { index ->
            stringFilters[index].comparison != StringComparisonType.EQUALS
        }
        if (partialMatches.isNotEmpty()) {
            val partialClasses = partialMatches.map { HashSet<PatchClasses.ClassDefWrapper>() }
            stringMap.forEach { (instructionString, classes) ->
                partialMatches.forEachIndexed { index, filterIndex ->
                    val filter = stringFilters[filterIndex]
                    if (filter.comparison.compare(instructionString, filter.stringValue)) {
                        partialClasses[index].addAll(classes)
                    }
                }
            }
            partialMatches.forEachIndexed { index, filterIndex ->
                (indexedClasses[filterIndex] as MutableList).addAll(partialClasses[index])
            }
        }

        return indexedClasses
    }

    /**
     * If the indexes have the classes that reference this type declaration. Primitive types are not indexed.
     */
    private fun isIndexedType(type: String): Boolean = type.length > 1 && type != "this" &&
            StringComparisonType.typeDeclarationToComparison(type) == StringComparisonType.EQUALS

    fun patchException() = PatchException("Failed to match the fingerprint: $this")

    /**
     * A named fingerprint class (such as an `object` declaration) is identified by its class name.
     * Other fingerprints, such as ones built inside a patch, are described by their declared fields.
     */
    override fun toString(): String {
        if (javaClass != Fingerprint::class.java && !javaClass.isAnonymousClass) {
            return super.toString()
        }

        val fields = buildList {
            classFingerprint?.let { add("classFingerprint=$it") }
            definingClass?.let { add("definingClass=$it") }
            superclass?.let { add("superclass=$it") }
            interfaces?.let { add("interfaces=$it") }
            name?.let { add("name=$it") }
            accessFlags?.let { add("accessFlags=${AccessFlags.formatAccessFlagsForMethod(it)}") }
            isStatic?.let { add("isStatic=$it") }
            returnType?.let { add("returnType=$it") }
            parameters?.let { add("parameters=$it") }
            filters?.let { filters -> add("filters=${filters.map { it.javaClass.simpleName }}") }
            strings?.let { add("strings=$it") }
            custom?.let { add("custom") }
        }
        return "Fingerprint(${fields.joinToString()})"
    }

    /**
     * The match for this [Fingerprint].
     *
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the [Fingerprint] failed to match.
     */
    context(patchContext: BytecodePatchContext)
    fun match() = matchOrNull() ?: throw patchException()

    /**
     * Match using a [ClassDef].
     *
     * @param classDef The class to match against.
     * @return The [Match] of this fingerprint.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(patchContext: BytecodePatchContext)
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
    context(patchContext: BytecodePatchContext)
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
    context(patchContext: BytecodePatchContext)
    fun match(
        method: Method,
        classDef: ClassDef,
    ) = matchOrNull(method, classDef) ?: throw patchException()

    /**
     * Matches all methods in the target app.
     *
     * @param classDef The class the method is a member of.
     * @return All methods that match.
     * @throws PatchException If the fingerprint failed to match methods.
     */
    context(patchContext: BytecodePatchContext)
    fun matchAll(
        classDef: ClassDef,
    ) = matchAllOrNull(classDef) ?: throw patchException()

    /**
     * Matches all methods in the target app, requiring the number of matches
     * to be in the specified range. A range including zero is allowed and
     * returns an empty list if no matches exist.
     *
     * @param classDef The class the method is a member of.
     * @return All methods that match.
     * @throws PatchException If the fingerprint failed to match.
     */
    context(patchContext: BytecodePatchContext)
    fun matchAll(classDef: ClassDef, range: IntRange): List<Match> {
        val matches = matchAllOrNull(classDef)

        return checkMatchesRange(matches, range)
    }

    /**
     * Match all methods in the target app.
     *
     * @return All methods that match.
     * @throws PatchException If the fingerprint failed to match any methods.
     */
    context(patchContext: BytecodePatchContext)
    fun matchAll() = matchAllOrNull() ?: throw patchException()

    /**
     * Match all methods in the target app, requiring the number of matches to be
     * in the specified range. A range including zero is allowed and returns an
     * empty list if no matches exist.
     *
     * @return All methods that match.
     * @throws PatchException If the number of matches is outside the range of [range].
     */
    context(patchContext: BytecodePatchContext)
    fun matchAll(range: IntRange): List<Match> {
        val matches = matchAllOrNull()

        return checkMatchesRange(matches, range)
    }

    private fun checkMatchesRange(
        matches: List<Match>?,
        range: IntRange
    ): List<Match> {
        if (matches == null) {
            if (range.first == 0) {
                return emptyList()
            }
            throw patchException()
        }

        if (matches.size !in range) {
            throw PatchException("Expected a number of matches in $range but instead found ${matches.size}: $this")
        }

        return matches
    }


    /**
     * The class the matching method is a member of, or null if this fingerprint did not match.
     */
    context(patchContext: BytecodePatchContext)
    val originalClassDefOrNull
        get() = matchOrNull()?.originalClassDef

    /**
     * The matching method, or null of this fingerprint did not match.
     */
    context(patchContext: BytecodePatchContext)
    val originalMethodOrNull
        get() = matchOrNull()?.originalMethod

    /**
     * The mutable version of [originalClassDefOrNull].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalClassDefOrNull] if mutable access is not required.
     */
    context(patchContext: BytecodePatchContext)
    val classDefOrNull
        get() = matchOrNull()?.classDef

    /**
     * The mutable version of [originalMethodOrNull].
     *
     * Accessing this property allocates a new mutable instance.
     * Use [originalMethodOrNull] if mutable access is not required.
     */
    context(patchContext: BytecodePatchContext)
    val methodOrNull
        get() = matchOrNull()?.method

    /**
     * The match for the instruction filters, or null if this fingerprint did not match.
     */
    context(patchContext: BytecodePatchContext)
    val instructionMatchesOrNull
        get() = matchOrNull()?.instructionMatchesOrNull

    /**
     * The class the matching method is a member of.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(patchContext: BytecodePatchContext)
    val originalClassDef
        get() = match().originalClassDef

    /**
     * The matching method.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(patchContext: BytecodePatchContext)
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
    context(patchContext: BytecodePatchContext)
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
    context(patchContext: BytecodePatchContext)
    val method
        get() = match().method

    /**
     * Instruction filter matches.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(patchContext: BytecodePatchContext)
    val instructionMatches
        get() = match().instructionMatches

    /**
     * The matches for the strings declared using `strings()`.
     * This does not give matches for strings declared using [string] instruction filters.
     *
     * @throws PatchException If the fingerprint has not been matched.
     */
    context(patchContext: BytecodePatchContext)
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
    context(patchContext: BytecodePatchContext)
    val stringMatchesOrNull
        get() = matchOrNull()?.stringMatchesOrNull
}

/**
 * Filters of a fingerprint that did not match after the filter declared before them matched an
 * instruction index. Whether the remaining filters match depends only on the index the previous
 * filter matched, so a failure applies no matter how the filters before it matched.
 */
private class FilterFailures(filterCount: Int, private val instructionCount: Int) {
    /**
     * For a filter that searches every instruction after or before the previous match, the failed
     * previous match index that searched the most instructions, or -1 if none failed. Searching from
     * an index farther in the search direction tries only instructions that were already tried.
     */
    private val unboundedFailures = IntArray(filterCount) { -1 }

    /**
     * Failures of filters that search a limited number of instructions, by filter and previous match index.
     */
    private var boundedFailures: BitSet? = null

    fun add(filterIndex: Int, location: InstructionLocation, previousIndex: Int) {
        val failedIndex = unboundedFailures[filterIndex]
        when {
            location.searchesAllAfter() -> if (failedIndex < 0 || previousIndex < failedIndex) {
                unboundedFailures[filterIndex] = previousIndex
            }
            location.searchesAllBefore() -> if (previousIndex > failedIndex) {
                unboundedFailures[filterIndex] = previousIndex
            }
            else -> (boundedFailures ?: BitSet().also { boundedFailures = it })
                .set(filterIndex * instructionCount + previousIndex)
        }
    }

    fun contains(filterIndex: Int, location: InstructionLocation, previousIndex: Int): Boolean {
        val failedIndex = unboundedFailures[filterIndex]
        return when {
            location.searchesAllAfter() -> failedIndex >= 0 && previousIndex >= failedIndex
            location.searchesAllBefore() -> failedIndex >= 0 && previousIndex <= failedIndex
            else -> boundedFailures?.get(filterIndex * instructionCount + previousIndex) == true
        }
    }

    private fun InstructionLocation.searchesAllAfter() =
        this is InstructionLocation.MatchAfterAnywhere || this is InstructionLocation.MatchAfterLast

    private fun InstructionLocation.searchesAllBefore() =
        this is InstructionLocation.MatchBeforeAnywhere || this is InstructionLocation.MatchBeforeFirst
}

/**
 * A match of a [Fingerprint].
 */
class Match internal constructor(
    internal val patchContext: BytecodePatchContext,
    originalClassDef: ClassDef,
    originalMethod: Method,
    private val _instructionMatches: List<InstructionMatch>?,
    private val _stringMatches: List<StringMatch>?,
) {
    // The matched class and method are intentionally not stored, and are instead looked up
    // from the patch context each time they are accessed. Patches can replace classes and
    // methods (making a class mutable, merging extension classes, replacing methods),
    // and holding onto the matched objects would leave this match with stale references
    // that no longer correspond to what the app is actually being compiled with.
    // Not storing them also allows the old replaced objects to be garbage collected.

    /**
     * The type of the matched class.
     */
    internal val classType: String = originalClassDef.type

    /**
     * Signature of the matched method.
     */
    internal val methodReference: MethodReference = ImmutableMethodReference.of(originalMethod)

    /**
     * The immutable class the matching method is a member of.
     */
    val originalClassDef: ClassDef
        get() = patchContext.classDefBy(classType)

    /**
     * The matching immutable method.
     */
    val originalMethod: Method
        get() = originalClassDef.methods.findMethod()

    /**
     * The mutable version of [originalClassDef].
     *
     * Accessing this property allocates a new mutable instance if the class is not already mutable.
     * Use [originalClassDef] if mutable access is not required.
     *
     * **Calling this unnecessarily when no mutable changes are applied can cause out of memory errors**
     */
    val classDef: MutableClass
        get() = patchContext.mutableClassDefBy(classType)

    /**
     * The mutable version of [originalMethod].
     *
     * Accessing this property allocates a new mutable instance if the class is not already mutable.
     * Use [originalMethod] if mutable access is not required.
     *
     * * **Calling this unnecessarily when no mutable changes are applied can cause out of memory errors**
     */
    val method: MutableMethod
        get() = classDef.methods.findMethod()

    private fun <T : Method> Iterable<T>.findMethod() = firstOrNull { classMethod ->
        MethodUtil.methodSignaturesMatch(classMethod, methodReference)
    } ?: throw PatchException("Could not find matched method: $methodReference")

    /**
     * Matches corresponding to the [InstructionFilter] declared in the [Fingerprint],
     * in the same order the filters are declared.
     *
     * If a filter uses an [InstructionLocation] that matches before the previous filter match,
     * such as [InstructionLocation.MatchBeforeImmediately], then the match indexes are not in
     * ascending order, and more than one filter can match the same instruction index.
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

        /**
         * The method reference of a method call instruction.
         * This may only be used on filters that match method calls such as [methodCall]
         * or [opcode] with an `INVOKE_*` [Opcode].
         *
         * @throws IllegalArgumentException If the instruction is not a method call.
         */
        val methodReference: MethodReference
            get() {
                val reference = (instruction as? ReferenceInstruction)?.reference
                require(reference is MethodReference) {
                    "Matched instruction is not a method call: $instruction"
                }
                return reference
            }

        /**
         * The field reference of a field access instruction.
         * This may only be used on filters that match field accesses such as [fieldAccess]
         * or [opcode] with a `GET`/`PUT` [Opcode].
         *
         * @throws IllegalArgumentException If the instruction is not a field access.
         */
        val fieldReference: FieldReference
            get() {
                val reference = (instruction as? ReferenceInstruction)?.reference
                require(reference is FieldReference) {
                    "Matched instruction is not a field access: $instruction"
                }
                return reference
            }

        /**
         * Returns the mutable method of a method call instruction.
         * This may only be used on filters that match method calls such as [methodCall]
         * or [opcode] with an `INVOKE_*` [Opcode].
         */
        context(patchContext: BytecodePatchContext)
        fun getMethodCalled(): MutableMethod {
            val methodReference = methodReference
            return patchContext.mutableClassDefBy(methodReference.definingClass).methods.first { classMethod ->
                MethodUtil.methodSignaturesMatch(classMethod, methodReference)
            }
        }

        /**
         * Returns the mutable method of a field access instruction.
         * This may only be used on filters that match method calls such as [fieldAccess]
         * or [opcode] with a `GET`/`PUT` [Opcode].
         */
        context(patchContext: BytecodePatchContext)
        fun getFieldAccessed(): MutableField {
            val fieldReference = fieldReference
            val mutableClass = patchContext.mutableClassDefBy(fieldReference.definingClass)
            return mutableClass.fields.first { classField ->
                classField.type == fieldReference.type && classField.name == fieldReference.name
            }
        }

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
    class StringMatch internal constructor(val string: String, val index: Int)

    override fun toString(): String {
        return "Match(method=$methodReference, " +
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
    replaceWith = ReplaceWith("parametersMatch(targetMethodParameters, fingerprintParameters)"),
    level = DeprecationLevel.ERROR
)
fun parametersStartsWith(
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
 * @property strings A list of the strings compared each using [String.equals].
 * @property customBlock A custom condition for this fingerprint.
 *
 * @constructor Create a new [FingerprintBuilder].
 */
@Deprecated(message = "DSL provides no functional benefits over class declarations " +
        "and can make stack traces impossible to know what fingerprint failed to resolve",
    replaceWith = ReplaceWith("app.morphe.patcher.Fingerprint()"),
    level = DeprecationLevel.WARNING
)
class FingerprintBuilder {
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
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
     * with more fine-grained filters.
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
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
     * @param strings A list of strings compared each using [String.equals].
     */
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
    @Deprecated(
        message = "DSL provides no functional benefits over class declarations " +
                "and can make stack traces impossible to know what fingerprint failed to resolve",
        level = DeprecationLevel.WARNING
    )
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
    replaceWith = ReplaceWith("app.morphe.patcher.Fingerprint()"),
    level = DeprecationLevel.WARNING
)
@Suppress("DEPRECATION")
fun fingerprint(
    block: FingerprintBuilder.() -> Unit,
) = FingerprintBuilder().apply(block).build()
