/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("DEPRECATION_ERROR", "DEPRECATION", "OVERRIDE_DEPRECATION")

package app.morphe.patcher.patch

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.PatcherTask
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.TaskProgress
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.dex.CachingDexBackedDexFile
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.dex.DexStripper
import app.morphe.patcher.dex.MappedFile
import app.morphe.patcher.dex.MultidexReadResult
import app.morphe.patcher.runTask
import app.morphe.patcher.util.ClassMerger.merge
import app.morphe.patcher.util.FileUtils.safelyMoveTo
import app.morphe.patcher.util.PatchClasses
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.logging.Logger

/**
 * A context for patches containing the current state of the bytecode.
 *
 * Patches do not need a context anymore, since only one APK is ever patched at a time. Use the global
 * functions instead, such as [app.morphe.patcher.patch.classDefBy], which work on the state this class
 * still holds. Existing patch bundles are executed with this class, so it cannot be removed.
 *
 * @param config The [PatcherConfig] used to create this context.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
@Deprecated("Patches do not need a context. Use the global functions, such as classDefBy().")
class BytecodePatchContext internal constructor(
    private val config: PatcherConfig,
    @Deprecated("Use the global property.", ReplaceWith("packageMetadata", "app.morphe.patcher.patch.packageMetadata"))
    val packageMetadata: PackageMetadata,
) : PatchContext<Set<PatcherResult.PatchedDexFile>>,
    Closeable {
    private val logger = Logger.getLogger(this::class.java.name)

    override val fileWorkspace = config.fileWorkspace

    override val validate = config.validate

    /**
     * Total time spent resolving fingerprints, used if [validate] is set.
     * Each fingerprint excludes the time of other fingerprints it resolves.
     */
    internal var fingerprintMatchNanos = 0L

    /**
     * [Opcodes] of the supplied [PatcherConfig.apkFile].
     */
    internal lateinit var opcodes: Opcodes

    /**
     * Live memory mappings of the original DEX files, keyed by file.
     *
     * These back the original [com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile]
     * views and must stay open while those classes are read. Each mapping is released
     * (via [releaseDexMapping]/[releaseAllDexMappings]) once its file's classes have been
     * read and before the file is rewritten, renamed, or deleted, so the deterministic
     * unmapping releases any Windows file lock in time for those operations.
     */
    private var originalDexMappings: MutableMap<File, MappedFile> = LinkedHashMap()

    /**
     * Number of classes that existed in the original APK (before any extensions or patches).
     */
    private var originalClassCount = 0

    /**
     * Extension DEX files that added classes to the app.
     */
    private val extensionDexFiles = mutableListOf<ExtensionDexFile>()

    /**
     * An extension DEX file merged into the app.
     *
     * @param bytes The contents of the file.
     * @param classTypes The types of all classes of the file.
     * @param addedClasses The classes of the file that were added to the app as they are.
     */
    private class ExtensionDexFile(
        val bytes: ByteArray,
        val classTypes: List<String>,
        val addedClasses: List<ClassDef>,
    )

    private lateinit var readResult: MultidexReadResult
    private val classDescriptorsByEntry get() = readResult.classDescriptorsByEntry

    /**
     * Whether [BytecodeMode.STRIP_FAST] compiled every DEX file of the input, which it otherwise does not.
     */
    internal var dexFilesComplete = false

    private var classes = PatchClasses(emptySet())

    /**
     * All classes for the target app and any extension classes.
     */
    internal var patchClasses: PatchClasses
        get() {
            if (decodeOnFirstUse) decodeDexFilesOnFirstUse()
            return classes
        }
        set(value) {
            classes = value
        }

    /**
     * Whether the DEX files are decoded when they are first needed, instead of before patches execute.
     */
    @Volatile
    internal var decodeOnFirstUse = false

    /**
     * Time spent decoding the DEX files when they were first needed.
     */
    private var decodeNanos = 0L

    /**
     * Total time spent on what is not the work of a patch or a fingerprint that happens to need it first,
     * which is decoding the DEX files and building the instruction indexes.
     */
    internal val preparationNanos
        get() = decodeNanos + classes.indexBuildNanos

    /**
     * The directory where DEX files are written during compilation.
     */
    private val dexOutputDir = config.patchedFiles.resolve("dex")
    private val dexWorkingDir = config.patchedFiles.resolve("originalDex")

    @Synchronized
    private fun decodeDexFilesOnFirstUse() {
        if (!decodeOnFirstUse) return

        val startNanos = System.nanoTime()
        decodeDexFiles()
        config.bytecodeMode = config.useBytecodeMode
        decodeOnFirstUse = false
        decodeNanos += System.nanoTime() - startNanos
    }

    internal fun decodeDexFiles() = config.progress.runTask(PatcherTask.DECODING_DEX) {
        // Extract original DEX files from the APK to disk for later in-place editing.
        dexOutputDir.apply { deleteRecursively(); mkdirs() }
        dexWorkingDir.apply { deleteRecursively(); mkdirs()}

        readResult = DexReadWrite.readMultidexFileFromZip(config.apkFile, dexWorkingDir)
        opcodes = readResult.dexFile.opcodes
        val classes = readResult.dexFile.classes
        originalClassCount = classes.size
        patchClasses = PatchClasses(classes, config.progress)

        // Insertion-ordered so iteration follows the original classes*.dex order; a plain
        // HashMap iterates in File-hash order, which varies with the temporary directory
        // path and makes STRIP_SAFE's output file naming nondeterministic.
        originalDexMappings = LinkedHashMap<File, MappedFile>(readResult.mappedFiles.size).apply {
            readResult.mappedFiles.forEachIndexed { index, mappedFile ->
                put(mappedFile.originalFile, mappedFile)
            }
        }
    }

    /**
     * Releases the memory mapping of a single original DEX [file], if still open.
     *
     * Must be called before the file is rewritten in-place by [DexStripper],
     * renamed, or deleted so that the deterministic unmapping releases any Windows
     * file lock beforehand.
     */
    private fun releaseDexMapping(file: File) {
        originalDexMappings.remove(file)?.close()
    }

    /**
     * Releases every remaining original DEX memory mapping. Idempotent.
     */
    private fun releaseAllDexMappings() {
        if (originalDexMappings.isEmpty()) return

        originalDexMappings.values.toList().forEach { mappedFile ->
            runCatching { mappedFile.close() }
        }
        originalDexMappings.clear()
    }

    /**
     * Merge the extension of [patch] into the [BytecodePatchContext].
     * If no extension is present, the function will return early.
     *
     * @param patch The [Patch] to merge the extension of.
     */
    internal fun mergeExtension(patch: Patch) {
        patch.extensionStreamProviders.forEach { provider ->
            provider.get().forEach { supplier ->
                mergeExtensionStream(supplier.get())
            }
        }
    }

    private fun mergeExtensionStream(extensionStream: InputStream) {
        extensionStream.use { stream ->
            val dexFile = DexReadWrite.readDexStream(stream)
            val classTypes = ArrayList<String>()
            val addedClasses = ArrayList<ClassDef>()

            dexFile.classes.forEach { classDef ->
                classTypes += classDef.type

                val existingClass = patchClasses.classByOrNull(classDef.type) ?: run {
                    logger.fine { "Adding class \"$classDef\"" }

                    patchClasses.addClass(classDef)
                    addedClasses += classDef

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

            val bytes = (dexFile as? CachingDexBackedDexFile)?.bytes
            if (bytes != null && addedClasses.isNotEmpty()) {
                extensionDexFiles += ExtensionDexFile(bytes, classTypes, addedClasses)
            }
        }
    }

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    @Deprecated("Use the global function.", ReplaceWith("classDefBy(classType)", "app.morphe.patcher.patch.classDefBy"))
    fun classDefBy(classType: String) = patchClasses.classBy(classType)

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    @Deprecated("Use the global function.", ReplaceWith("classDefBy(predicate)", "app.morphe.patcher.patch.classDefBy"))
    fun classDefBy(predicate: (ClassDef) -> Boolean) = patchClasses.classBy(predicate)

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassDefBy
     */
    @Deprecated("Use the global function.", ReplaceWith("classDefByOrNull(classType)", "app.morphe.patcher.patch.classDefByOrNull"))
    fun classDefByOrNull(classType: String) = patchClasses.classByOrNull(classType)

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     */
    @Deprecated("Use the global function.", ReplaceWith("classDefByOrNull(predicate)", "app.morphe.patcher.patch.classDefByOrNull"))
    fun classDefByOrNull(predicate: (ClassDef) -> Boolean) = patchClasses.classByOrNull(predicate)

    /**
     * Find a class with a predicate.
     *
     * **Important:** Use this method only if you are going to modify the class or any of its methods.
     * Calling this method without making any modifications can cause out of memory errors.
     *
     * If your code does not modify the class or any of its methods, then instead use [classDefBy].
     *
     * @param classType The full classname.
     * @return A mutable version of the class type.
     */
    @Deprecated("Use the global function.", ReplaceWith("mutableClassDefBy(classType)", "app.morphe.patcher.patch.mutableClassDefBy"))
    fun mutableClassDefBy(classType: String) = patchClasses.mutableClassBy(classType)

    /**
     * Find a class with a predicate.
     *
     * **Important:** Use this method only if you are going to modify the class or any of its methods.
     * Calling this method without making any modifications can cause out of memory errors.
     *
     * @param classDef An immutable class.
     * @return A mutable version of the class definition.
     */
    @Deprecated("Use the global function.", ReplaceWith("mutableClassDefBy(classDef)", "app.morphe.patcher.patch.mutableClassDefBy"))
    fun mutableClassDefBy(classDef: ClassDef) = patchClasses.mutableClassBy(classDef)

    /**
     * Find a class with a predicate.
     *
     * **Important:** Use this method only if you are going to modify the class or any of its methods.
     * Calling this method without making any modifications can cause out of memory errors.
     *
     * If your code does not modify the class or any of its methods, then instead use [classDefBy].
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    @Deprecated("Use the global function.", ReplaceWith("mutableClassDefBy(predicate)", "app.morphe.patcher.patch.mutableClassDefBy"))
    fun mutableClassDefBy(predicate: (ClassDef) -> Boolean) = patchClasses.mutableClassBy(predicate)

    /**
     * Mutable class from a full class name.
     * Returns `null` if class is not available, such as a built-in Android or Java library.
     *
     * **Important:** Use this method only if you are going to modify the class or any of its methods.
     * Calling this method without making any modifications can cause out of memory errors.
     *
     * If your code does not modify the class or any of its methods, then instead use [classDefByOrNull].
     *
     * @param classType The full classname.
     * @return A mutable version of the class type.
     */
    @Deprecated("Use the global function.", ReplaceWith("mutableClassDefByOrNull(classType)", "app.morphe.patcher.patch.mutableClassDefByOrNull"))
    fun mutableClassDefByOrNull(classType: String) = patchClasses.mutableClassByOrNull(classType)

    /**
     * Find a mutable class with a predicate.
     *
     * **Important:** Use this method only if you are going to modify the class or any of its methods.
     * Calling this method without making any modifications can cause out of memory errors.
     *
     * If your code does not modify the class or any of its methods, then instead use [classDefByOrNull].
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    @Deprecated("Use the global function.", ReplaceWith("mutableClassDefByOrNull(predicate)", "app.morphe.patcher.patch.mutableClassDefByOrNull"))
    fun mutableClassDefByOrNull(predicate: (ClassDef) -> Boolean) = patchClasses.mutableClassByOrNull(predicate)

    /**
     * Iterate over all classes in the target app and all extension code.
     */
    @Deprecated("Use the global function.", ReplaceWith("classDefForEach(action)", "app.morphe.patcher.patch.classDefForEach"))
    fun classDefForEach(action: (ClassDef) -> Unit) {
        patchClasses.forEach(action)
    }

    /**
     * @return All classes that contain the string parameter.
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("classDefByStrings(literalString, comparison)", "app.morphe.patcher.patch.classDefByStrings"),
    )
    fun classDefByStrings(
        literalString: String,
        comparison: StringComparisonType = StringComparisonType.EQUALS
    ): List<ClassDef> {
        // Exact matches can only ever come from the one key equal to literalString, so look it
        // up directly instead of comparing against every string in the index.
        if (comparison == StringComparisonType.EQUALS) {
            return patchClasses.getClassesFromOpcodeStringLiteral(literalString)
                ?.map { it.classDef } ?: emptyList()
        }

        val result = mutableSetOf<ClassDef>()
        patchClasses.getClassesByReferenceMap().forEach { (string, list) ->
            if (comparison.compare(string, literalString)) {
                list.forEach { wrapper ->
                    if (!wrapper.replaced) result += wrapper.classDef
                }
            }
        }
        return result.toList()
    }

    /**
     * @return All classes that contain at least 1 string.
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("getAllClassesWithStrings()", "app.morphe.patcher.patch.getAllClassesWithStrings"),
    )
    fun getAllClassesWithStrings(): List<ClassDef> {
        return patchClasses.getAllClassesWithStrings().map { it.classDef }
    }

    /**
     * @return All classes that contain the exact string.
     * @see classDefByStrings
     */
    @Deprecated(
        "Use classDefByStrings instead, which handles all string comparisons",
        ReplaceWith("classDefByStrings(stringLiteral)"),
        level = DeprecationLevel.HIDDEN
    )
    fun getAllClassesWithString(stringLiteral: String): List<ClassDef> {
        return classDefByStrings(stringLiteral)
    }

    /**
     * Navigate a method.
     *
     * @param method The method to navigate.
     *
     * @return A `MethodNavigator` for the method.
     */
    @Deprecated("Instead use Fingerprint instruction match `getMethodCalled()`," +
            " or lookup a method from an index using BytecodeUtils MethodReference.getMutableMethod()",
        level = DeprecationLevel.HIDDEN
    )
    @Suppress("DEPRECATION")
    fun navigate(method: MethodReference) = app.morphe.patcher.util.MethodNavigator(this, method)

    /**
     * Compile bytecode from the [BytecodePatchContext].
     *
     * Dispatches to the appropriate compilation strategy based on [PatcherConfig.bytecodeMode].
     *
     * @return The compiled bytecode.
     */
    @InternalApi
    override fun get(): Set<PatcherResult.PatchedDexFile> {
        // Patching ended, so nothing needs the DEX files that were not decoded.
        decodeOnFirstUse = false

        logger.info("Compiling patched dex files (mode: ${config.bytecodeMode})")

        if (config.bytecodeMode == BytecodeMode.NONE) return emptySet()

        // Counted in the classes written, whose number each mode learns as it goes.
        return config.progress.runTask(PatcherTask.COMPILING_DEX, total = 0) { task ->
            when (config.bytecodeMode) {
                BytecodeMode.NONE -> emptySet()
                BytecodeMode.FULL -> compileFull(task)
                BytecodeMode.STRIP_FAST -> compileStripFast(task)
                BytecodeMode.STRIP_SAFE -> compileStripSafe(task)
            }
        }
    }

    /**
     * [BytecodeMode.FULL]: Write ALL classes through DexPool, producing completely new DEX files.
     * Slowest but most space-efficient since no dead data remains.
     */
    private fun compileFull(task: TaskProgress): Set<PatcherResult.PatchedDexFile> {
        val classDefs = patchClasses.classMap.values.let { values ->
            values.mapTo(ArrayList(values.size)) { it.classDef }
        }
        logMutableClassCount()
        patchClasses.close()

        dexOutputDir.apply { deleteRecursively(); mkdirs() }
        // TODO: Separate out any j$ classes into their own DEX files, because d8/r8 will reject the DEX files otherwise.
        DexReadWrite.writeMultiDexFile(dexOutputDir, classDefs, opcodes, -1, logger, task)

        // All original classes have now been read; release their mappings.
        releaseAllDexMappings()

        config.verifier.verifyDexDirectory(dexOutputDir)
        return dexOutputDir.listFiles { it.isFile }!!.sorted().map {
            PatcherResult.PatchedDexFile(it.name, it.inputStream())
        }.toSet()
    }

    /**
     * [BytecodeMode.STRIP_FAST]: Remove modified class_def entries from original DEX files
     * in-place (compacting the class_defs array), then write modified + new classes to
     * separate DEX files numbered after the originals.
     *
     * Fastest, but leaves dead data in original DEX files (orphaned class_data, annotations, etc.).
     * No class is defined twice, so load order does not matter, and the result holds only the
     * stripped originals and the new files: the output keeps every other original DEX entry as is.
     */
    private fun compileStripFast(task: TaskProgress): Set<PatcherResult.PatchedDexFile> {
        val (modifiedOriginalDescriptors, classesForNewDex)
                = getModifiedOriginalDescriptorsAndClassesForNewDex()

        // 1. Copy the extension DEX files with the extension classes that patches did not change,
        // so these classes need not be written again.
        val extensionDexCopies = copyUnchangedExtensionClasses(classesForNewDex)

        logMutableClassCount()
        patchClasses.close()

        // 2. Write modified + new classes through DexPool, numbered after the originals. This reads
        // the original DEX files, so it runs before they are stripped.
        var nextDexIndex = originalDexMappings.keys.maxOfOrNull(::dexIndex) ?: 0
        if (classesForNewDex.isNotEmpty()) {
            logger.info("Writing ${classesForNewDex.size} new classes to new DEX files")
            DexReadWrite.writeMultiDexFile(config.patchedFiles.resolve("newDex"), classesForNewDex, opcodes, -1, logger, task)
                .forEach { dexFile ->
                    dexFile.safelyMoveTo(dexOutputDir.resolve(getDexName(nextDexIndex++)))
                }
        }
        extensionDexCopies.forEach { dexFile ->
            dexFile.safelyMoveTo(dexOutputDir.resolve(getDexName(nextDexIndex++)))
        }

        // 3. Strip modified class_def entries from original DEX files in-place.
        val originalDexNames = originalDexMappings.keys.mapTo(HashSet()) { it.name }
        val emptiedDexNames = HashSet<String>()
        val strippedDexNames = originalDexMappings.filter { (originalDex, mappedFile) ->
            val stripped = DexStripper.stripInPlace(mappedFile, modifiedOriginalDescriptors)
            if (stripped > 0 && stripped == readResult.entryClassCounts[originalDex.name]) {
                emptiedDexNames += originalDex.name
            }
            stripped > 0
        }.keys.mapTo(HashSet()) { it.name }
        logger.info("Stripped ${modifiedOriginalDescriptors.size} modified classes from ${strippedDexNames.size} original DEX files")
        releaseAllDexMappings()

        // The verifier checks classes across every DEX file, while the result holds only those that changed.
        dexWorkingDir.listFiles { it.isFile && it.name !in emptiedDexNames }!!
            .forEach { it.safelyMoveTo(dexOutputDir.resolve(it.name)) }
        config.verifier.verifyDexDirectory(dexOutputDir)

        if (emptiedDexNames.isNotEmpty()) {
            dexFilesComplete = true
            logger.info("Removing ${emptiedDexNames.size} DEX files left without classes")
            dexOutputDir.listFiles { it.isFile }!!.sortedBy(::dexIndex).forEachIndexed { index, dexFile ->
                dexFile.safelyMoveTo(dexOutputDir.resolve(getDexName(index)))
            }
        }

        return dexOutputDir.listFiles { it.isFile && (dexFilesComplete || it.name in strippedDexNames || it.name !in originalDexNames) }!!
            .sorted()
            .mapTo(LinkedHashSet()) { dexFile -> PatcherResult.PatchedDexFile(dexFile.name, dexFile.inputStream()) }
    }

    /**
     * Writes a copy of each extension DEX file that has classes that patches did not change, without
     * its other classes, and removes the copied classes from [classesForNewDex].
     * The copy keeps a class only if it is still the class that the extension DEX file added,
     * which is not the case for a class that patches made mutable or replaced.
     *
     * @return The written copies.
     */
    private fun copyUnchangedExtensionClasses(classesForNewDex: MutableList<ClassDef>): List<File> {
        if (extensionDexFiles.isEmpty()) return emptyList()

        val outputDir = config.patchedFiles.resolve("extensionDex").apply { deleteRecursively(); mkdirs() }
        val copiedClasses = Collections.newSetFromMap(IdentityHashMap<ClassDef, Boolean>())

        val copies = extensionDexFiles.mapIndexedNotNull { index, extensionDexFile ->
            val unchangedClasses = extensionDexFile.addedClasses.filter { classDef ->
                // Desugared library classes are written to their own DEX files.
                patchClasses.classMap[classDef.type]?.classDef === classDef && !DexReadWrite.isDesugarLib(classDef)
            }
            if (unchangedClasses.isEmpty()) return@mapIndexedNotNull null

            val copy = outputDir.resolve("extension$index.dex")
            val copied = DexReadWrite.writeDexCopy(
                extensionDexFile.bytes,
                extensionDexFile.classTypes,
                unchangedClasses,
                opcodes,
                copy,
            )
            if (!copied) {
                logger.fine { "Extension DEX file $index cannot be copied, writing its classes instead" }
                return@mapIndexedNotNull null
            }

            copiedClasses += unchangedClasses
            copy
        }
        extensionDexFiles.clear()

        if (copiedClasses.isNotEmpty()) {
            classesForNewDex.removeAll(copiedClasses)
            logger.info("Copied ${copiedClasses.size} unchanged extension classes to ${copies.size} DEX files")
        }

        return copies
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
    private fun compileStripSafe(task: TaskProgress): Set<PatcherResult.PatchedDexFile> {
        val (modifiedOriginalDescriptors, classesForNewDex)
                = getModifiedOriginalDescriptorsAndClassesForNewDex()

        dexOutputDir.apply { deleteRecursively(); mkdirs() }

        // 1. Write modified + new classes through DexPool first so we know how many files they produce.
        var newDexCount = 0
        if (classesForNewDex.isNotEmpty()) {
            logger.info("Writing ${classesForNewDex.size} modified/new classes to new DEX files")
            DexReadWrite.writeMultiDexFile(dexOutputDir, classesForNewDex, opcodes, -1, null, task)
            newDexCount = dexOutputDir.listFiles { it.isFile }!!.size
        }

        // 2. For each original DEX file, either pass through or rebuild without modified classes.
        // Iterate over a snapshot: releaseDexMapping() removes entries from originalDexMappings
        // while this loop runs, which would otherwise throw ConcurrentModificationException.
        originalDexMappings.keys.toList().forEachIndexed { i, originalDex ->
            val entryDescriptors = classDescriptorsByEntry[originalDex.name] ?: emptySet()
            val hasModifiedClasses = entryDescriptors.any { it in modifiedOriginalDescriptors }

            if (!hasModifiedClasses) {
                // No modified classes — copy the original file as-is.
                logger.fine { "Passing through unmodified DEX: ${originalDex.name}" }
                // Release the mapping before renaming so the file is not locked on Windows.
                releaseDexMapping(originalDex)
                originalDex.safelyMoveTo(dexOutputDir.resolve(getDexName(newDexCount)))
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
                        tempDir, unmodifiedClasses, opcodes, -1, null, task
                    )
                    // This file's classes have been read; release its mapping.
                    releaseDexMapping(originalDex)
                    rebuiltFiles.forEach { rebuiltFile ->
                        val newName = getDexName(newDexCount)
                        rebuiltFile.safelyMoveTo(dexOutputDir.resolve(newName))
                        logger.fine("${originalDex.name} -> $newName")
                        newDexCount++
                    }
                } else {
                    logger.info("Skipping DEX: ${originalDex.name} (all classes were modified)")
                    // All classes in this DEX were modified — no file needed.
                    releaseDexMapping(originalDex)
                }
            }
        }

        // Release any remaining mappings (e.g. for empty original DEX directories).
        releaseAllDexMappings()

        logMutableClassCount()
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
            // Original classes receive ordinals 0 until originalClassCount, and replaced classes keep the ordinal of the class they replaced.
            val inOriginalClassDescriptors = entry.ordinal < originalClassCount

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

    /**
     * Log how many classes were upgraded to [MutableClass] by patches. Call before [PatchClasses.close].
     */
    private fun logMutableClassCount() {
        val total = patchClasses.classMap.size
        val mutable = patchClasses.classMap.values.count { it.classDef is MutableClass }
        logger.info("Changed $mutable of $total classes to mutable")

        // A patch that calls mutableClassDefBy on every class from classDefForEach
        // uses far more memory than needed and can run out of memory on device.
        if (validate && mutable * 2 > total) {
            logger.warning(
                "More than half of all classes were changed to mutable ($mutable of $total). " +
                    "A patch may be calling mutableClassDefBy for every class instead of only the classes it changes"
            )
        }
    }

    private fun dexIndex(dexFile: File) = dexFile.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: 1

    internal fun getDexName(index: Int): String = if (index == 0) "classes.dex" else "classes${index + 1}.dex"

    override fun close() {
        decodeOnFirstUse = false
        releaseAllDexMappings()
        extensionDexFiles.clear()
        classes.close()
    }
}
