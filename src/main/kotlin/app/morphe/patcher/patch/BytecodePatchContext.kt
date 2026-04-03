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
     * All classes for the target app and any extension classes.
     */
    internal lateinit var patchClasses: PatchClasses

    /**
     * The directory where DEX files are written during compilation.
     */
    private val dexOutputDir = config.patchedFiles.resolve("dex")
    private val dexWorkingDir = config.apkFiles.resolve("dex")

    internal fun decodeDexFiles() {
        val readResult = DexReadWrite.readMultidexFile(config.apkFile)
        opcodes = readResult.dexFile.opcodes
        originalClassDescriptors = readResult.dexFile.classes.mapTo(HashSet()) { it.type }
        patchClasses = PatchClasses(readResult.dexFile.classes)

        // Extract original DEX files from the APK to disk for later in-place editing.
        dexOutputDir.apply { deleteRecursively(); mkdirs() }
        dexWorkingDir.apply { deleteRecursively(); mkdirs()}
        originalDexFiles = DexReadWrite.extractDexEntries(config.apkFile, readResult.dexEntryNames, dexWorkingDir)
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
    fun navigate(method: MethodReference) = MethodNavigator(method)

    /**
     * Compile bytecode from the [BytecodePatchContext].
     *
     * Uses an optimized approach: original DEX files (already on disk) are binary-stripped
     * of modified class definitions in-place via memory-mapped I/O, and only modified/new
     * classes are written through DexPool. This avoids the expensive intern+encode pass
     * for the vast majority of unmodified classes.
     *
     * @return The compiled bytecode.
     */
    @InternalApi
    override fun get(): Set<PatcherResult.PatchedDexFile> {
        logger.info("Compiling patched dex files")

        // Free up memory before compiling the dex files.
        patchClasses.closeStringMap()

        // Identify which original classes were modified (converted to MutableClass).
        val modifiedOriginalDescriptors = patchClasses.classMap.values
            .filter { it.classDef is MutableClass && it.classDef.type in originalClassDescriptors }
            .mapTo(HashSet()) { it.classDef.type }

        // Collect classes that need DexPool: modified originals + all new classes (extensions).
        val classesForNewDex = patchClasses.classMap.values
            .filter { it.classDef is MutableClass || it.classDef.type !in originalClassDescriptors }
            .map { it.classDef }
            .toMutableList()

        // Close patchClasses to free the parsed class data before writing.
        patchClasses.close()

        val results = mutableSetOf<PatcherResult.PatchedDexFile>()

        // 1. Strip modified classes from original DEX files in-place via memory-mapped I/O.
        if (modifiedOriginalDescriptors.isNotEmpty()) {
            logger.info(
                "Stripping ${modifiedOriginalDescriptors.size} modified classes from original DEX files"
            )
            for (originalDex in originalDexFiles) {
                DexStripper.stripInPlace(originalDex, modifiedOriginalDescriptors)
            }
        }

        // 2. Write modified + new classes through DexPool into new DEX files.
        var newDexCount = 0
        if (classesForNewDex.isNotEmpty()) {
            logger.info(
                "Writing ${classesForNewDex.size} new classes to new DEX files"
            )

            DexReadWrite.writeMultiDexFile(dexOutputDir, classesForNewDex, opcodes, -1, logger)

            val newDexFiles = dexOutputDir.listFiles { it.isFile }!!.sorted()
            newDexCount = newDexFiles.size
        }

        // 3. Rename all DEX files to final names.
        //    New DEX files get the lowest-numbered slots so they are loaded first by the classloader.
        //    Original (stripped) DEX files are shifted up.

        // Original DEX files: slots newDexCount .. newDexCount+origCount-1
        dexWorkingDir.listFiles { it.isFile }.forEachIndexed { i, tempFile ->
            val newIndex = newDexCount + i
            val dexName = if (newIndex == 0) "classes.dex" else "classes${i + 1}.dex"
            val dst = dexOutputDir.resolve(dexName)
            tempFile.renameTo(dst)
        }

        dexOutputDir.listFiles { it.isFile }.forEachIndexed { i, dexFile ->
            results.add(PatcherResult.PatchedDexFile(dexFile.name, dexFile.inputStream()))
        }

        return results
    }

    override fun close() {
        patchClasses.close()
    }
}
