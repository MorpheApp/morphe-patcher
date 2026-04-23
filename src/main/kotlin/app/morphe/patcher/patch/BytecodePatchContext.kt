/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.patch

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.dex.DexStripper
import app.morphe.patcher.util.ClassMerger.merge
import app.morphe.patcher.util.MethodNavigator
import app.morphe.patcher.util.PatchClasses
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.Closeable
import java.io.File
import java.util.logging.Logger

/**
 * A context for patches containing the current state of the bytecode.
 *
 * @param config The [PatcherConfig] used to create this context.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class BytecodePatchContext internal constructor(private val config: PatcherConfig, val packageMetadata: PackageMetadata) :
    PatchContext<Set<PatcherResult.PatchedDexFile>>,
    Closeable {
    private val logger = Logger.getLogger(this::class.java.name)

    /**
     * [Opcodes] of the supplied [PatcherConfig.apkFile].
     */
    internal lateinit var opcodes: Opcodes

    /**
     * Original DEX files extracted from the APK to the dex output directory.
     * These files are edited in-place during compilation via [DexStripper].
     */
    private lateinit var originalDexFiles: List<File>

    /**
     * Class descriptors that existed in the original APK (before any extensions or patches).
     */
    private lateinit var originalClassDescriptors: Set<String>

    /**
     * Maps each original DEX entry name to the set of class descriptors it contains.
     * Used by [BytecodeMode.STRIP_SAFE] to rebuild only DEX files with modified classes.
     */
    private lateinit var classDescriptorsByEntry: Map<String, Set<String>>

    /**
     * All classes for the target app and any extension classes.
     */
    internal var patchClasses: PatchClasses = PatchClasses(emptySet())

    /**
     * The directory where DEX files are written during compilation.
     */
    private val dexOutputDir = config.patchedFiles.resolve("dex")
    private val dexWorkingDir = config.patchedFiles.resolve("originalDex")

    internal fun decodeDexFiles() {
        // Extract original DEX files from the APK to disk for later in-place editing.
        dexOutputDir.apply { deleteRecursively(); mkdirs() }
        dexWorkingDir.apply { deleteRecursively(); mkdirs()}

        val readResult = DexReadWrite.readMultidexFileFromZip(config.apkFile, dexWorkingDir)
        opcodes = readResult.dexFile.opcodes
        originalClassDescriptors = readResult.dexFile.classes.let { classes ->
            classes.mapTo(HashSet(2 * classes.size)) { it.type }
        }
        classDescriptorsByEntry = readResult.classDescriptorsByEntry
        patchClasses = PatchClasses(readResult.dexFile.classes)

        originalDexFiles = readResult.extractedDexFiles
    }

    /**
     * Merge the extension of [bytecodePatch] into the [BytecodePatchContext].
     * If no extension is present, the function will return early.
     *
     * @param bytecodePatch The [BytecodePatch] to merge the extension of.
     */
    internal fun mergeExtension(bytecodePatch: BytecodePatch) {
        bytecodePatch.extensionInputStream?.get()?.use { extensionStream ->
            DexReadWrite.readDexStream(extensionStream).classes.forEach { classDef ->
                val existingClass = patchClasses.classByOrNull(classDef.type) ?: run {
                    logger.fine { "Adding class \"$classDef\"" }

                    patchClasses.addClass(classDef)

                    return@forEach
                }

                logger.fine { "Class \"$classDef\" exists already. Adding missing methods and fields." }

                existingClass.merge(classDef, this@BytecodePatchContext).let { mergedClass ->
                    // If the class was merged, replace the original class with the merged class.
                    if (mergedClass === existingClass) {
                        return@let
                    }

                    patchClasses.addClass(mergedClass)
                }
            }
        } ?: logger.fine("No extension to merge")
    }

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    fun classDefBy(classType: String) = patchClasses.classBy(classType)

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    fun classDefBy(predicate: (ClassDef) -> Boolean) = patchClasses.classBy(predicate)

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    fun classDefByOrNull(classType: String) = patchClasses.classByOrNull(classType)

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     */
    fun classDefByOrNull(predicate: (ClassDef) -> Boolean) = patchClasses.classByOrNull(predicate)

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassDefBy(classType: String) = patchClasses.mutableClassBy(classType)

    /**
     * Find a class with a predicate.
     *
     * @param classDef An immutable class.
     * @return A mutable version of the class definition.
     */
    fun mutableClassDefBy(classDef: ClassDef) = patchClasses.mutableClassBy(classDef)

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassDefBy(predicate: (ClassDef) -> Boolean) = patchClasses.mutableClassBy(predicate)

    /**
     * Mutable class from a full class name.
     * Returns `null` if class is not available, such as a built in Android or Java library.
     *
     * @param classType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassDefByOrNull(classType: String) = patchClasses.mutableClassByOrNull(classType)

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassDefByOrNull(predicate: (ClassDef) -> Boolean) = patchClasses.mutableClassByOrNull(predicate)

    /**
     * Iterate over all classes in the target app and all extension code.
     */
    fun classDefForEach(action: (ClassDef) -> Unit) {
        patchClasses.forEach(action)
    }

    /**
     * @return All classes that contain the string parameter.
     */
    fun classDefByStrings(
        literalString: String,
        comparison: StringComparisonType = StringComparisonType.EQUALS
    ): List<ClassDef> {
        val result = mutableSetOf<ClassDef>()
        patchClasses.getClassesByStringMap().forEach { (string, list) ->
            if (comparison.compare(string, literalString)) {
                list.forEach { wrapper ->
                    result += wrapper.classDef
                }
            }
        }
        return result.toList()
    }

    /**
     * @return All classes that contain at least 1 string.
     */
    fun getAllClassesWithStrings(): List<ClassDef> {
        return patchClasses.getAllClassesWithStrings().map { it.classDef }
    }

    /**
     * @return All classes that contain the exact string.
     */
    fun getAllClassesWithString(stringLiteral: String): List<ClassDef> {
        val classes = patchClasses.getClassesFromOpcodeStringLiteral(stringLiteral)
            ?: return emptyList()
        return classes.map { it.classDef }
    }

    /**
     * Navigate a method.
     *
     * @param method The method to navigate.
     *
     * @return A [MethodNavigator] for the method.
     */
    @Deprecated("Instead use Fingerprint instruction match `getMethodCalled()`," +
            " or lookup a method from an index using BytecodeUtils MethodReference.getMutableMethod()")
    fun navigate(method: MethodReference) = MethodNavigator(method)

    /**
     * Compile bytecode from the [BytecodePatchContext].
     *
     * Dispatches to the appropriate compilation strategy based on [PatcherConfig.bytecodeMode].
     *
     * @return The compiled bytecode.
     */
    @InternalApi
    override fun get(): Set<PatcherResult.PatchedDexFile> {
        logger.info("Compiling patched dex files (mode: ${config.bytecodeMode})")

        return when (config.bytecodeMode) {
            BytecodeMode.NONE -> emptySet()
            BytecodeMode.FULL -> compileFull()
            BytecodeMode.STRIP_FAST -> compileStripFast()
            BytecodeMode.STRIP_SAFE -> compileStripSafe()
        }
    }

    /**
     * [BytecodeMode.FULL]: Write ALL classes through DexPool, producing completely new DEX files.
     * Slowest but most space-efficient since no dead data remains.
     */
    private fun compileFull(): Set<PatcherResult.PatchedDexFile> {
        val classDefs = patchClasses.classMap.values.let { values ->
            values.mapTo(ArrayList(values.size)) { it.classDef }
        }
        patchClasses.close()

        dexOutputDir.apply { deleteRecursively(); mkdirs() }
        // TODO: Separate out any j$ classes into their own DEX files, because d8/r8 will reject the DEX files otherwise.
        DexReadWrite.writeMultiDexFile(dexOutputDir, classDefs, opcodes, -1, logger)

        config.verifier.verifyDexDirectory(dexOutputDir)
        return dexOutputDir.listFiles { it.isFile }!!.sorted().map {
            PatcherResult.PatchedDexFile(it.name, it.inputStream())
        }.toSet()
    }

    /**
     * [BytecodeMode.STRIP_FAST]: Remove modified class_def entries from original DEX files
     * in-place (compacting the class_defs array), then write modified + new classes to
     * separate DEX files.
     *
     * Fastest, but leaves dead data in original DEX files (orphaned class_data, annotations, etc.).
     * Unlike hollowing, this completely removes the class_def entries so there are no
     * duplicate class definitions across DEX files.
     */
    private fun compileStripFast(): Set<PatcherResult.PatchedDexFile> {
        val (modifiedOriginalDescriptors, classesForNewDex)
                = getModifiedOriginalDescriptorsAndClassesForNewDex()

        patchClasses.close()

        val results = mutableSetOf<PatcherResult.PatchedDexFile>()

        // 1. Write modified + new classes through DexPool.
        var newDexCount = 0
        if (classesForNewDex.isNotEmpty()) {
            logger.info("Writing ${classesForNewDex.size} new classes to new DEX files")
            DexReadWrite.writeMultiDexFile(dexOutputDir, classesForNewDex, opcodes, -1, logger)
            val newDexFiles = dexOutputDir.listFiles { it.isFile }!!.sorted()
            newDexCount = newDexFiles.size
        }

        // 2. Strip modified class_def entries from original DEX files in-place.
        if (modifiedOriginalDescriptors.isNotEmpty()) {
            logger.info("Stripping ${modifiedOriginalDescriptors.size} modified classes from original DEX files")
            for (originalDex in originalDexFiles) {
                val stripped = DexStripper.stripInPlace(originalDex, modifiedOriginalDescriptors)
                if (stripped > 0) {
                    logger.fine { "Stripped $stripped class_def entries from ${originalDex.name}" }
                }
            }
        }

        // 3. Rename: new DEX files get lowest slots (loaded first), originals shifted up.
        dexWorkingDir.listFiles { it.isFile }!!.sorted().forEachIndexed { i, tempFile ->
            val newIndex = newDexCount + i
            val dexName = if (newIndex == 0) "classes.dex" else "classes${newIndex + 1}.dex"
            tempFile.renameTo(dexOutputDir.resolve(dexName))
        }

        config.verifier.verifyDexDirectory(dexOutputDir)

        dexOutputDir.listFiles { it.isFile }!!.sorted().forEach { dexFile ->
            results.add(PatcherResult.PatchedDexFile(dexFile.name, dexFile.inputStream()))
        }

        return results
    }

    /**
     * [BytecodeMode.STRIP_SAFE]: For each original DEX file that contains modified classes,
     * rebuild it via DexPool with only the unmodified classes (stripping out modified ones).
     * DEX files with no modified classes are passed through unchanged.
     * Modified original classes and new extension classes are written to separate DEX files
     * that are loaded first.
     *
     * Slower than [STRIP_FAST][BytecodeMode.STRIP_FAST] but produces clean DEX files
     * with no dead data or duplicate class definitions.
     */
    private fun compileStripSafe(): Set<PatcherResult.PatchedDexFile> {
        val (modifiedOriginalDescriptors, classesForNewDex)
                = getModifiedOriginalDescriptorsAndClassesForNewDex()

        dexOutputDir.apply { deleteRecursively(); mkdirs() }

        // 1. Write modified + new classes through DexPool first so we know how many files they produce.
        var newDexCount = 0
        if (classesForNewDex.isNotEmpty()) {
            logger.info("Writing ${classesForNewDex.size} modified/new classes to new DEX files")
            DexReadWrite.writeMultiDexFile(dexOutputDir, classesForNewDex, opcodes, -1, null)
            newDexCount = dexOutputDir.listFiles { it.isFile }!!.size
        }

        // 2. For each original DEX file, either pass through or rebuild without modified classes.
        originalDexFiles.forEachIndexed { i, originalDex ->
            val entryDescriptors = classDescriptorsByEntry[originalDex.name] ?: emptySet()
            val hasModifiedClasses = entryDescriptors.any { it in modifiedOriginalDescriptors }

            if (!hasModifiedClasses) {
                // No modified classes — copy the original file as-is.
                logger.fine { "Passing through unmodified DEX: ${originalDex.name}" }
                originalDex.renameTo(dexOutputDir.resolve(getDexName(newDexCount)))
                newDexCount++
            } else {
                // Rebuild this DEX via DexPool with only the unmodified classes.
                val unmodifiedClasses = entryDescriptors
                    .filter { it !in modifiedOriginalDescriptors }
                    .mapNotNull { patchClasses.classMap[it]?.classDef }
                    .toMutableList()

                if (unmodifiedClasses.isNotEmpty()) {
                    logger.info("Rebuilding DEX: ${originalDex.name} (stripping ${
                        entryDescriptors.count { it in modifiedOriginalDescriptors }
                    } modified classes, keeping ${unmodifiedClasses.size})")

                    // Write to a temp dir to avoid naming collisions.
                    val tempDir = config.patchedFiles.resolve("dex_rebuild_$i").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val rebuiltFiles = DexReadWrite.writeMultiDexFile(
                        tempDir, unmodifiedClasses, opcodes, -1, null
                    )
                    rebuiltFiles.forEach { rebuiltFile ->
                        val newName = getDexName(newDexCount)
                        rebuiltFile.renameTo(dexOutputDir.resolve(newName))
                        logger.fine("${originalDex.name} -> $newName")
                        newDexCount++
                    }
                } else {
                    logger.info("Skipping DEX: ${originalDex.name} (all classes were modified)")
                    // All classes in this DEX were modified — no file needed.
                }
            }
        }

        patchClasses.close()

        val results = mutableSetOf<PatcherResult.PatchedDexFile>()

        config.verifier.verifyDexDirectory(dexOutputDir)

        dexOutputDir.listFiles { it.isFile }!!.sorted().forEach { dexFile ->
            results.add(PatcherResult.PatchedDexFile(dexFile.name, dexFile.inputStream()))
        }

        return results
    }

     private fun getModifiedOriginalDescriptorsAndClassesForNewDex(): Pair<HashSet<String>, MutableList<ClassDef>> {
        // Identify modified original class descriptors.
        val modifiedOriginalDescriptors = HashSet<String>(1024, 0.5f)
        // Collect classes for the new DEX: modified originals + brand-new extension classes.
        val classesForNewDex = ArrayList<ClassDef>(1024)

        for (entry in patchClasses.classMap.values) {
            val def = entry.classDef
            val inOriginalClassDescriptors = def.type in originalClassDescriptors

            if (def is MutableClass) {
                classesForNewDex.add(def)
                if (inOriginalClassDescriptors) {
                    modifiedOriginalDescriptors.add(def.type)
                }
            } else if (!inOriginalClassDescriptors) {
                classesForNewDex.add(def)
            }
        }

        return modifiedOriginalDescriptors to classesForNewDex
    }

    internal fun getDexName(index: Int): String = if (index == 0) "classes.dex" else "classes${index + 1}.dex"

    override fun close() {
        patchClasses.close()
    }
}
