/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.PatcherProgress
import app.morphe.patcher.PatcherTask
import app.morphe.patcher.dex.CachingDexBackedDexFile
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.runTask
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
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Collectors
import java.util.stream.IntStream

/**
 * All classes for the target app and any extension classes.
 */
internal class PatchClasses internal constructor(
    /**
     * Class type -> ClassDef.
     */
    internal val classMap: MutableMap<String, ClassDefWrapper>,
    /**
     * Receives how far building the instruction indexes got.
     */
    private val progress: PatcherProgress? = null,
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
         * Position of the declared methods of this class in [DeclaredMethodIndex], or -1 if they are not indexed.
         */
        internal var declaredMethodsSlot = -1

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
         * What is kept of a class that is or was mutable, which few classes are, or null for other classes.
         */
        private var mutableState: MutableState? = null

        private class MutableState {
            /**
             * The methods of a class that was mutable when it was indexed, or null for other classes.
             */
            var indexedMethods: Set<Method>? = null

            /**
             * The [ChangeStamps] stamp when a class that was mutable was indexed.
             */
            var indexedChangeStamp = 0L

            /**
             * The last result of [changedMethods], and the [MutableClass.methodsChangeStamp]
             * and number of methods it was found for.
             */
            var changedMethodsCache: List<Method>? = null
            var changedMethodsCacheStamp = 0L
            var changedMethodsCacheSize = 0

            /**
             * The instruction values of the methods of [changedMethodsValuesFor].
             */
            var changedMethodsValues: ClassIndexValues? = null
            var changedMethodsValuesFor: List<Method>? = null
        }

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
            val state = mutableState ?: MutableState().also { mutableState = it }
            state.changedMethodsCache?.let { cached ->
                if (state.changedMethodsCacheStamp == stamp && state.changedMethodsCacheSize == size) return cached
            }

            val indexedMethodsLocal = state.indexedMethods
            val changedMethods = when {
                indexedClassDefLocal is DexBackedClassDef -> classDefLocal.methods.filter { method ->
                    method.instructionsChangeStamp != 0L ||
                            (method.sourceMethod as? DexBackedMethod)?.classDef !== indexedClassDefLocal
                }
                indexedMethodsLocal != null -> classDefLocal.methods.filter { method ->
                    method !in indexedMethodsLocal || method.instructionsChangeStamp > state.indexedChangeStamp
                }
                else -> classDefLocal.methods.toList()
            }

            state.changedMethodsCache = changedMethods
            state.changedMethodsCacheStamp = stamp
            state.changedMethodsCacheSize = size
            return changedMethods
        }

        /**
         * The instruction values of [changedMethods]. A class that the indexes show cannot satisfy a
         * condition can satisfy it only with its changed methods.
         */
        internal fun changedMethodsIndexValues(): ClassIndexValues {
            val changedMethods = changedMethods()
            val state = mutableState
            val cachedValues = state?.changedMethodsValues
            if (cachedValues != null && state.changedMethodsValuesFor === changedMethods) return cachedValues

            return collectIndexValues(classDef, changedMethods).also { values ->
                state?.changedMethodsValues = values
                state?.changedMethodsValuesFor = changedMethods
            }
        }

        /**
         * Sets what the instruction indexes were built from.
         */
        internal fun setIndexed(values: ClassIndexValues) {
            hasStrings = values.strings.isNotEmpty()
            indexedClassDef = values.classDef.takeUnless { it is MutableClass }
            mutableState = values.mutableMethods?.let { methods ->
                MutableState().apply {
                    indexedMethods = methods
                    indexedChangeStamp = values.changeStamp
                }
            }
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
     * Reads the hashes of the references of instructions of a dex file using the indexes of the references,
     * without creating reference objects and without decoding the strings.
     */
    private class DexReferenceDecoder(val dexFile: CachingDexBackedDexFile) {
        private val buffer = dexFile.buffer
        val dataBuffer: DexBuffer = dexFile.dataBuffer
        private val stringSection = dexFile.stringSection
        private val typeSection = dexFile.typeSection
        private val fieldSection = dexFile.fieldSection
        private val methodSection = dexFile.methodSection
        private val protoSection = dexFile.protoSection

        fun string(stringIndex: Int): String = stringSection[stringIndex]

        fun typeHash(typeIndex: Int) = dexFile.stringHashCode(typeStringIndex(typeIndex))

        /**
         * If the type is a single character, which only primitive types and void are.
         */
        fun isPrimitive(typeIndex: Int) =
            dataBuffer.readUbyte(buffer.readSmallUint(stringSection.getOffset(typeStringIndex(typeIndex)))) == 1

        private fun typeStringIndex(typeIndex: Int) = buffer.readSmallUint(typeSection.getOffset(typeIndex))

        fun fieldDefiningClassIndex(fieldIndex: Int) =
            buffer.readUshort(fieldSection.getOffset(fieldIndex) + FieldIdItem.CLASS_OFFSET)

        fun fieldNameHash(fieldIndex: Int) =
            dexFile.stringHashCode(buffer.readSmallUint(fieldSection.getOffset(fieldIndex) + FieldIdItem.NAME_OFFSET))

        fun fieldTypeIndex(fieldIndex: Int) =
            buffer.readUshort(fieldSection.getOffset(fieldIndex) + FieldIdItem.TYPE_OFFSET)

        fun methodDefiningClassIndex(methodIndex: Int) =
            buffer.readUshort(methodSection.getOffset(methodIndex) + MethodIdItem.CLASS_OFFSET)

        fun methodNameHash(methodIndex: Int) =
            dexFile.stringHashCode(buffer.readSmallUint(methodSection.getOffset(methodIndex) + MethodIdItem.NAME_OFFSET))

        fun methodProtoIndex(methodIndex: Int) =
            buffer.readUshort(methodSection.getOffset(methodIndex) + MethodIdItem.PROTO_OFFSET)

        fun protoReturnTypeIndex(protoIndex: Int) =
            buffer.readSmallUint(protoSection.getOffset(protoIndex) + ProtoIdItem.RETURN_TYPE_OFFSET)

        /**
         * Calls [action] with the index of the type of each parameter.
         */
        inline fun forEachProtoParameterTypeIndex(protoIndex: Int, action: (Int) -> Unit) {
            val parametersOffset = buffer.readSmallUint(protoSection.getOffset(protoIndex) + ProtoIdItem.PARAMETERS_OFFSET)
            if (parametersOffset == 0) return
            val parameterCount = dataBuffer.readSmallUint(parametersOffset + TypeListItem.SIZE_OFFSET)
            val parametersStart = parametersOffset + TypeListItem.LIST_OFFSET
            for (i in 0 until parameterCount) {
                action(dataBuffer.readUshort(parametersStart + 2 * i))
            }
        }
    }

    /**
     * What a method of a class must declare, for [canDeclare].
     *
     * A method is summed up in an int: part of the hash of its name, part of the hash of its return type,
     * how many parameters it has, and a bit for the hash of the type of each parameter. Different methods
     * can have the same int, so a class can be a false positive.
     *
     * @param name Exact name, or null for any.
     * @param returnType Exact return type, or null for any.
     * @param parameterCount How many parameters, or null for any.
     * @param parameterTypes Exact types of some of the parameters.
     */
    internal class MethodDeclaration(
        name: String?,
        returnType: String?,
        parameterCount: Int?,
        parameterTypes: List<String>,
    ) {
        private val mask: Int
        private val value: Int

        init {
            var mask = 0
            var value = 0
            if (name != null) {
                mask = mask or (-1 shl NAME_SHIFT)
                value = value or nameBits(name.hashCode())
            }
            if (returnType != null) {
                mask = mask or (RETURN_TYPE_MASK shl RETURN_TYPE_SHIFT)
                value = value or returnTypeBits(returnType.hashCode())
            }
            if (parameterCount != null) {
                mask = mask or (PARAMETER_COUNT_MAX shl PARAMETER_COUNT_SHIFT)
                value = value or parameterCountBits(parameterCount)
            }
            parameterTypes.forEach { type ->
                val bit = parameterTypeBit(type.hashCode())
                mask = mask or bit
                value = value or bit
            }
            this.mask = mask
            this.value = value
        }

        /**
         * If every method can be the method.
         */
        val isAny get() = mask == 0

        fun canBe(method: Int) = (method and mask) == value

        companion object {
            private const val NAME_SHIFT = 20
            private const val RETURN_TYPE_SHIFT = 12
            private const val RETURN_TYPE_MASK = 0xff
            private const val PARAMETER_COUNT_SHIFT = 8
            private const val PARAMETER_COUNT_MAX = 0xf

            private fun mix(hash: Int) = (hash * -0x61c88647).let { it xor (it ushr 16) }

            private fun nameBits(hash: Int) = mix(hash) shl NAME_SHIFT

            private fun returnTypeBits(hash: Int) = (mix(hash) and RETURN_TYPE_MASK) shl RETURN_TYPE_SHIFT

            private fun parameterCountBits(count: Int) =
                minOf(count, PARAMETER_COUNT_MAX) shl PARAMETER_COUNT_SHIFT

            private fun parameterTypeBit(hash: Int) = 1 shl (mix(hash) and 7)

            /**
             * The int of a method without its parameters.
             */
            fun of(nameHash: Int, returnTypeHash: Int) = nameBits(nameHash) or returnTypeBits(returnTypeHash)

            /**
             * Adds the types of the parameters to the int of a method.
             */
            inline fun withParameters(
                method: Int,
                forEachParameterTypeHash: ((Int) -> Unit) -> Unit,
            ): Int {
                var count = 0
                var bits = method
                forEachParameterTypeHash { hash ->
                    count++
                    bits = bits or parameterTypeBit(hash)
                }
                return bits or parameterCountBits(count)
            }

            fun of(method: Method) = withParameters(of(method.name.hashCode(), method.returnType.hashCode())) { action ->
                method.parameterTypes.forEach { action(it.toString().hashCode()) }
            }
        }
    }

    /**
     * The methods each class declares, as the ints of [MethodDeclaration], in the order the classes were indexed.
     */
    private class DeclaredMethodIndex(classCount: Int) {
        private var methods = IntArray(maxOf(16, 4 * classCount))
        private var classes = arrayOfNulls<ClassDefWrapper>(maxOf(16, classCount))

        // Where the methods of each class end, which is where those of the next class start.
        private var ends = IntArray(classes.size)
        private var size = 0

        fun add(wrapper: ClassDefWrapper, classMethods: IntArray) {
            if (size == classes.size) {
                classes = classes.copyOf(size * 2)
                ends = ends.copyOf(size * 2)
            }
            val start = start(size)
            val end = start + classMethods.size
            if (end > methods.size) methods = methods.copyOf(maxOf(end, methods.size + methods.size / 2))
            classMethods.copyInto(methods, start)

            classes[size] = wrapper
            ends[size] = end
            wrapper.declaredMethodsSlot = size
            size++
        }

        fun trim() {
            if (size > 0) methods = methods.copyOf(ends[size - 1])
        }

        private fun start(slot: Int) = if (slot == 0) 0 else ends[slot - 1]

        fun canDeclare(wrapper: ClassDefWrapper, declaration: MethodDeclaration): Boolean {
            val slot = wrapper.declaredMethodsSlot
            for (i in start(slot) until ends[slot]) {
                if (declaration.canBe(methods[i])) return true
            }
            return false
        }

        fun addClassesDeclaring(declaration: MethodDeclaration, result: MutableList<ClassDefWrapper>) {
            var i = 0
            for (slot in 0 until size) {
                val end = ends[slot]
                while (i < end) {
                    if (declaration.canBe(methods[i])) {
                        result += classes[slot]!!
                        break
                    }
                    i++
                }
                i = end
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
            @Suppress("UNCHECKED_CAST")
            return when (val entry = classes[slotOf(hash)]) {
                null -> null
                is ClassDefWrapper -> Collections.singletonList(entry)
                is Array<*> -> (entry as Array<ClassDefWrapper>).asList()
                else -> entry as List<ClassDefWrapper>
            }
        }

        /**
         * Trims the lists of classes, which take a good part of the heap for a large app.
         */
        fun trim() {
            for (slot in classes.indices) {
                val entry = classes[slot]
                if (entry is ArrayList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    classes[slot] = (entry as ArrayList<ClassDefWrapper>).toTypedArray()
                }
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
                    val list = if (slotClasses is Array<*>) {
                        // Lists of which some get more classes after they were trimmed.
                        ArrayList((slotClasses as Array<ClassDefWrapper>).asList()).also { classes[slot] = it }
                    } else {
                        slotClasses as ArrayList<ClassDefWrapper>
                    }
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

        fun toArray() = if (size == 0) EMPTY_INT_ARRAY else values.copyOf(size)

        fun toSortedArray() = toArray().apply { sort() }
    }

    /**
     * Set of indexes of a section of a dex file that is cleared without writing to all of it.
     */
    private class IndexSet {
        private var stamps = IntArray(0)
        private var stamp = 0

        fun clear(size: Int) {
            if (stamps.size < size || stamp == Int.MAX_VALUE) {
                stamps = IntArray(maxOf(size, stamps.size))
                stamp = 0
            }
            stamp++
        }

        /**
         * @return False if the set already has the index.
         */
        fun add(index: Int): Boolean {
            if (stamps[index] == stamp) return false
            stamps[index] = stamp
            return true
        }
    }

    /**
     * The values of the instructions of a class to index.
     *
     * @param classDef The class the values were collected from.
     * @param strings String constants, can have duplicates.
     * @param typeHashes Sorted hashes of referenced types, can have duplicates. See [getIndexedClassesReferencingType].
     * @param memberNameHashes Sorted hashes of referenced field and method names, can have duplicates.
     * @param literalHashes Sorted hashes of literals, can have duplicates.
     * @param declaredMethods The methods of an immutable class, see [MethodDeclaration], or null if the class is mutable.
     * @param mutableMethods The methods of a mutable class, or null if the class is immutable.
     * @param changeStamp The [ChangeStamps] stamp before the values of a mutable class were collected.
     */
    internal class ClassIndexValues(
        val classDef: ClassDef,
        val strings: List<String>,
        val typeHashes: IntArray,
        val memberNameHashes: IntArray,
        val literalHashes: IntArray,
        val declaredMethods: IntArray?,
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
        val declaredMethods = IntList()

        // The references of a dex file that were already collected for the class, as most are used repeatedly.
        private val collectedStrings = IndexSet()
        private val collectedTypes = IndexSet()
        private val collectedFields = IndexSet()
        private val collectedMethods = IndexSet()
        private val collectedProtos = IndexSet()

        /**
         * Starts over with another class.
         */
        fun clear(dexFile: DexBackedDexFile?) {
            strings.clear()
            typeHashes.clear()
            memberNameHashes.clear()
            literalHashes.clear()
            declaredMethods.clear()

            if (dexFile != null) {
                collectedStrings.clear(dexFile.stringSection.size)
                collectedTypes.clear(dexFile.typeSection.size)
                collectedFields.clear(dexFile.fieldSection.size)
                collectedMethods.clear(dexFile.methodSection.size)
                collectedProtos.clear(dexFile.protoSection.size)
            }
        }

        fun addDeclaredMethod(method: DexBackedMethod, decoder: DexReferenceDecoder) {
            val proto = decoder.methodProtoIndex(method.methodIndex)
            declaredMethods.add(
                MethodDeclaration.withParameters(
                    MethodDeclaration.of(
                        decoder.methodNameHash(method.methodIndex),
                        decoder.typeHash(decoder.protoReturnTypeIndex(proto)),
                    )
                ) { action ->
                    decoder.forEachProtoParameterTypeIndex(proto) { action(decoder.typeHash(it)) }
                }
            )
        }

        /**
         * Collects the type a field or a method signature refers to. Primitive types are in most classes
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
                ReferenceType.STRING -> if (
                    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
                    collectedStrings.add(referenceIndex)
                ) {
                    strings += decoder.string(referenceIndex)
                }
                ReferenceType.TYPE -> addType(referenceIndex, decoder)
                ReferenceType.FIELD -> if (collectedFields.add(referenceIndex)) {
                    addType(decoder.fieldDefiningClassIndex(referenceIndex), decoder)
                    memberNameHashes.add(decoder.fieldNameHash(referenceIndex))
                    addSignatureType(decoder.fieldTypeIndex(referenceIndex), decoder)
                }
                ReferenceType.METHOD -> if (collectedMethods.add(referenceIndex)) {
                    addType(decoder.methodDefiningClassIndex(referenceIndex), decoder)
                    memberNameHashes.add(decoder.methodNameHash(referenceIndex))

                    val proto = decoder.methodProtoIndex(referenceIndex)
                    if (collectedProtos.add(proto)) {
                        addSignatureType(decoder.protoReturnTypeIndex(proto), decoder)
                        decoder.forEachProtoParameterTypeIndex(proto) { addSignatureType(it, decoder) }
                    }
                }
            }
            return true
        }

        private fun addType(typeIndex: Int, decoder: DexReferenceDecoder) {
            if (collectedTypes.add(typeIndex)) typeHashes.add(decoder.typeHash(typeIndex))
        }

        /**
         * Collects a type as [addSignatureType] does. A primitive type is nothing but a signature type.
         */
        private fun addSignatureType(typeIndex: Int, decoder: DexReferenceDecoder) {
            if (collectedTypes.add(typeIndex) && !decoder.isPrimitive(typeIndex)) {
                typeHashes.add(decoder.typeHash(typeIndex))
            }
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
     * Collects the index values of all methods of [classDef], which is or was the class of the wrapper.
     */
    private fun collectIndexValues(classDef: ClassDef): ClassIndexValues {
        val changeStamp = ChangeStamps.current
        // Mutable methods are equal by signature, so they are kept by identity.
        val mutableMethods = (classDef as? MutableClass)?.methods?.toCollection(
            Collections.newSetFromMap(IdentityHashMap<Method, Boolean>())
        )

        return collectIndexValues(classDef, classDef.methods, mutableMethods, changeStamp)
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
        val declaredMethods = DeclaredMethodIndex(classCount)

        /**
         * Adds the values of a class to the indexes. Classes are added in class map order.
         */
        fun add(wrapper: ClassDefWrapper, values: ClassIndexValues) {
            addIndexed(wrapper, values)
            addStrings(wrapper, values)
            types.addAll(values.typeHashes, wrapper)
            memberNames.addAll(values.memberNameHashes, wrapper)
            literals.addAll(values.literalHashes, wrapper)
        }

        /**
         * Adds the values of each class to the indexes, which are filled in parallel.
         */
        fun addAll(wrappers: List<ClassDefWrapper>, values: List<ClassIndexValues>) {
            listOf<(ClassDefWrapper, ClassIndexValues) -> Unit>(
                ::addIndexed,
                ::addStrings,
                { wrapper, classValues -> types.addAll(classValues.typeHashes, wrapper) },
                { wrapper, classValues -> memberNames.addAll(classValues.memberNameHashes, wrapper) },
                { wrapper, classValues -> literals.addAll(classValues.literalHashes, wrapper) },
            ).parallelStream().forEach { add ->
                wrappers.forEachIndexed { i, wrapper -> add(wrapper, values[i]) }
            }
        }

        /**
         * Frees what the indexes hold in reserve for more classes. A few more can still be added.
         */
        fun trim() {
            listOf<() -> Unit>(
                { strings.values.forEach { (it as? ArrayList<*>)?.trimToSize() } },
                types::trim,
                memberNames::trim,
                literals::trim,
                declaredMethods::trim,
            ).parallelStream().forEach { it() }
        }

        private fun addIndexed(wrapper: ClassDefWrapper, values: ClassIndexValues) {
            wrapper.setIndexed(values)
            values.declaredMethods?.let { declaredMethods.add(wrapper, it) }
        }

        private fun addStrings(wrapper: ClassDefWrapper, values: ClassIndexValues) {
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

    internal constructor(set: Set<ClassDef>, progress: PatcherProgress? = null) : this(
        // Must use linked hash map. A regular map does not preserve the order of classes found
        // in the apk, so old fingerprints that have multiple matches can match the wrong class
        // due to hashmap random class iteration during matching. The issue is with
        // some fingerprint declarations not being unique enough and currently there is no way to
        // check for duplicate matches.
        // See https://github.com/ReVanced/revanced-patcher/issues/74
        //
        // Pre-size so rehashing doesn't occur and use a more performant load factor.
        LinkedHashMap<String, ClassDefWrapper>(2 * set.size, 0.5f),
        progress,
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
        cancelBuildingIndexes()
        classMap.clear()
        closeReferenceMap()
    }

    internal fun closeReferenceMap() {
        cancelBuildingIndexes()
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
        indexBuilder?.add(wrapper, collectIndexValues(classDef))
        if (indexBuild != null) classesToIndex += wrapper
    }

    internal fun getClassesByReferenceMap(): Map<String, List<ClassDefWrapper>> {
        if (stringMap != null) {
            return stringMap!!
        }

        return buildInstructionIndexes()
    }

    /**
     * Instruction indexes that are built in the background, see [startBuildingIndexes].
     */
    private class IndexBuild(
        val builder: ForkJoinTask<IndexBuilder>,
        val canceled: AtomicBoolean,
    )

    private var indexBuild: IndexBuild? = null

    /**
     * Classes that were added while [indexBuild] indexes the classes before them.
     */
    private val classesToIndex = ArrayList<ClassDefWrapper>()

    /**
     * Starts building the instruction indexes in the background, as almost every run needs them, and what
     * patches do until then mostly keeps a single thread busy. Whatever needs the indexes first waits for them.
     *
     * The classes are indexed as they are now. The indexes of a class that is made mutable or is replaced until
     * they are built are handled as those of a class that is after they are built.
     */
    internal fun startBuildingIndexes() {
        // A mutable class can change while it is indexed.
        if (indexBuilder != null || indexBuild != null || mutableWrappers.isNotEmpty()) return
        if (Runtime.getRuntime().availableProcessors() < 2) return

        val wrappers = classMap.values.toList()
        val classDefs = wrappers.map { it.classDef }
        val canceled = AtomicBoolean()
        val builder = ForkJoinPool.commonPool().submit(Callable { buildIndexes(wrappers, classDefs, canceled) })
        indexBuild = IndexBuild(builder, canceled)
    }

    /**
     * Ends building the instruction indexes in the background without a use for them. Until then the classes
     * are read, so this must be called before the DEX files are closed.
     */
    internal fun cancelBuildingIndexes() {
        val build = indexBuild ?: return
        indexBuild = null
        classesToIndex.clear()
        build.canceled.set(true)
        try {
            build.builder.join()
        } catch (_: CancellationException) {
            logger.fine("Canceled building the instruction indexes")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to build the instruction indexes", e)
        }
    }

    private fun buildInstructionIndexes(): Map<String, List<ClassDefWrapper>> {
        val startNanos = System.nanoTime()

        val build = indexBuild
        val builder = if (build == null) {
            val wrappers = classMap.values.toList()
            buildIndexes(wrappers, wrappers.map { it.classDef }, canceled = null)
        } else {
            val builder = build.builder.join()
            indexBuild = null
            // Mostly the classes of extensions.
            val added = classesToIndex.filterNot { it.replaced }
            classesToIndex.clear()
            builder.addAll(added, added.parallelStream().map { collectIndexValues(it.classDef) }.collect(Collectors.toList()))
            builder
        }

        stringMap = builder.strings
        indexBuilder = builder
        indexBuildNanos += System.nanoTime() - startNanos
        return builder.strings
    }

    /**
     * @param classDefs The class of each wrapper.
     */
    private fun buildIndexes(
        wrappers: List<ClassDefWrapper>,
        classDefs: List<ClassDef>,
        canceled: AtomicBoolean?,
    ): IndexBuilder {
        logger.info("Building instruction indexes for ${wrappers.size} classes...")
        val builder = IndexBuilder(wrappers.size)

        // Scanning the instructions is the costly part and reads each class on its own, so it runs
        // in parallel, a chunk at a time to bound memory, while the indexes are filled in class order.
        progress.runTask(PatcherTask.INDEXING_CLASSES, wrappers.size.toLong()) { task ->
            for (start in wrappers.indices step INDEX_CHUNK_SIZE) {
                if (canceled?.get() == true) throw CancellationException()

                val end = minOf(start + INDEX_CHUNK_SIZE, wrappers.size)
                val values = IntStream.range(start, end).parallel()
                    .mapToObj { i -> collectIndexValues(classDefs[i]) }
                    .collect(Collectors.toList())
                builder.addAll(wrappers.subList(start, end), values)
                task.advance((end - start).toLong())
            }
        }
        builder.trim()
        classDefs.forEach { classDef ->
            ((classDef as? DexBackedClassDef)?.dexFile as? CachingDexBackedDexFile)?.releaseStringHashCodes()
        }
        return builder
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
     * as the declared type of a field, as what a method returns or takes, or as the target of an
     * instruction such as `new-instance` and `check-cast`. Primitive types of fields and methods are
     * not indexed.
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
        result.sortInClassMapOrder()

        return Candidates(result, changedMethods)
    }

    private fun ArrayList<ClassDefWrapper>.sortInClassMapOrder() {
        for (i in 1 until size) {
            if (this[i - 1].ordinal > this[i].ordinal) {
                sortBy { it.ordinal }
                break
            }
        }
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

    /**
     * If a method of the class can be the declared method, see [MethodDeclaration]. Classes that are not indexed
     * as they are now, such as mutable classes, always can.
     */
    internal fun canDeclare(wrapper: ClassDefWrapper, declaration: MethodDeclaration): Boolean {
        val builder = indexBuilder ?: return true
        if (wrapper.declaredMethodsSlot < 0 || wrapper.classDef !== wrapper.indexedClassDef) return true
        return declaration.isAny || builder.declaredMethods.canDeclare(wrapper, declaration)
    }

    /**
     * Classes for which [canDeclare] is true, in class map order, or null if the indexes are not built
     * or cannot limit the classes.
     */
    internal fun classesDeclaring(declaration: MethodDeclaration): List<ClassDefWrapper>? {
        val builder = indexBuilder ?: return null
        if (declaration.isAny) return null

        val declaring = ArrayList<ClassDefWrapper>()
        builder.declaredMethods.addClassesDeclaring(declaration, declaring)

        val result = ArrayList<ClassDefWrapper>(declaring.size + mutableWrappers.size)
        // A class that was made mutable is in both.
        declaring.filterTo(result) { !it.replaced && it.classDef === it.indexedClassDef }
        result.addAll(mutableWrappers)
        result.sortInClassMapOrder()
        return result
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
            val dexFile = (classDef as? DexBackedClassDef)?.dexFile as? CachingDexBackedDexFile
            val decoder = dexFile?.let { DexReferenceDecoder(it) }
            val collector = threadLocalCollector.get()
            collector.clear(dexFile)

            methods.forEach { method ->
                if (mutableMethods == null) {
                    if (decoder != null && method is DexBackedMethod && method.dexFile === dexFile) {
                        collector.addDeclaredMethod(method, decoder)
                    } else {
                        collector.declaredMethods.add(MethodDeclaration.of(method))
                    }
                }

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
                if (mutableMethods == null) collector.declaredMethods.toArray() else null,
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
