/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.dex.CachingDexBackedDexFile
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.ChangeStamps
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBuffer
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.dexbacked.instruction.DexBackedInstruction
import com.android.tools.smali.dexlib2.dexbacked.raw.FieldIdItem
import com.android.tools.smali.dexlib2.dexbacked.raw.MethodIdItem
import com.android.tools.smali.dexlib2.dexbacked.raw.ProtoIdItem
import com.android.tools.smali.dexlib2.dexbacked.raw.TypeListItem
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.logging.Logger
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
    private val logger = Logger.getLogger(PatchClasses::class.java.name)

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
        /**
         * Position of this class in the class map. Used to keep candidate classes in class map order.
         */
        internal var ordinal = 0

        /**
         * If this class was replaced in the class map by another class of the same type.
         */
        internal var replaced = false

        /**
         * Whether instructions in this class contain any string constants.
         */
        internal var hasStrings = false

        /**
         * The immutable class the instruction indexes were built from,
         * or null if the class was not indexed or was mutable when it was indexed.
         */
        internal var indexedClassDef: ClassDef? = null

        /**
         * The methods of a class that was mutable when it was indexed, or null for other classes.
         */
        internal var indexedMethods: Set<Method>? = null

        /**
         * The [ChangeStamps] stamp when a class that was mutable was indexed.
         */
        internal var indexedChangeStamp = 0L

        /**
         * The last result of [changedMethods], and the [MutableClass.methodsChangeStamp]
         * and number of methods it was found for.
         */
        private var changedMethodsCache: List<Method>? = null
        private var changedMethodsCacheStamp = 0L
        private var changedMethodsCacheSize = 0

        /**
         * The instruction values of the methods of [changedMethodsValuesFor].
         */
        private var changedMethodsValues: ClassIndexValues? = null
        private var changedMethodsValuesFor: List<Method>? = null

        /**
         * The methods of [classDef].
         */
        val methods: Iterable<Method>
            get() = classDef.methods

        /**
         * The methods of [classDef] that can have other instructions than when it was indexed,
         * such as added methods and methods with changed instructions.
         */
        internal fun changedMethods(): List<Method> {
            val classDefLocal = classDef
            if (classDefLocal !is MutableClass) {
                return if (classDefLocal === indexedClassDef) emptyList() else methods.toList()
            }

            val indexedClassDefLocal = indexedClassDef
            if (indexedClassDefLocal is DexBackedClassDef && !classDefLocal.areMethodsCreated) return emptyList()

            // The methods are checked again only after they changed. Adding methods or changing their
            // instructions changes the stamp, and removing methods changes their number.
            val stamp = classDefLocal.methodsChangeStamp
            val size = classDefLocal.methods.size
            changedMethodsCache?.let { cached ->
                if (changedMethodsCacheStamp == stamp && changedMethodsCacheSize == size) return cached
            }

            val indexedMethodsLocal = indexedMethods
            val changedMethods = when {
                indexedClassDefLocal is DexBackedClassDef -> classDefLocal.methods.filter { method ->
                    method.instructionsChangeStamp != 0L ||
                            (method.sourceMethod as? DexBackedMethod)?.classDef !== indexedClassDefLocal
                }
                indexedMethodsLocal != null -> classDefLocal.methods.filter { method ->
                    method !in indexedMethodsLocal || method.instructionsChangeStamp > indexedChangeStamp
                }
                else -> classDefLocal.methods.toList()
            }

            changedMethodsCache = changedMethods
            changedMethodsCacheStamp = stamp
            changedMethodsCacheSize = size
            return changedMethods
        }

        /**
         * The instruction values of [changedMethods]. A class that the indexes show cannot satisfy a
         * condition can satisfy it only with its changed methods.
         */
        internal fun changedMethodsIndexValues(): ClassIndexValues {
            val changedMethods = changedMethods()
            val cachedValues = changedMethodsValues
            if (cachedValues != null && changedMethodsValuesFor === changedMethods) return cachedValues

            return collectIndexValues(classDef, changedMethods).also { values ->
                changedMethodsValues = values
                changedMethodsValuesFor = changedMethods
            }
        }

        /**
         * Sets what the instruction indexes were built from.
         */
        internal fun setIndexed(values: ClassIndexValues) {
            hasStrings = values.strings.isNotEmpty()
            indexedClassDef = values.classDef.takeUnless { it is MutableClass }
            indexedMethods = values.mutableMethods
            indexedChangeStamp = values.changeStamp
            changedMethodsCache = null
            changedMethodsValues = null
            changedMethodsValuesFor = null
        }

        fun getMutableClass(): MutableClass {
            if (classDef !is MutableClass) {
                classDef = MutableClass(classDef)
                onMakeMutable?.invoke(this)
            }
            return classDef as MutableClass
        }
    }

    private var nextOrdinal = 0

    /**
     * If a class was replaced in the class map. The indexes keep replaced classes,
     * so lists of the indexes are filtered once a class is replaced.
     */
    private var hasReplacedClasses = false

    private val mutableWrappers = LinkedHashSet<ClassDefWrapper>()

    private val onWrapperMadeMutable: (ClassDefWrapper) -> Unit = { wrapper ->
        mutableWrappers.add(wrapper)
    }

    init {
        classMap.values.forEach { wrapper ->
            wrapper.onMakeMutable = onWrapperMadeMutable
            wrapper.ordinal = nextOrdinal++
            if (wrapper.classDef is MutableClass) {
                mutableWrappers.add(wrapper)
            }
        }
    }

    /**
     * Reads the references of instructions of a dex file using the indexes of the references,
     * without creating reference objects. A [app.morphe.patcher.dex.CachingDexBackedDexFile]
     * decodes each string only once.
     */
    private class DexReferenceDecoder(val dexFile: DexBackedDexFile) {
        private val buffer = dexFile.buffer
        val dataBuffer = dexFile.dataBuffer
        private val stringSection = dexFile.stringSection
        private val typeSection = dexFile.typeSection
        private val fieldSection = dexFile.fieldSection
        private val methodSection = dexFile.methodSection
        private val protoSection = dexFile.protoSection

        fun string(stringIndex: Int): String = stringSection[stringIndex]

        fun type(typeIndex: Int) = string(buffer.readSmallUint(typeSection.getOffset(typeIndex)))

        fun fieldDefiningClass(fieldIndex: Int) =
            type(buffer.readUshort(fieldSection.getOffset(fieldIndex) + FieldIdItem.CLASS_OFFSET))

        fun fieldName(fieldIndex: Int) =
            string(buffer.readSmallUint(fieldSection.getOffset(fieldIndex) + FieldIdItem.NAME_OFFSET))

        fun fieldType(fieldIndex: Int) =
            type(buffer.readUshort(fieldSection.getOffset(fieldIndex) + FieldIdItem.TYPE_OFFSET))

        fun methodDefiningClass(methodIndex: Int) =
            type(buffer.readUshort(methodSection.getOffset(methodIndex) + MethodIdItem.CLASS_OFFSET))

        fun methodName(methodIndex: Int) =
            string(buffer.readSmallUint(methodSection.getOffset(methodIndex) + MethodIdItem.NAME_OFFSET))

        /**
         * Calls [action] with the return type and each parameter type of the method.
         */
        inline fun forEachMethodSignatureType(methodIndex: Int, action: (String) -> Unit) {
            val protoIndex = buffer.readUshort(methodSection.getOffset(methodIndex) + MethodIdItem.PROTO_OFFSET)
            val protoOffset = protoSection.getOffset(protoIndex)
            action(type(buffer.readSmallUint(protoOffset + ProtoIdItem.RETURN_TYPE_OFFSET)))

            val parametersOffset = buffer.readSmallUint(protoOffset + ProtoIdItem.PARAMETERS_OFFSET)
            if (parametersOffset == 0) return
            val parameterCount = dataBuffer.readSmallUint(parametersOffset + TypeListItem.SIZE_OFFSET)
            val parametersStart = parametersOffset + TypeListItem.LIST_OFFSET
            for (i in 0 until parameterCount) {
                action(type(dataBuffer.readUshort(parametersStart + 2 * i)))
            }
        }
    }

    /**
     * Classes by the hashes of indexed values. Unlike a [HashMap], the hashes are not boxed.
     * Different values can have the same hash, so the classes of a hash can be false positives.
     */
    private class HashIndex(initialCapacity: Int) {
        private var hashes = IntArray(initialCapacity)
        private var classes = arrayOfNulls<Any>(initialCapacity)
        private var size = 0

        private fun mixHash(hash: Int, mask: Int): Int =
            (hash * -0x61c88647).let { it xor (it ushr 16) } and mask

        private fun slotOf(hash: Int): Int {
            val mask = hashes.size - 1
            var slot = mixHash(hash, mask)
            while (true) {
                val slotClasses = classes[slot]
                if (slotClasses == null || hashes[slot] == hash) return slot
                slot = (slot + 1) and mask
            }
        }

        operator fun get(hash: Int): List<ClassDefWrapper>? {
            val entry = classes[slotOf(hash)] ?: return null
            return if (entry is ClassDefWrapper) {
                Collections.singletonList(entry)
            } else {
                @Suppress("UNCHECKED_CAST")
                entry as List<ClassDefWrapper>
            }
        }

        /**
         * Adds [wrapper] to the classes of each hash.
         *
         * @param sortedHashes The hashes of the class, in order. Can have duplicates.
         */
        fun addAll(sortedHashes: IntArray, wrapper: ClassDefWrapper) {
            for (i in sortedHashes.indices) {
                val hash = sortedHashes[i]
                if (i == 0 || sortedHashes[i - 1] != hash) add(hash, wrapper)
            }
        }

        /**
         * Adds [wrapper] to the classes of [hash], unless it was already added.
         *
         * Classes are indexed one at a time, so a class that already holds the hash
         * is always the last class of the list.
         */
        private fun add(hash: Int, wrapper: ClassDefWrapper) {
            val slot = slotOf(hash)
            val slotClasses = classes[slot]
            if (slotClasses != null) {
                if (slotClasses is ClassDefWrapper) {
                    if (slotClasses !== wrapper) {
                        val list = ArrayList<ClassDefWrapper>(4)
                        list.add(slotClasses)
                        list.add(wrapper)
                        classes[slot] = list
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val list = slotClasses as ArrayList<ClassDefWrapper>
                    if (list[list.lastIndex] !== wrapper) {
                        list.add(wrapper)
                    }
                }
                return
            }

            hashes[slot] = hash
            classes[slot] = wrapper
            // Keep at most half of the slots in use.
            if (++size * 2 > hashes.size) grow()
        }

        private fun grow() {
            val oldHashes = hashes
            val oldClasses = classes
            val newCapacity = oldHashes.size * 2
            val mask = newCapacity - 1
            val newHashes = IntArray(newCapacity)
            val newClasses = arrayOfNulls<Any>(newCapacity)

            for (i in oldHashes.indices) {
                val oldSlotClasses = oldClasses[i] ?: continue
                val hash = oldHashes[i]
                var slot = mixHash(hash, mask)
                while (newClasses[slot] != null) {
                    slot = (slot + 1) and mask
                }
                newHashes[slot] = hash
                newClasses[slot] = oldSlotClasses
            }

            hashes = newHashes
            classes = newClasses
        }
    }

    /**
     * Growable list of ints, to collect hashes without boxing them.
     */
    private class IntList {
        private var values = IntArray(16)
        private var size = 0

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun clear() {
            size = 0
        }

        fun toSortedArray() = if (size == 0) EMPTY_INT_ARRAY else values.copyOf(size).apply { sort() }
    }

    /**
     * The values of the instructions of a class to index.
     *
     * @param classDef The class the values were collected from.
     * @param strings String constants, can have duplicates.
     * @param typeHashes Sorted hashes of referenced types, can have duplicates. See [getIndexedClassesReferencingType].
     * @param memberNameHashes Sorted hashes of referenced field and method names, can have duplicates.
     * @param literalHashes Sorted hashes of literals, can have duplicates.
     * @param mutableMethods The methods of a mutable class, or null if the class is immutable.
     * @param changeStamp The [ChangeStamps] stamp before the values of a mutable class were collected.
     */
    internal class ClassIndexValues(
        val classDef: ClassDef,
        val strings: List<String>,
        val typeHashes: IntArray,
        val memberNameHashes: IntArray,
        val literalHashes: IntArray,
        val mutableMethods: Set<Method>?,
        val changeStamp: Long,
    ) {
        fun hasType(type: String) = typeHashes.binarySearch(type.hashCode()) >= 0

        fun hasMemberName(name: String) = memberNameHashes.binarySearch(name.hashCode()) >= 0

        fun hasLiteral(literal: Long) = literalHashes.binarySearch(literal.hashCode()) >= 0
    }

    /**
     * Collects the values of the instructions of a class to index.
     */
    private class ClassIndexCollector {
        val strings = ArrayList<String>()
        val typeHashes = IntList()
        val memberNameHashes = IntList()
        val literalHashes = IntList()

        fun clear() {
            strings.clear()
            typeHashes.clear()
            memberNameHashes.clear()
            literalHashes.clear()
        }

        /**
         * Collects a type of a field or method signature. Primitive types are in most classes
         * and are not indexed.
         */
        fun addSignatureType(type: String) {
            if (type.length > 1) typeHashes.add(type.hashCode())
        }

        /**
         * Collects the literals and references of the instructions of a dex backed method,
         * without creating the instructions and references.
         */
        fun addDexBackedInstructions(
            implementation: CachingDexBackedDexFile.MethodImplementation,
            decoder: DexReferenceDecoder,
        ) {
            val dexFile = implementation.dexFile
            val opcodes = dexFile.opcodes
            val buffer = decoder.dataBuffer
            var offset = implementation.instructionsStart
            val end = offset + implementation.instructionsSize * 2

            // Reads the instructions as DexBackedMethodImplementation and DexBackedInstruction do.
            while (offset < end) {
                var opcodeValue = buffer.readUbyte(offset)
                if (opcodeValue == 0) opcodeValue = buffer.readUshort(offset)
                val opcode = opcodes.getOpcodeByValue(opcodeValue)

                val codeUnits = if (opcode == null || opcode.format.isPayloadFormat) {
                    DexBackedInstruction.getCodeUnits(dexFile, offset)
                } else {
                    addDexBackedLiteral(opcode, offset, buffer)
                    if (!addDexBackedReference(opcode, offset, decoder)) {
                        addReference(DexBackedInstruction.readFrom(dexFile, buffer.readerAt(offset)))
                    }
                    opcode.format.size / 2
                }

                offset += codeUnits * 2
            }
        }

        /**
         * Collects the literal of a dex backed instruction, which is any instruction of a format
         * of [WideLiteralInstruction], decoded as the dex backed instruction of the format does.
         */
        private fun addDexBackedLiteral(opcode: Opcode, instructionStart: Int, buffer: DexBuffer) {
            val literal = when (opcode.format) {
                Format.Format11n -> ((buffer.readByte(instructionStart + 1) shl 24) shr 28).toLong()
                Format.Format21ih -> (buffer.readShort(instructionStart + 2) shl 16).toLong()
                Format.Format21lh -> buffer.readShort(instructionStart + 2).toLong() shl 48
                Format.Format21s, Format.Format22s -> buffer.readShort(instructionStart + 2).toLong()
                Format.Format22b -> buffer.readByte(instructionStart + 3).toLong()
                Format.Format31i -> buffer.readInt(instructionStart + 2).toLong()
                Format.Format51l -> buffer.readLong(instructionStart + 2)
                else -> return
            }
            literalHashes.add(literal.hashCode())
        }

        /**
         * Collects the reference of a dex backed instruction without creating the reference.
         *
         * @return False if the format of the instruction is not supported.
         */
        private fun addDexBackedReference(opcode: Opcode, instructionStart: Int, decoder: DexReferenceDecoder): Boolean {
            val referenceType = opcode.referenceType
            if (referenceType == ReferenceType.NONE) return true

            // All reference formats store the reference index after the opcode.
            val referenceIndexOffset = instructionStart + 2
            val referenceIndex = when (opcode.format) {
                Format.Format21c, Format.Format22c, Format.Format35c, Format.Format3rc,
                Format.Format45cc, Format.Format4rcc -> decoder.dataBuffer.readUshort(referenceIndexOffset)
                Format.Format31c -> decoder.dataBuffer.readSmallUint(referenceIndexOffset)
                else -> return false
            }

            when (referenceType) {
                ReferenceType.STRING -> if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
                    strings += decoder.string(referenceIndex)
                }
                ReferenceType.TYPE -> typeHashes.add(decoder.type(referenceIndex).hashCode())
                ReferenceType.FIELD -> {
                    typeHashes.add(decoder.fieldDefiningClass(referenceIndex).hashCode())
                    memberNameHashes.add(decoder.fieldName(referenceIndex).hashCode())
                    addSignatureType(decoder.fieldType(referenceIndex))
                }
                ReferenceType.METHOD -> {
                    typeHashes.add(decoder.methodDefiningClass(referenceIndex).hashCode())
                    memberNameHashes.add(decoder.methodName(referenceIndex).hashCode())
                    decoder.forEachMethodSignatureType(referenceIndex, ::addSignatureType)
                }
            }
            return true
        }

        /**
         * Collects the reference of any instruction.
         */
        fun addReference(instruction: Instruction) {
            when (val reference = (instruction as? ReferenceInstruction)?.reference ?: return) {
                is StringReference -> if (
                    instruction.opcode == Opcode.CONST_STRING ||
                    instruction.opcode == Opcode.CONST_STRING_JUMBO
                ) {
                    strings += reference.string
                }
                is MethodReference -> {
                    typeHashes.add(reference.definingClass.hashCode())
                    memberNameHashes.add(reference.name.hashCode())
                    addSignatureType(reference.returnType)
                    reference.parameterTypes.forEach { addSignatureType(it.toString()) }
                }
                is FieldReference -> {
                    typeHashes.add(reference.definingClass.hashCode())
                    memberNameHashes.add(reference.name.hashCode())
                    addSignatureType(reference.type)
                }
                is TypeReference -> typeHashes.add(reference.type.hashCode())
            }
        }
    }

    /**
     * Collects the index values of all methods of [ClassDefWrapper.classDef].
     */
    private fun ClassDefWrapper.collectIndexValues(): ClassIndexValues {
        val classDef = classDef
        val changeStamp = ChangeStamps.current
        // Mutable methods are equal by signature, so they are kept by identity.
        val mutableMethods = (classDef as? MutableClass)?.methods?.toCollection(
            Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())
        )

        return collectIndexValues(classDef, methods, mutableMethods, changeStamp)
    }

    /**
     * The indexes of the instruction values.
     */
    private class IndexBuilder(classCount: Int) {
        private val initialCapacity = initialHashIndexCapacity(classCount)

        // Default 0.75f load factor works well and a lower value does not improve patching time.
        val strings = HashMap<String, List<ClassDefWrapper>>()
        val types = HashIndex(initialCapacity)
        val memberNames = HashIndex(initialCapacity)
        val literals = HashIndex(initialCapacity)

        /**
         * Adds the values of a class to the indexes. Classes are added in class map order.
         */
        fun add(wrapper: ClassDefWrapper, values: ClassIndexValues) {
            wrapper.setIndexed(values)

            if (values.strings.isNotEmpty()) {
                values.strings.forEach { string ->
                    // A class that already holds the string is always the last class of the list.
                    when (val existing = strings[string]) {
                        null -> strings[string] = Collections.singletonList(wrapper)
                        is ArrayList<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val list = existing as ArrayList<ClassDefWrapper>
                            if (list[list.lastIndex] !== wrapper) {
                                list.add(wrapper)
                            }
                        }
                        else -> {
                            val first = existing[0]
                            if (first !== wrapper) {
                                strings[string] = ArrayList<ClassDefWrapper>(4).apply {
                                    add(first)
                                    add(wrapper)
                                }
                            }
                        }
                    }
                }
            }
            types.addAll(values.typeHashes, wrapper)
            memberNames.addAll(values.memberNameHashes, wrapper)
            literals.addAll(values.literalHashes, wrapper)
        }
    }

    /**
     * Opcode string constant -> List<ClassDefWrapper>
     */
    private var stringMap: Map<String, List<ClassDefWrapper>>? = null

    /**
     * Instructions indexes, or null if not yet built.
     */
    private var indexBuilder: IndexBuilder? = null

    /**
     * Total time spent building the instruction indexes.
     */
    internal var indexBuildNanos = 0L
        private set

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
            wrapper.ordinal = nextOrdinal++
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
        indexBuilder = null
        mutableWrappers.clear()
        classMap.values.forEach { wrapper ->
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

        val replacedWrapper = classMap.put(classDef.type, wrapper)
        if (replacedWrapper == null) {
            wrapper.ordinal = nextOrdinal++
        } else {
            // The class keeps its position in the class map.
            wrapper.ordinal = replacedWrapper.ordinal
            replacedWrapper.replaced = true
            hasReplacedClasses = true
            mutableWrappers.remove(replacedWrapper)
        }

        // Classes are added while patches execute (extension merges), which can happen after the
        // instruction indexes were built. Index the new class incrementally, otherwise string
        // lookups and candidate scans never see it.
        indexBuilder?.add(wrapper, wrapper.collectIndexValues())
    }

    internal fun getClassesByReferenceMap(): Map<String, List<ClassDefWrapper>> {
        if (stringMap != null) {
            return stringMap!!
        }

        return buildInstructionIndexes()
    }

    private fun buildInstructionIndexes(): Map<String, List<ClassDefWrapper>> {
        val startNanos = System.nanoTime()
        logger.info("Building instruction indexes for ${classMap.size} classes...")
        val builder = IndexBuilder(classMap.size)

        // Scanning the instructions is the costly part and reads each class on its own, so it runs
        // in parallel, a chunk at a time to bound memory, while the indexes are filled in class order.
        classMap.values.chunked(INDEX_CHUNK_SIZE).forEach { chunk ->
            chunk.parallelStream().map { wrapper -> wrapper.collectIndexValues() }.collect(Collectors.toList())
                .forEachIndexed { i, values -> builder.add(chunk[i], values) }
        }

        stringMap = builder.strings
        indexBuilder = builder
        indexBuildNanos += System.nanoTime() - startNanos
        return builder.strings
    }

    private fun getIndexBuilder(): IndexBuilder {
        getClassesByReferenceMap() // Load reference map if needed.
        return indexBuilder!!
    }

    /**
     * Classes with the string, without classes that were replaced in the class map.
     */
    internal fun getClassesFromOpcodeStringLiteral(stringLiteral: String): List<ClassDefWrapper>? {
        return getClassesByReferenceMap()[stringLiteral]?.withoutReplacedClasses()?.ifEmpty { null }
    }

    /**
     * Classes with strings, without classes that were replaced in the class map.
     */
    internal fun getAllClassesWithStrings(): List<ClassDefWrapper> {
        getClassesByReferenceMap() // Load string map if needed.
        return classMap.values.filter { it.hasStrings }
    }

    /**
     * Classes of an index list that were not replaced in the class map.
     */
    internal fun List<ClassDefWrapper>.withoutReplacedClasses(): List<ClassDefWrapper> =
        if (hasReplacedClasses) filterNot { it.replaced } else this

    /**
     * Indexed classes with instructions that reference the type: as the class of a field or method,
     * as the type of a field, as a return or parameter type of a method, or as a type reference
     * such as `new-instance` and `check-cast`. Primitive types of fields and methods are not indexed.
     * Can contain false positives. Does not include changes of mutable classes, see [candidateClasses].
     */
    internal fun getIndexedClassesReferencingType(type: String): List<ClassDefWrapper> =
        getIndexBuilder().types[type.hashCode()].orEmpty()

    /**
     * Indexed classes with instructions that reference a field or method with the name.
     * Can contain false positives. Does not include changes of mutable classes, see [candidateClasses].
     */
    internal fun getIndexedClassesReferencingMemberName(name: String): List<ClassDefWrapper> =
        getIndexBuilder().memberNames[name.hashCode()].orEmpty()

    /**
     * Indexed classes with instructions that use the literal.
     * Does not include changes of mutable classes, see [candidateClasses].
     */
    internal fun getIndexedClassesContainingLiteral(literal: Long): List<ClassDefWrapper> =
        getIndexBuilder().literals[literal.hashCode()].orEmpty()

    /**
     * Classes that can satisfy conditions of the indexed instruction values.
     *
     * @param classes The candidate classes in class map order.
     * @param changedMethods Changed methods of candidate mutable classes that cannot satisfy the
     *                       conditions with the indexed instructions. Only the changed methods of
     *                       these classes can satisfy the conditions.
     */
    internal class Candidates(
        val classes: List<ClassDefWrapper>,
        private val changedMethods: Map<ClassDefWrapper, List<Method>>,
    ) {
        private val classSet by lazy { classes.toHashSet() }

        operator fun contains(wrapper: ClassDefWrapper) = classSet.contains(wrapper)

        /**
         * The methods of a candidate class that can satisfy the conditions.
         */
        fun methodsOf(wrapper: ClassDefWrapper): Iterable<Method> = changedMethods[wrapper] ?: wrapper.methods
    }

    /**
     * A condition of the instruction values that a class must satisfy.
     *
     * @param indexedClasses The indexed classes that can satisfy the condition.
     * @param isSatisfiedBy If the instruction values of some methods can satisfy the condition.
     *                      Like the indexed classes, it can be true for values that do not.
     */
    internal class IndexCondition(
        val indexedClasses: List<ClassDefWrapper>,
        val isSatisfiedBy: (ClassIndexValues) -> Boolean,
    )

    /**
     * Classes that can satisfy all the conditions, in class map order.
     *
     * @param indexedClasses Indexed classes of each condition.
     *                       A class must be in all lists to satisfy all conditions.
     * @return The indexed classes in all lists, and the mutable classes with
     *         changed methods because their contents can differ from the indexes.
     */
    internal fun candidateClasses(indexedClasses: List<List<ClassDefWrapper>>): Candidates =
        candidateConditionClasses(indexedClasses.map { classes -> IndexCondition(classes) { true } })

    /**
     * Classes that can satisfy all the conditions, in class map order.
     *
     * @return The indexed classes that can satisfy all conditions, and the mutable classes
     *         with changed methods that can satisfy all conditions.
     */
    internal fun candidateConditionClasses(conditions: List<IndexCondition>): Candidates {
        val result = ArrayList<ClassDefWrapper>(conditions.minOf { it.indexedClasses.size } + mutableWrappers.size)
        addIndexedClassesInAll(conditions.map { it.indexedClasses }, result)

        var changedMethods = emptyMap<ClassDefWrapper, List<Method>>()
        if (mutableWrappers.isNotEmpty()) {
            val indexed = HashSet(result)
            changedMethods = HashMap()
            mutableWrappers.forEach { wrapper ->
                if (indexed.contains(wrapper)) return@forEach

                val methods = wrapper.changedMethods()
                if (methods.isEmpty()) return@forEach

                // The indexes show the class cannot satisfy a condition with its other methods,
                // so only the changed methods can satisfy all conditions.
                val values = wrapper.changedMethodsIndexValues()
                if (conditions.all { it.isSatisfiedBy(values) }) {
                    result += wrapper
                    changedMethods[wrapper] = methods
                }
            }
        }

        // Lists are in class map order, except for replaced classes and mutable classes.
        for (i in 1 until result.size) {
            if (result[i - 1].ordinal > result[i].ordinal) {
                result.sortBy { it.ordinal }
                break
            }
        }

        return Candidates(result, changedMethods)
    }

    /**
     * Indexed classes that can satisfy the conditions of at least one alternative.
     *
     * @param alternatives Indexed classes of each condition of each alternative.
     *                     A class must be in all lists of an alternative to satisfy the alternative.
     * @return The indexed classes in all lists of any alternative, without duplicates
     *         and not in class map order.
     */
    internal fun indexedClassesOfAny(alternatives: List<List<List<ClassDefWrapper>>>): List<ClassDefWrapper> {
        val result = LinkedHashSet<ClassDefWrapper>()
        alternatives.forEach { indexedClasses -> addIndexedClassesInAll(indexedClasses, result) }
        return ArrayList(result)
    }

    /**
     * Adds the indexed classes that are in all lists to [result], in the order of the smallest list.
     *
     * Classes of a much larger list are left for the matching to reject, as finding them in a
     * large list costs more than the matching. So [result] can get classes not in every list.
     */
    private fun addIndexedClassesInAll(
        indexedClasses: List<List<ClassDefWrapper>>,
        result: MutableCollection<ClassDefWrapper>,
    ) {
        val smallest = indexedClasses.minBy { it.size }
        if (smallest.isEmpty()) return

        val maximumSize = CANDIDATE_INTERSECTION_SIZE_FACTOR * smallest.size
        val others = indexedClasses.mapNotNull { classes ->
            if (classes === smallest || classes.size > maximumSize) null else HashSet(classes)
        }

        smallest.filterTo(result) { wrapper ->
            !wrapper.replaced && others.all { it.contains(wrapper) }
        }
    }

    internal fun getClassesReferencingType(type: String): List<ClassDefWrapper>? =
        candidateClasses(listOf(getIndexedClassesReferencingType(type))).classes.ifEmpty { null }

    internal fun getClassesContainingLiteral(literal: Long): List<ClassDefWrapper>? =
        candidateClasses(listOf(getIndexedClassesContainingLiteral(literal))).classes.ifEmpty { null }

    private companion object {
        /**
         * Lists of candidates larger than this factor of the smallest list are not intersected.
         */
        private const val CANDIDATE_INTERSECTION_SIZE_FACTOR = 16

        /**
         * Must be powers of 2.
         */
        private const val MIN_HASH_INDEX_CAPACITY = 16
        private const val MAX_INITIAL_HASH_INDEX_CAPACITY = 1 shl 19

        private fun initialHashIndexCapacity(classCount: Int): Int {
            if (classCount <= 4) return MIN_HASH_INDEX_CAPACITY
            return (Integer.highestOneBit(classCount - 1) shl 2)
                .coerceIn(MIN_HASH_INDEX_CAPACITY, MAX_INITIAL_HASH_INDEX_CAPACITY)
        }

        private const val INDEX_CHUNK_SIZE = 4096

        private val EMPTY_INT_ARRAY = IntArray(0)

        private val threadLocalCollector = ThreadLocal.withInitial { ClassIndexCollector() }

        /**
         * Collects the string, type-reference, member-name-reference,
         * and literal values of the instructions of [methods] of [classDef] in one traversal.
         *
         * Only reads the class, so classes can be collected in parallel.
         *
         * @param mutableMethods See [ClassIndexValues.mutableMethods].
         * @param changeStamp See [ClassIndexValues.changeStamp].
         */
        fun collectIndexValues(
            classDef: ClassDef,
            methods: Iterable<Method>,
            mutableMethods: Set<Method>? = null,
            changeStamp: Long = 0,
        ): ClassIndexValues {
            val decoder = (classDef as? DexBackedClassDef)?.let { DexReferenceDecoder(it.dexFile) }
            val collector = threadLocalCollector.get()
            collector.clear()

            methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                if (decoder != null && implementation is CachingDexBackedDexFile.MethodImplementation &&
                    implementation.dexFile === decoder.dexFile
                ) {
                    collector.addDexBackedInstructions(implementation, decoder)
                    return@forEach
                }

                implementation.instructions.forEach { instruction ->
                    if (instruction is WideLiteralInstruction) {
                        collector.literalHashes.add(instruction.wideLiteral.hashCode())
                    }
                    collector.addReference(instruction)
                }
            }

            val collectedStrings = if (collector.strings.isEmpty()) emptyList() else ArrayList(collector.strings)

            return ClassIndexValues(
                classDef,
                collectedStrings,
                collector.typeHashes.toSortedArray(),
                collector.memberNameHashes.toSortedArray(),
                collector.literalHashes.toSortedArray(),
                mutableMethods,
                changeStamp,
            )
        }
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
