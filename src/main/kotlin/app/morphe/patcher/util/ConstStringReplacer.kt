/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.StringFilter
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * Replaces the strings of `const-string` instructions that match the filters with constant replacements.
 * Each replacement replaces the part of the string its filter matches.
 *
 * @return The number of instructions replaced.
 * @see app.morphe.patcher.patch.replaceStrings
 */
internal fun PatchClasses.replaceConstStrings(replacements: List<Pair<StringFilter, String>>): Int {
    val matcher = StringFilterMatcher(replacements.map { it.first }, rejectDuplicateExactStrings = true)

    return replaceConstStrings(matcher) { filterIndex, _, _, string ->
        val filterString = matcher.strings[filterIndex]
        val replacement = replacements[filterIndex].second

        when (matcher.comparisons[filterIndex]) {
            StringComparisonType.EQUALS -> replacement
            StringComparisonType.STARTS_WITH -> replacement + string.substring(filterString.length)
            StringComparisonType.ENDS_WITH -> string.substring(0, string.length - filterString.length) + replacement
            StringComparisonType.CONTAINS -> string.replace(filterString, replacement)
        }
    }
}

/**
 * Replaces the strings of `const-string` instructions that match any of the filters
 * with the strings [replacement] returns.
 *
 * @return The number of instructions replaced.
 * @see app.morphe.patcher.patch.replaceStrings
 */
internal fun PatchClasses.replaceConstStrings(
    filters: List<StringFilter>,
    replacement: (method: Method, index: Int, string: String) -> String?,
): Int {
    val matcher = StringFilterMatcher(filters, rejectDuplicateExactStrings = false)

    return replaceConstStrings(matcher) { _, method, index, string ->
        replacement(method, index, string)
    }
}

/**
 * Replaces the strings in a single pass over the classes that can have a matching string.
 *
 * The replacements of a class are found with the instructions it has before any replacement,
 * and the class is made mutable only if it has a string to replace.
 */
private fun PatchClasses.replaceConstStrings(
    matcher: StringFilterMatcher,
    replacement: (filterIndex: Int, method: Method, index: Int, string: String) -> String?,
): Int {
    if (matcher.isEmpty) return 0

    // The indexes have the strings the classes had when they were indexed. A single condition with the
    // classes of all filters also finds mutable classes whose changed methods can have a matching string.
    val condition = PatchClasses.IndexCondition(matcher.indexedClasses(getClassesByReferenceMap())) { values ->
        values.strings.any { string -> matcher.filterIndexOf(string) >= 0 }
    }
    val candidates = candidateConditionClasses(listOf(condition))

    var replacedCount = 0
    candidates.classes.forEach { wrapper ->
        replacedCount += replaceInClass(wrapper, candidates.methodsOf(wrapper), matcher, replacement)
    }
    return replacedCount
}

/**
 * Replaces the matching strings of the methods of a class.
 *
 * @param methods The methods of the class that can have a matching string. For a class that is not
 *                mutable, these are all methods of the class in the order of the class.
 * @return The number of instructions replaced.
 */
private fun replaceInClass(
    wrapper: PatchClasses.ClassDefWrapper,
    methods: Iterable<Method>,
    matcher: StringFilterMatcher,
    replacement: (filterIndex: Int, method: Method, index: Int, string: String) -> String?,
): Int {
    val classDef = wrapper.classDef
    var changedMethods: ArrayList<MethodReplacements>? = null

    var methodIndex = 0
    for (method in methods) {
        val currentMethodIndex = methodIndex++
        // For a mutable method that was not changed, these are the instructions of the method it was
        // created from, so finding the strings does not create the mutable implementation.
        val instructions = method.instructionsOrNull ?: continue
        var replacements: ArrayList<InstructionReplacement>? = null

        var index = 0
        for (instruction in instructions) {
            val instructionIndex = index++

            val opcode = instruction.opcode
            if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) continue

            val string = ((instruction as ReferenceInstruction).reference as StringReference).string
            val filterIndex = matcher.filterIndexOf(string)
            if (filterIndex < 0) continue

            val newString = replacement(filterIndex, method, instructionIndex, string)
            if (newString == null || newString == string) continue

            if (replacements == null) replacements = ArrayList()
            replacements.add(
                InstructionReplacement(instructionIndex, (instruction as OneRegisterInstruction).registerA, newString)
            )
        }

        if (replacements != null) {
            if (changedMethods == null) changedMethods = ArrayList()
            changedMethods.add(MethodReplacements(currentMethodIndex, method, replacements))
        }
    }

    if (changedMethods == null) return 0

    // The methods of a class that was mutable are already mutable.
    val mutableMethods = if (classDef is MutableClass) null else wrapper.getMutableClass().methods.toList()

    var replacedCount = 0
    changedMethods.forEach { (methodIndex, method, replacements) ->
        val mutableMethod = mutableMethods?.findMutableMethod(methodIndex, method) ?: method as MutableMethod

        // The replacement has the same number of instructions, so the indexes do not change.
        // The dex writer changes it to const-string/jumbo if the string index needs it.
        replacements.forEach { (index, register, string) ->
            mutableMethod.replaceInstruction(
                index,
                BuilderInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(string)),
            )
        }
        replacedCount += replacements.size
    }
    return replacedCount
}

