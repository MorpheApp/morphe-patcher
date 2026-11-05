/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("DEPRECATION")

package app.morphe.patcher.patch

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.resource.ResourceIds
import app.morphe.patcher.resource.hasResourceId
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.resource.xml.TextXmlDocument
import app.morphe.patcher.util.Document
import app.morphe.patcher.xml.ResourceXml
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File
import java.io.InputStream

// The functions of this file work on the APK being patched. Only one APK is ever patched at a time,
// so they need no context and can be called from anywhere, such as from the lambdas of an instruction
// filter or from a function called by a patch. They are only available while an APK is being patched,
// so they cannot be called when a patch or a fingerprint is declared.

private val currentContext: PatcherContext
    get() = PatcherContext.current ?: throw PatchException(
        "This is only available while an APK is being patched. If this is from declaring a patch or a " +
            "fingerprint, then use a lambda so that it is evaluated when patching, such as " +
            "newInstance(type = { OtherFingerprint.originalClassDef.type })"
    )

/**
 * The state of the bytecode of the APK being patched.
 */
internal val currentBytecode: BytecodePatchContext
    get() = currentContext.bytecodeContext

/**
 * The state of the resources of the APK being patched.
 */
internal val currentResources: ResourcePatchContext
    get() = currentContext.resourceContext

/**
 * The context of the bytecode of the APK being patched, for code that still needs one.
 */
@Deprecated("Only for calling code that needs a context until that code is changed to the global functions.")
val bytecodeContext: BytecodePatchContext
    get() = currentBytecode

/**
 * The context of the resources of the APK being patched, for code that still needs one.
 */
@Deprecated("Only for calling code that needs a context until that code is changed to the global functions.")
val resourceContext: ResourcePatchContext
    get() = currentResources

//
// The APK being patched.
//

/**
 * The path to the file workspace directory.
 * This directory has write access and can be used for temporary files.
 */
val fileWorkspace: File
    get() = currentContext.fileWorkspace

/**
 * If extra validation checks are done, such as when patching with the `--validate` CLI argument.
 * Patches can use this to run their own optional checks that are too slow or too strict
 * to always run for the end user.
 *
 * @see PatcherConfig.validate
 */
val validate: Boolean
    get() = currentBytecode.validate

/**
 * The metadata of the APK being patched.
 */
val packageMetadata: PackageMetadata
    get() = currentContext.packageMetadata

//
// Classes.
//

/**
 * Find a class by its type.
 *
 * @param classType The full classname.
 * @return An immutable instance of the class type.
 * @throws PatchException If the class does not exist.
 * @see mutableClassDefBy
 */
fun classDefBy(classType: String): ClassDef = currentBytecode.classDefBy(classType)

/**
 * Find a class with a predicate.
 *
 * @param predicate A predicate to match the class.
 * @return An immutable instance of the class type.
 * @throws PatchException If no class matches.
 * @see mutableClassDefBy
 */
fun classDefBy(predicate: (ClassDef) -> Boolean): ClassDef = currentBytecode.classDefBy(predicate)

/**
 * Find a class by its type.
 *
 * @param classType The full classname.
 * @return An immutable instance of the class type, or null if the class does not exist.
 * @see mutableClassDefByOrNull
 */
fun classDefByOrNull(classType: String): ClassDef? = currentBytecode.classDefByOrNull(classType)

/**
 * Find a class with a predicate.
 *
 * @param predicate A predicate to match the class.
 * @return An immutable instance of the class type, or null if no class matches.
 * @see mutableClassDefByOrNull
 */
fun classDefByOrNull(predicate: (ClassDef) -> Boolean): ClassDef? = currentBytecode.classDefByOrNull(predicate)

/**
 * Find a class by its type, and change it to a mutable class.
 *
 * **Important:** Use this method only if you are going to modify the class or any of its methods.
 * Calling this method without making any modifications can cause out of memory errors.
 *
 * If your code does not modify the class or any of its methods, then instead use [classDefBy].
 *
 * @param classType The full classname.
 * @return A mutable version of the class type.
 * @throws PatchException If the class does not exist.
 */
fun mutableClassDefBy(classType: String): MutableClass = currentBytecode.mutableClassDefBy(classType)

/**
 * Change a class to a mutable class.
 *
 * **Important:** Use this method only if you are going to modify the class or any of its methods.
 * Calling this method without making any modifications can cause out of memory errors.
 *
 * @param classDef An immutable class.
 * @return A mutable version of the class definition.
 */
fun mutableClassDefBy(classDef: ClassDef): MutableClass = currentBytecode.mutableClassDefBy(classDef)

/**
 * Find a class with a predicate, and change it to a mutable class.
 *
 * **Important:** Use this method only if you are going to modify the class or any of its methods.
 * Calling this method without making any modifications can cause out of memory errors.
 *
 * If your code does not modify the class or any of its methods, then instead use [classDefBy].
 *
 * @param predicate A predicate to match the class.
 * @return A mutable class that matches the predicate.
 * @throws PatchException If no class matches.
 */
