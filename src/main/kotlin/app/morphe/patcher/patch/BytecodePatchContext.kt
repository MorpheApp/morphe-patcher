/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.patch

import app.morphe.patcher.*
import app.morphe.patcher.dex.DexReadWrite
import app.morphe.patcher.util.ClassMerger.merge
import app.morphe.patcher.util.MethodNavigator
import app.morphe.patcher.util.PatchClasses
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.Closeable
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
    internal val opcodes: Opcodes

    /**
     * All classes for the target app and any extension classes.
     */
    internal val patchClasses = PatchClasses(
        DexReadWrite.readMultidexFile(config.apkFile).also { opcodes = it.opcodes }.classes
    )

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
        val classes = patchClasses.getAllClassesWithStrings()
        val result = ArrayList<ClassDef>(classes.size)
        for (wrapper in classes) {
            result.add(wrapper.classDef)
        }
        return result
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
     * @return The compiled bytecode.
     */
    @InternalApi
    override fun get(): Set<PatcherResult.PatchedDexFile> {
        logger.info("Compiling patched dex files")

        // Free up memory before compiling the dex files.
        patchClasses.closeStringMap()

        val patchedDexFileResults =
            config.patchedFiles.resolve("dex").also {
                it.deleteRecursively() // Make sure the directory is empty.
                it.mkdirs()
            }.apply {
                DexReadWrite.writeMultiDexFile(
                    this,
                    object : DexFile {
                        override fun getClasses(): Set<ClassDef> {
                            val values = this@BytecodePatchContext.patchClasses.classMap.values
                            return values.mapTo(HashSet(values.size * 3 / 2)) { it.classDef }
                        }

                        override fun getOpcodes() = this@BytecodePatchContext.opcodes
                    },
                    -1,
                    logger
                )
            }.listFiles { it.isFile }!!.map {
                PatcherResult.PatchedDexFile(it.name, it.inputStream())
            }.toSet()

        return patchedDexFileResults
    }

    override fun close() {
        patchClasses.close()
    }
}