/**
 * The mutable method of a method of the class the mutable methods were created from.
 *
 * @param methodIndex The position of [method] in the methods of the class. The mutable methods are created
 *                    in the same order, so the method at this position is the method unless the methods
 *                    were changed.
 */
private fun List<MutableMethod>.findMutableMethod(methodIndex: Int, method: Method): MutableMethod {
    getOrNull(methodIndex)?.let { mutableMethod ->
        if (MethodUtil.methodSignaturesMatch(mutableMethod, method)) return mutableMethod
    }

    return firstOrNull { mutableMethod -> MethodUtil.methodSignaturesMatch(mutableMethod, method) }
        ?: throw PatchException("Could not find method: $method")
}

private data class InstructionReplacement(val index: Int, val register: Int, val string: String)

private data class MethodReplacements(
    val methodIndex: Int,
    val method: Method,
    val replacements: List<InstructionReplacement>,
)

/**
 * Finds the first declared filter that matches a string.
 * The values of the filters are evaluated once, and the location of the filters is not used.
 *
 * @param rejectDuplicateExactStrings If an exact string declared by more than one filter is an error.
 */
private class StringFilterMatcher(filters: List<StringFilter>, rejectDuplicateExactStrings: Boolean) {
    val strings = Array(filters.size) { index -> filters[index].string() }
    val comparisons = Array(filters.size) { index -> filters[index].comparison }

    /**
     * Exact string -> index of the first filter that declares it.
     */
    private val exactFilterIndexes = HashMap<String, Int>()

    /**
     * Indexes of the filters that are not exact, in the declared order.
     */
    private val partialFilterIndexes: IntArray

    val isEmpty: Boolean
        get() = strings.isEmpty()

    init {
        val partialIndexes = ArrayList<Int>()

        strings.forEachIndexed { index, string ->
            if (comparisons[index] == StringComparisonType.EQUALS) {
                val previousIndex = exactFilterIndexes.putIfAbsent(string, index)
                require(previousIndex == null || !rejectDuplicateExactStrings) {
                    "String is declared more than once: \"$string\""
                }
            } else {
                require(string.isNotEmpty()) {
                    "String filter with comparison ${comparisons[index]} cannot have an empty string"
                }
                partialIndexes += index
            }
        }

        partialFilterIndexes = partialIndexes.toIntArray()
    }

    /**
     * @return The index of the first declared filter that matches [string], or -1 if no filter matches.
     */
    fun filterIndexOf(string: String): Int {
        val exactIndex = exactFilterIndexes[string] ?: NO_FILTER

        for (filterIndex in partialFilterIndexes) {
            if (filterIndex > exactIndex) break
            if (comparisons[filterIndex].compare(string, strings[filterIndex])) return filterIndex
        }

        return if (exactIndex == NO_FILTER) -1 else exactIndex
    }

    /**
     * Indexed classes with a string that a filter matches, without duplicates.
     */
    fun indexedClasses(
        stringMap: Map<String, List<PatchClasses.ClassDefWrapper>>,
    ): List<PatchClasses.ClassDefWrapper> {
        val classes = LinkedHashSet<PatchClasses.ClassDefWrapper>()

        exactFilterIndexes.keys.forEach { string ->
            stringMap[string]?.let(classes::addAll)
        }

        // Exact strings are found above, so only the other filters are compared with every string.
        if (partialFilterIndexes.isNotEmpty()) {
            stringMap.forEach { (string, stringClasses) ->
                val matches = partialFilterIndexes.any { filterIndex ->
                    comparisons[filterIndex].compare(string, strings[filterIndex])
                }
                if (matches) classes.addAll(stringClasses)
            }
        }

        return ArrayList(classes)
    }

    private companion object {
        private const val NO_FILTER = Int.MAX_VALUE
    }
}