fun mutableClassDefBy(predicate: (ClassDef) -> Boolean): MutableClass = currentBytecode.mutableClassDefBy(predicate)

/**
 * Find a class by its type, and change it to a mutable class.
 * Returns `null` if the class is not available, such as a built-in Android or Java library.
 *
 * **Important:** Use this method only if you are going to modify the class or any of its methods.
 * Calling this method without making any modifications can cause out of memory errors.
 *
 * If your code does not modify the class or any of its methods, then instead use [classDefByOrNull].
 *
 * @param classType The full classname.
 * @return A mutable version of the class type.
 */
fun mutableClassDefByOrNull(classType: String): MutableClass? = currentBytecode.mutableClassDefByOrNull(classType)

/**
 * Find a class with a predicate, and change it to a mutable class.
 *
 * **Important:** Use this method only if you are going to modify the class or any of its methods.
 * Calling this method without making any modifications can cause out of memory errors.
 *
 * If your code does not modify the class or any of its methods, then instead use [classDefByOrNull].
 *
 * @param predicate A predicate to match the class.
 * @return A mutable class that matches the predicate, or null if no class matches.
 */
fun mutableClassDefByOrNull(predicate: (ClassDef) -> Boolean): MutableClass? =
    currentBytecode.mutableClassDefByOrNull(predicate)

/**
 * Iterate over all classes in the target app and all extension code.
 */
fun classDefForEach(action: (ClassDef) -> Unit) = currentBytecode.classDefForEach(action)

/**
 * @return All classes that contain the string parameter.
 */
fun classDefByStrings(
    literalString: String,
    comparison: StringComparisonType = StringComparisonType.EQUALS,
): List<ClassDef> = currentBytecode.classDefByStrings(literalString, comparison)

/**
 * @return All classes that contain at least 1 string.
 */
fun getAllClassesWithStrings(): List<ClassDef> = currentBytecode.getAllClassesWithStrings()

//
// Resources.
//

/**
 * The resource ids of the APK being patched, read from its resource table on first use.
 *
 * @see resourceId
 * @see hasResourceId
 */
val resourceIds: ResourceIds
    get() = currentResources.resourceIds

/**
 * Read a document from an [InputStream].
 */
fun document(inputStream: InputStream) = Document(inputStream)

/**
 * Read and write a document of the APK being patched.
 * The file is decoded first if it is not decoded yet.
 *
 * @param path The path of the file.
 */
fun document(path: String): Document = currentResources.document(path)

/**
 * Read and write a document of the APK being patched.
 * The file is decoded first if it is not decoded yet.
 *
 * @param path The path of the file.
 * @param packageName The package whose resource to read.
 */
fun document(path: String, packageName: String): Document = currentResources.document(path, packageName)

/**
 * Open an XML file of the APK being patched, such as `res/layout/main.xml`, `res/values/strings.xml` or
 * `AndroidManifest.xml`, to read or change it. Close it when done, which [use] does, to keep the changes.
 *
 * Unlike [document], the file is not decoded to text: a compiled XML file is edited in its binary form, and
 * a values file is edited in the resource table it is generated from, decoding only the resources a patch
 * looks up. A file that was decoded already, or that a patch added, is read and written as text.
 *
 * ```kt
 * resourceXml("res/values/strings.xml").use { strings ->
 *     strings.resource("app_name").text = "Morphe"
 * }
 * ```
 *
 * @param path The path of the file.
 * @param copy False to open a values file only to add or replace resources, without reading the resources
 * it has. A resource added this way replaces the one of the same name, and none is removed. Such a document
 * starts out empty, and can be opened for a configuration the APK does not have, such as
 * `res/values-night/styles.xml`.
 * @param packageName The package whose resource to open. Defaults to the package name of the APK.
 * @throws PatchException If the APK has no such file.
 * @see resourceXmlOrNull
 */
fun resourceXml(path: String, copy: Boolean = true, packageName: String? = null): ResourceXml =
    currentResources.xml(path, copy, packageName) ?: throw PatchException("Could not find the XML file $path")

/**
 * Open an XML file of the APK being patched as [resourceXml] does, or get null if the APK has no such file.
 *
 * @param path The path of the file.
 * @param packageName The package whose resource to open. Defaults to the package name of the APK.
 */
fun resourceXmlOrNull(path: String, packageName: String? = null): ResourceXml? =
    currentResources.xml(path, true, packageName)

