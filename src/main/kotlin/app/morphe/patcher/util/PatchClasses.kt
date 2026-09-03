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
import java.util.LinkedList

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
    ) {
        /** Sorted hashes of class types referenced by instructions in this class. */
        var referencedTypeHashes: IntArray? = null

        /** Sorted literal values used by instructions in this class. */
        var literalValues: LongArray? = null

        fun getMutableClass(): MutableClass {
            if (classDef !is MutableClass) {
                classDef = MutableClass(classDef)
            }
            return classDef as MutableClass
        }
    }

    private data class ClassIndexValues(
        val strings: MutableSet<String> = HashSet(),
        val referencedTypeHashes: MutableSet<Int> = HashSet(),
        val literalValues: MutableSet<Long> = HashSet(),
    )

    /** Collect string, type-reference, and literal values in one traversal. */
    private fun ClassDef.findIndexValues(): ClassIndexValues {
        val values = ClassIndexValues()
        methods.forEach { method ->
            method.instructionsOrNull?.forEach { instruction ->
                if (instruction is WideLiteralInstruction) {
                    values.literalValues += instruction.wideLiteral
                }
                val reference = (instruction as? ReferenceInstruction)?.reference ?: return@forEach
                when (reference) {
                    is StringReference -> if (
                        instruction.opcode == Opcode.CONST_STRING ||
                        instruction.opcode == Opcode.CONST_STRING_JUMBO
                    ) {
                        values.strings += reference.string
                    }
                    is MethodReference -> values.referencedTypeHashes += reference.definingClass.hashCode()
                    is FieldReference -> values.referencedTypeHashes += reference.definingClass.hashCode()
                    is TypeReference -> values.referencedTypeHashes += reference.type.hashCode()
                }
            }
        }
        return values
    }

    /**
     * Opcode string constant -> List<ClassDefWrapper>
     */
    private var stringMap: Map<String, List<ClassDefWrapper>>? = null

    /**
     * All classes that contain at least 1 string.
     * Same contents as [stringMap] values except contains no duplicates.
     */
    private var allClassesWithStrings: List<ClassDefWrapper>? = null

    internal constructor(set: Set<ClassDef>) : this(set.map {
        ClassDefWrapper(it)
    }.associateByTo(
        // Must use linked hash map, otherwise with a regular map the ordering of classes found
        // in the apk is not preserved, and old fingerprints that have multiple matches can match
        // the wrong class due to hashmap random class iteration during matching. The issue is with
        // some fingerprint declarations not being unique enough and currently there is no way to
        // check for duplicate matches.
        // See https://github.com/ReVanced/revanced-patcher/issues/74
        //
        // Pre-size so rehashing doesn't occur and use a more performant load factor.
        LinkedHashMap(2 * set.size, 0.5f)
    ) { wrapper ->
        wrapper.classDef.type
    })

    internal fun close() {
        classMap.clear()
        closeStringMap()
    }

    internal fun closeStringMap() {
        stringMap = null
        allClassesWithStrings = null
        classMap.values.forEach { wrapper ->
            wrapper.referencedTypeHashes = null
            wrapper.literalValues = null
        }
    }

    internal fun addClass(classDef: ClassDef) {
        classMap[classDef.type] = ClassDefWrapper(classDef)
    }

    internal fun getClassesByStringMap(): Map<String, List<ClassDefWrapper>> {
        if (stringMap != null) {
            return stringMap!!
        }

        return buildInstructionIndexes()
    }

    private fun buildInstructionIndexes(): Map<String, List<ClassDefWrapper>> {
        // Default 0.75f load factor works well and a lower value does not improve patching time.
        val strings = HashMap<String, MutableList<ClassDefWrapper>>()
        val classesWithStrings = mutableListOf<ClassDefWrapper>()

        classMap.values.forEach { wrapper ->
            val values = wrapper.classDef.findIndexValues()
            if (values.strings.isNotEmpty()) {
                values.strings.forEach { stringLiteral ->
                    strings.getOrPut(stringLiteral) { ArrayList(1) } += wrapper
                }
                classesWithStrings += wrapper
            }
            wrapper.referencedTypeHashes = if (values.referencedTypeHashes.isEmpty()) {
                EMPTY_TYPE_HASHES
            } else {
                values.referencedTypeHashes.sorted().toIntArray()
            }
            wrapper.literalValues = if (values.literalValues.isEmpty()) {
                EMPTY_LITERAL_VALUES
            } else {
                values.literalValues.sorted().toLongArray()
            }
        }

        stringMap = strings
        allClassesWithStrings = classesWithStrings
        return strings
    }

    internal fun getClassesFromOpcodeStringLiteral(stringLiteral: String): List<ClassDefWrapper>? {
        return getClassesByStringMap()[stringLiteral]
    }

    internal fun getAllClassesWithStrings(): List<ClassDefWrapper> {
        getClassesByStringMap() // Load string map if needed.
        return allClassesWithStrings!!
    }

    internal fun getClassesReferencingType(type: String): List<ClassDefWrapper>? {
        getClassesByStringMap() // Both instruction indexes are built in the same traversal.
        val typeHash = type.hashCode()
        return classMap.values.filter { wrapper ->
            val hashes = wrapper.referencedTypeHashes
            // Mutable and newly added classes may have changed since indexing.
            hashes == null || wrapper.classDef is MutableClass || hashes.binarySearch(typeHash) >= 0
        }.ifEmpty { null }
    }

    internal fun getClassesContainingLiteral(literal: Long): List<ClassDefWrapper>? {
        getClassesByStringMap() // All instruction indexes are built in the same traversal.
        return classMap.values.filter { wrapper ->
            val values = wrapper.literalValues
            // Mutable and newly added classes may have changed since indexing.
            values == null || wrapper.classDef is MutableClass || values.binarySearch(literal) >= 0
        }.ifEmpty { null }
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
     * Returns `null` if class is not available, such as a built in Android or Java library.
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
    fun mutableClassBy(classDef: ClassDef) =
        if (classDef is MutableClass) classDef else mutableClassBy(classDef.type)

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassBy(predicate: (ClassDef) -> Boolean) = mutableClassByOrNull(predicate)
        ?: throw PatchException("Could not find any class match")
}
