/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.stream.Collectors

/**
 * All classes for the target app and any extension classes.
 */
internal class PatchClasses internal constructor(
    /**
     * Class type -> ClassDef.
     */
    internal val classMap: MutableMap<String, ClassDefWrapper>
) {

    /**
     * Container to hold the class definition that is either mutable or immutable.
     *
     * This intermediate container is needed to easily update the class in both
     * the class map and in the string map with a single constant time operation.
     */
    internal class ClassDefWrapper(
        /**
         * Can be immutable or mutable.
         */
        var classDef: ClassDef,
        internal var onMakeMutable: ((ClassDefWrapper) -> Unit)? = null,
    ) {
        /** Sorted hashes of class types referenced by instructions in this class. */
        var referencedTypeHashes: IntArray? = null

        /** Sorted literal values used by instructions in this class. */
        var literalValues: LongArray? = null

        fun getMutableClass(): MutableClass {
            if (classDef !is MutableClass) {
                classDef = MutableClass(classDef)
                onMakeMutable?.invoke(this)
            }
            return classDef as MutableClass
        }
    }

    private val mutableWrappers = LinkedHashSet<ClassDefWrapper>()

    private val onWrapperMadeMutable: (ClassDefWrapper) -> Unit = { wrapper ->
        mutableWrappers.add(wrapper)
    }

    init {
        classMap.values.forEach { wrapper ->
            wrapper.onMakeMutable = onWrapperMadeMutable
            if (wrapper.classDef is MutableClass) {
                mutableWrappers.add(wrapper)
            }
        }
    }

    private class ClassIndexCollector {
        val strings: MutableSet<String> = HashSet()
        val referencedTypeHashes: MutableSet<Int> = HashSet()
        val literalValues: MutableSet<Long> = HashSet()

        fun clear() {
            strings.clear()
            referencedTypeHashes.clear()
            literalValues.clear()
        }
    }

    /** Collect string, type-reference, and literal values into [collector] in one traversal. */
    private fun ClassDef.collectIndexValues(collector: ClassIndexCollector) {
        collector.clear()
        methods.forEach { method ->
            method.instructionsOrNull?.forEach { instruction ->
                if (instruction is WideLiteralInstruction) {
                    collector.literalValues += instruction.wideLiteral
                }
                val reference = (instruction as? ReferenceInstruction)?.reference ?: return@forEach
                when (reference) {
                    is StringReference -> if (
                        instruction.opcode == Opcode.CONST_STRING ||
                        instruction.opcode == Opcode.CONST_STRING_JUMBO
                    ) {
                        collector.strings += reference.string
                    }
                    is MethodReference -> collector.referencedTypeHashes += reference.definingClass.hashCode()
                    is FieldReference -> collector.referencedTypeHashes += reference.definingClass.hashCode()
                    is TypeReference -> collector.referencedTypeHashes += reference.type.hashCode()
                }
            }
        }
    }

    /**
     * Opcode string constant -> List<ClassDefWrapper>
     */
    private var stringMap: MutableMap<String, MutableList<ClassDefWrapper>>? = null

    /**
     * Referenced type hash -> List<ClassDefWrapper>
     */
    private var typeMap: MutableMap<Int, MutableList<ClassDefWrapper>>? = null

    /**
     * Literal value -> List<ClassDefWrapper>
     */
    private var literalMap: MutableMap<Long, MutableList<ClassDefWrapper>>? = null

    /**
     * All classes that contain at least 1 string.
     * Same contents as [stringMap] values except contains no duplicates.
     */
    private var allClassesWithStrings: MutableList<ClassDefWrapper>? = null

    internal constructor(set: Set<ClassDef>) : this(
        // Must use linked hash map. A regular map does not preserve the order of classes found
        // in the apk, so old fingerprints that have multiple matches can match the wrong class
        // due to hashmap random class iteration during matching. The issue is with
        // some fingerprint declarations not being unique enough and currently there is no way to
        // check for duplicate matches.
        // See https://github.com/ReVanced/revanced-patcher/issues/74
        //
        // Pre-size so rehashing doesn't occur and use a more performant load factor.
        LinkedHashMap<String, ClassDefWrapper>(2 * set.size, 0.5f)
    ) {
        for (classDef in set) {
            val wrapper = ClassDefWrapper(classDef, onWrapperMadeMutable)
            if (classDef is MutableClass) {
                mutableWrappers.add(wrapper)
            }
            classMap[classDef.type] = wrapper
        }
    }

    internal fun close() {
        classMap.clear()
        closeReferenceMap()
    }

    internal fun closeReferenceMap() {
        stringMap = null
        typeMap = null
        literalMap = null
        allClassesWithStrings = null
        mutableWrappers.clear()
        classMap.values.forEach { wrapper ->
            wrapper.referencedTypeHashes = null
            wrapper.literalValues = null
            if (wrapper.classDef is MutableClass) {
                mutableWrappers.add(wrapper)
            }
        }
    }

    internal fun addClass(classDef: ClassDef) {
        val wrapper = ClassDefWrapper(classDef, onWrapperMadeMutable)
        if (classDef is MutableClass) {
            mutableWrappers.add(wrapper)
        }
        classMap[classDef.type] = wrapper

        // Classes are added while patches execute (extension merges), which can happen after the
        // instruction indexes were built. Index the new class incrementally, otherwise string
        // lookups and candidate scans never see it.
        val stringMapLocal = stringMap
        val classesWithStringsLocal = allClassesWithStrings
        if (stringMapLocal != null && classesWithStringsLocal != null) {
            val collector = ClassIndexCollector()
            wrapper.classDef.collectIndexValues(collector)
            indexWrapper(wrapper, collector, stringMapLocal, typeMap, literalMap, classesWithStringsLocal)
        }
    }

    internal fun getClassesByReferenceMap(): Map<String, List<ClassDefWrapper>> {
        if (stringMap != null) {
            return stringMap!!
        }

        return buildInstructionIndexes()
    }

    private fun buildInstructionIndexes(): Map<String, List<ClassDefWrapper>> {
        // Default 0.75f load factor works well and a lower value does not improve patching time.
        val strings = HashMap<String, MutableList<ClassDefWrapper>>()
        val types = HashMap<Int, MutableList<ClassDefWrapper>>()
        val literals = HashMap<Long, MutableList<ClassDefWrapper>>()
        val classesWithStrings = mutableListOf<ClassDefWrapper>()

        // Scanning the instructions is the costly part and reads each class on its own, so it runs
        // in parallel, a chunk at a time to bound memory, while the indexes are filled in class order.
        classMap.values.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
            chunk.parallelStream().map { wrapper ->
                ClassIndexCollector().also { collector -> wrapper.classDef.collectIndexValues(collector) }
            }.collect(Collectors.toList()).forEachIndexed { i, collector ->
                indexWrapper(chunk[i], collector, strings, types, literals, classesWithStrings)
            }
        }

        stringMap = strings
        typeMap = types
        literalMap = literals
        allClassesWithStrings = classesWithStrings
        return strings
    }

    private fun indexWrapper(
        wrapper: ClassDefWrapper,
        collector: ClassIndexCollector,
        strings: MutableMap<String, MutableList<ClassDefWrapper>>,
        types: MutableMap<Int, MutableList<ClassDefWrapper>>?,
        literals: MutableMap<Long, MutableList<ClassDefWrapper>>?,
        classesWithStrings: MutableList<ClassDefWrapper>,
    ) {
        if (collector.strings.isNotEmpty()) {
            collector.strings.forEach { stringLiteral ->
                strings.getOrPut(stringLiteral) { ArrayList(1) } += wrapper
            }
            classesWithStrings += wrapper
        }
        if (collector.referencedTypeHashes.isEmpty()) {
            wrapper.referencedTypeHashes = EMPTY_TYPE_HASHES
        } else {
            types?.let { map ->
                collector.referencedTypeHashes.forEach { hash ->
                    map.getOrPut(hash) { ArrayList(1) } += wrapper
                }
            }
            wrapper.referencedTypeHashes = collector.referencedTypeHashes.sorted().toIntArray()
        }
        if (collector.literalValues.isEmpty()) {
            wrapper.literalValues = EMPTY_LITERAL_VALUES
        } else {
            literals?.let { map ->
                collector.literalValues.forEach { literal ->
                    map.getOrPut(literal) { ArrayList(1) } += wrapper
                }
            }
            wrapper.literalValues = collector.literalValues.sorted().toLongArray()
        }
    }

    internal fun getClassesFromOpcodeStringLiteral(stringLiteral: String): List<ClassDefWrapper>? {
        return getClassesByReferenceMap()[stringLiteral]
    }

    internal fun getAllClassesWithStrings(): List<ClassDefWrapper> {
        getClassesByReferenceMap() // Load string map if needed.
        return allClassesWithStrings!!
    }

    internal fun getClassesReferencingType(type: String): List<ClassDefWrapper>? {
        getClassesByReferenceMap() // Load reference map if needed.
        val typeHash = type.hashCode()
        val indexed = typeMap?.get(typeHash)
        if (mutableWrappers.isEmpty()) {
            return indexed
        }
        val result = LinkedHashSet<ClassDefWrapper>()
        indexed?.let { result.addAll(it) }
        result.addAll(mutableWrappers)
        return result.toList()
    }

    internal fun getClassesContainingLiteral(literal: Long): List<ClassDefWrapper>? {
        getClassesByReferenceMap() // Load reference map if needed.
        val indexed = literalMap?.get(literal)
        if (mutableWrappers.isEmpty()) {
            return indexed
        }
        val result = LinkedHashSet<ClassDefWrapper>()
        indexed?.let { result.addAll(it) }
        result.addAll(mutableWrappers)
        return result.toList()
    }

    /**
     * Iterate over all classes.
     */
    fun forEach(action: (ClassDef) -> Unit) {
        classMap.values.forEach { wrapper ->
            action(wrapper.classDef)
        }
    }

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassBy
     */
    fun classByOrNull(classType: String) = classMap[classType]?.classDef

    private fun mapWrapperByOrNull(predicate: (ClassDef) -> Boolean) =
        classMap.values.find { wrapper ->
            predicate(wrapper.classDef)
        }

    /**
     * Find a class with a predicate. If you know the class type name,
     * it is highly preferred to instead use [classByOrNull(String)].
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type, or null if not found.
     */
    fun classByOrNull(predicate: (ClassDef) -> Boolean) = mapWrapperByOrNull(predicate)?.classDef

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     */
    fun classBy(predicate: (ClassDef) -> Boolean) = classByOrNull(predicate)
        ?: throw PatchException("Could not find any class match")

    private companion object {
        private const val INDEX_CHUNK_SIZE = 4096
        private val EMPTY_TYPE_HASHES = IntArray(0)
        private val EMPTY_LITERAL_VALUES = LongArray(0)
    }

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassBy
     */
    fun classBy(classType: String) = classByOrNull(classType)
        ?: throw PatchException("Could not find class: $classType")

    /**
     * Mutable class from a full class name.
     * Returns `null` if class is not available, such as a built-in Android or Java library.
     *
     * @param classDefType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassByOrNull(classDefType: String): MutableClass? {
        val wrapper = classMap[classDefType] ?: return null
        return wrapper.getMutableClass()
    }

    /**
     * Find a class with a predicate.
     *
     * @param classDefType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassBy(classDefType: String) = mutableClassByOrNull(classDefType)
        ?: throw PatchException("Could not find class: $classDefType")

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassByOrNull(predicate: (ClassDef) -> Boolean) =
        mapWrapperByOrNull(predicate)?.getMutableClass()

    /**
     * @param classDef An immutable class.
     * @return A mutable version of the class definition.
     */
    fun mutableClassBy(classDef: ClassDef): MutableClass =
        classDef as? MutableClass ?: mutableClassBy(classDef.type)

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassBy(predicate: (ClassDef) -> Boolean) = mutableClassByOrNull(predicate)
        ?: throw PatchException("Could not find any class match")
}
