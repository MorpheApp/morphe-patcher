/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.profile

import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Carries the baseline profile of the input APK over to the DEX files that
 * [STRIP_FAST][app.morphe.patcher.dex.BytecodeMode.STRIP_FAST] writes.
 *
 * Stripping a class from an original DEX file keeps every id of that file, so the profile keeps its
 * indices there, and only the class definitions and the checksum change. The classes it strips, which
 * patches changed, are written to new DEX files, where they are found by their descriptor. These are the
 * classes patches hook into, so they are often among the ones the profile has ART compile.
 *
 * [prepare] reads the original DEX files before they are stripped, and [finish] the DEX files of the
 * patched APK.
 */
internal class StrippedDexProfile private constructor(
    private val kept: Map<String, DexProfile>,
    private val movedClasses: Set<String>,
    private val movedMethods: Map<String, Int>,
    /** The classes that declare [movedMethods], whose methods an added DEX file is searched for. */
    private val movedMethodOwners: Set<String>,
) {
    /**
     * A DEX file of the patched APK. The contents are read once, when they are needed, so that only one
     * DEX file has to be held at a time.
     */
    sealed class OutputDex(val name: String) {
        /** An original DEX file, as the input APK holds it, now named [name]. */
        class Unchanged(name: String, val originalName: String) : OutputDex(name)

        /** An original DEX file without the classes patches changed. */
        class Stripped(name: String, val originalName: String, val contents: () -> ByteBuffer) : OutputDex(name)

        /** A DEX file with the classes patches changed or added. */
        class Added(name: String, val contents: () -> ByteBuffer) : OutputDex(name)
    }

    /**
     * The profile for the DEX files of the patched APK, or null if no part of it is left.
     *
     * @param outputDex Every DEX file of the patched APK.
     */
    fun finish(outputDex: List<OutputDex>): BaselineProfile? {
        val dexFiles = outputDex.mapNotNull { dex ->
            when (dex) {
                is OutputDex.Unchanged -> kept[dex.originalName]?.renamed(dex.name)
                is OutputDex.Stripped -> kept[dex.originalName]?.let { stripped(it, dex) }
                is OutputDex.Added -> added(dex)
            }
        }.filterNot(DexProfile::isEmpty).sortedBy { dexIndex(it.name) }
        return dexFiles.takeIf { it.isNotEmpty() }?.let(::BaselineProfile)
    }

    private fun DexProfile.renamed(name: String) =
        DexProfile(name, checksum, typeCount, methodCount, classes, classDefs, methods, methodFlags)

    /** [profile] with the classes the stripped DEX file still defines, at their new definitions. */
    private fun stripped(profile: DexProfile, dex: OutputDex.Stripped): DexProfile {
        val contents = dex.contents()
        val ids = DexIds(contents)
        val (classes, classDefs) = definedClasses(profile.classes, ids.classDefTypes(), ids.typeCount)
        return DexProfile(
            dex.name, crc32(contents), ids.typeCount, ids.methodCount, classes, classDefs,
            profile.methods, profile.methodFlags,
        )
    }

    /** The classes and methods that moved to the added DEX file, in its indices. */
    private fun added(dex: OutputDex.Added): DexProfile? {
        if (movedClasses.isEmpty() && movedMethods.isEmpty()) return null
        val contents = dex.contents()
        val ids = DexIds(contents)
        val definitionTypes = ids.classDefTypes()

        val classes = IntList()
        val classDefs = IntList()
        // The classes defined here that declare a moved method, whose methods are looked up.
        val owners = BooleanArray(ids.typeCount)
        var hasOwners = false
        definitionTypes.forEachIndexed { definition, type ->
            val descriptor = ids.typeDescriptor(type)
            if (descriptor in movedClasses) {
                classes.add(type)
                classDefs.add(definition)
            }
            if (descriptor in movedMethodOwners) {
                owners[type] = true
                hasOwners = true
            }
        }

        val methods = IntList()
        val flags = IntList()
        if (hasOwners) {
            for (method in 0 until ids.methodCount) {
                if (!owners[ids.methodClass(method)]) continue
                val methodFlags = movedMethods[ids.methodSignature(method)] ?: continue
                methods.add(method)
                flags.add(methodFlags)
            }
        }
        if (classes.size == 0 && methods.size == 0) return null

        return DexProfile(
            name = dex.name,
            checksum = crc32(contents),
            typeCount = ids.typeCount,
            methodCount = ids.methodCount,
            classes = classes.toIntArray().apply { sort() },
            classDefs = classDefs.toIntArray().apply { sort() },
            methods = methods.toIntArray(),
            methodFlags = flags.toByteArray(),
        )
    }

    companion object {
        /**
         * Splits [profile] into what stays in the original DEX files and what moves with the classes
         * patches changed, while the original DEX files are as the input APK holds them.
         *
         * A part of the profile whose checksum or method count does not match its DEX file of the input
         * APK is left out, as ART would not use it either.
         *
         * @param originalDex The contents of each original DEX file by its entry name.
         * @param originalChecksums The CRC-32 of each original DEX entry of the input APK.
         * @param changedClasses The descriptors of the original classes patches changed.
         */
        fun prepare(
            profile: BaselineProfile,
            originalDex: Map<String, ByteBuffer>,
            originalChecksums: Map<String, Int>,
            changedClasses: Set<String>,
        ): StrippedDexProfile {
            val kept = HashMap<String, DexProfile>()
            val movedClasses = HashSet<String>()
            val movedMethods = HashMap<String, Int>()
            val movedMethodOwners = HashSet<String>()

            for (dexProfile in profile.dexFiles) {
                val contents = originalDex[dexProfile.name] ?: continue
                if (originalChecksums[dexProfile.name] != dexProfile.checksum) continue
                val ids = DexIds(contents)
                if (ids.methodCount != dexProfile.methodCount) continue

                val definitionTypes = ids.classDefTypes()
                // Types whose class moves, of the classes this DEX file defines.
                val moved = BooleanArray(ids.typeCount)
                if (changedClasses.isNotEmpty()) {
                    definitionTypes.forEach { type -> if (ids.typeDescriptor(type) in changedClasses) moved[type] = true }
                }

                val stayingClasses = IntList()
                dexProfile.classes.forEach { type ->
                    if (type < moved.size && moved[type]) movedClasses += ids.typeDescriptor(type) else stayingClasses.add(type)
                }

                val methods = IntList()
                val flags = IntList()
                dexProfile.methods.forEachIndexed { index, method ->
                    if (method >= ids.methodCount) return@forEachIndexed
                    val type = ids.methodClass(method)
                    if (moved[type]) {
                        movedMethods.merge(ids.methodSignature(method), dexProfile.methodFlags[index].toInt(), Int::or)
                        movedMethodOwners += ids.typeDescriptor(type)
                    } else {
                        methods.add(method)
                        flags.add(dexProfile.methodFlags[index].toInt())
                    }
                }

                val (classes, classDefs) = definedClasses(stayingClasses.toIntArray(), definitionTypes, ids.typeCount)
                kept[dexProfile.name] = DexProfile(
                    dexProfile.name, dexProfile.checksum, ids.typeCount, ids.methodCount, classes, classDefs,
                    methods.toIntArray(), flags.toByteArray(),
                )
            }

            return StrippedDexProfile(kept, movedClasses, movedMethods, movedMethodOwners)
        }

        /**
         * The [classes] that [definitionTypes] defines, and the indices of their definitions, both
         * ascending, as a profile and its metadata record them.
         */
        private fun definedClasses(classes: IntArray, definitionTypes: IntArray, typeCount: Int): Pair<IntArray, IntArray> {
            // The definition of each type plus one, or 0 for a type this DEX file does not define.
            val definitions = IntArray(typeCount)
            definitionTypes.forEachIndexed { definition, type -> definitions[type] = definition + 1 }
            val defined = IntList()
            val definedDefinitions = IntList()
            classes.forEach { type ->
                val definition = if (type < typeCount) definitions[type] else 0
                if (definition != 0) {
                    defined.add(type)
                    definedDefinitions.add(definition - 1)
                }
            }
            return defined.toIntArray() to definedDefinitions.toIntArray().apply { sort() }
        }

        /** The number in the name of a DEX entry, 1 for `classes.dex`, which orders the entries. */
        internal fun dexIndex(name: String) =
            name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1

        internal fun crc32(contents: ByteBuffer): Int {
            val buffer = contents.duplicate()
            // Through Buffer, as the ByteBuffer overloads are missing on older Android versions.
            (buffer as Buffer).clear()
            return CRC32().apply { update(buffer) }.value.toInt()
        }
    }
}

/** A growable list of ints, which unlike a list of boxed values is quick before the code is compiled. */
private class IntList {
    private var values = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toIntArray() = values.copyOf(size)

    fun toByteArray() = ByteArray(size) { values[it].toByte() }
}