/**
 * Read XML text from a stream, usually a file bundled with patches, to copy elements from it into a
 * file of the APK with [app.morphe.patcher.xml.XmlElement.appendCopy] or
 * [app.morphe.patcher.xml.XmlElement.insertCopy].
 *
 * This is not a file of the APK, and it is unrelated to [resourceXml]: values stay exactly as the text
 * writes them, nothing is looked up in the resource table, and compiled XML cannot be read. The document
 * can be changed, but it is never written anywhere. To only read a file of the APK, open it with
 * [resourceXml] and leave it unchanged.
 *
 * ```kt
 * // Adds the buttons a patch bundles to a layout of the app.
 * val stream = inputStreamFromBundledResource("buttons", "layout/buttons.xml")!!
 * stream.use { readXml(it) }.use { buttons ->
 *     resourceXml("res/layout/player.xml").use { layout ->
 *         val container = layout.elementByAttribute("android:id", "@id/controls")
 *         buttons.root.children.forEach { container.appendCopy(it) }
 *     }
 * }
 * ```
 *
 * @param inputStream The XML text, which is read but not closed.
 */
fun readXml(inputStream: InputStream): ResourceXml = TextXmlDocument.read(inputStream)

/**
 * Get a file of the APK being patched.
 *
 * A directory only holds the files that were decoded or added so far, so call [decode] before listing it.
 *
 * @param path The path of the file.
 * @param copy Whether to decode the file from the APK if it is not decoded yet. Pass false to replace or
 * add a file without decoding what it replaces. A values file written this way, such as
 * `res/values-de/strings.xml`, adds its resources to those of the APK or replaces the ones of the same
 * name, but removes none.
 */
fun resourceFile(path: String, copy: Boolean = true): File = currentResources[path, copy]

/**
 * List the files of the APK being patched that can be decoded, by the path they are decoded to,
 * without decoding them. These are the manifest, the resource files under `res/` and the values files
 * `resources.arsc` is decoded to, such as `res/values-de/strings.xml` and `res/values/public.xml`, and
 * the other entries of the APK, such as `assets/data.bin`, which are extracted as they are. Native
 * libraries and DEX files are not listed, but [resourceFile] extracts a native library all the same.
 *
 * The listing describes the input APK, so it does not reflect files a patch has since added or deleted.
 *
 * @param pattern A glob pattern the paths must match. `*` matches any characters within one segment of
 * a path, `**` matches any characters across segments, and `?` matches one character. A pattern that
 * matches a directory matches everything below it, so `res/xml` lists the files in that directory.
 * Lists every file by default.
 * @param packageName The package whose resources to list. Defaults to the package name of the APK.
 * @return The matching paths, sorted.
 */
fun listDecodableFiles(pattern: String = "**", packageName: String? = null): List<String> =
    currentResources.listDecodableFiles(pattern, packageName)

/**
 * Decode the files of the APK being patched that match [patterns] and are not decoded yet, so that a
 * patch can find them, for example to list a directory.
 *
 * A file that was decoded before, or that a patch changed, added or deleted, is left as it is. A values
 * file a patch wrote before it was decoded is decoded and keeps what the patch wrote, which replaces the
 * resources of the same name. Calling this when every resource was decoded up front does nothing more.
 *
 * ```kt
 * // Every layout of every configuration.
 * decode("res/layout*").forEach { path ->
 *     document(path).use { document -> }
 * }
 * ```
 *
 * @param patterns Glob patterns the paths must match, see [listDecodableFiles]. `**` decodes everything.
 * @param packageName The package whose resources to decode. Defaults to the package name of the APK.
 * @return The paths of the matching files, sorted. These include files patches added, but not the ones
 * they deleted.
 */
fun decode(vararg patterns: String, packageName: String? = null): List<String> =
    currentResources.decode(*patterns, packageName = packageName)

/**
 * List the entries of the APK being patched, without staging them to the working directory.
 *
 * Use this to discover what an APK contains, rather than walking the working directory: not
 * every entry is staged there, and native libraries in particular are left in the archive.
 * Names are archive names, so they can be passed straight to [resourceFile].
 *
 * The listing describes the input APK, so it does not reflect files a patch has since added,
 * changed or deleted.
 *
 * @param prefix Restricts the listing to entries starting with it, e.g. `lib/`.
 */
fun listApkEntries(prefix: String = ""): List<String> = currentResources.listApkEntries(prefix)

/**
 * Mark a file for deletion when the APK is rebuilt.
 *
 * The name is either a decoded resource path such as `res/drawable/icon.png`, or any other
 * entry of the APK being patched as returned by [listApkEntries], such as
 * `lib/x86/libfoo.so` or `assets/data.bin`. Archive entries are excluded from the rebuilt APK
 * staged to the working directory or not, so this works for native
 * libraries too. A directory name, such as `lib/x86/`, deletes everything below it. Deleting a
 * name that matches nothing is a no-op. A file deleted and then recreated with [resourceFile] keeps the
 * recreated content. The manifest and `resources.arsc` cannot be deleted.
 *
 * @param name The name of the file to delete.
 * @param packageName The package name a decoded resource exists in. Defaults to the package name of the APK.
 */
fun deleteResource(name: String, packageName: String? = null) = currentResources.delete(name, packageName)
