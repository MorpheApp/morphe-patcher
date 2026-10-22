/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.resource.ResourceMode
import java.io.Closeable
import java.io.File

internal interface ResourceCoder : Closeable {
    /**
     * No-op default implementation. Override to release resources held by the coder.
     */
    override fun close() {}
    /**
     * Get package metadata from the APK.
     *
     * @return The package's metadata.
     */
    fun getPackageMetadata(): PackageMetadata

    /**
     * List the entries of the APK being patched, without reading them or staging them to the
     * working directory.
     *
     * Names are archive names, as [getFile] accepts them. The listing describes the input APK, so
     * it does not reflect files a patch has since added, changed or deleted.
     *
     * @param prefix Restricts the listing to entries starting with it, e.g. `lib/`. Empty lists
     * every entry.
     * @return The matching archive entry names.
     */
    fun listApkEntries(prefix: String = ""): List<String>

    /**
     * The resource ids of the input APK, keyed by `type/name`, read from its resource table.
     */
    fun resourceIds(): Map<String, Long>

    /**
     * Decode raw resources from the APK into the working directory and update the package metadata.
     *
     * @param onRequest Whether to extract nothing up front, but each file when it is requested.
     * @return The package's metadata.
     */
    fun decodeRaw(onRequest: Boolean = false): PackageMetadata

    /**
     * Decode resources from the APK into the working directory and update the package metadata.
     *
     * @param onRequest Whether to decode only the manifest up front, and every other file when it is
     * requested by [getFile] or [decode].
     * @return The package's metadata.
     */
    fun decodeResources(onRequest: Boolean = false): PackageMetadata

    /**
     * List the files of the APK that can be decoded into the working directory, by the path they are
     * decoded to, without decoding them.
     *
     * The listing describes the input APK, so it does not reflect files a patch has since added or deleted.
     *
     * @param pattern A glob pattern the paths must match, see [app.morphe.patcher.resource.PathPatterns].
     * @param packageName The package whose resources to list. Defaults to the package name of the APK.
     * @return The matching paths, sorted.
     */
    fun listDecodableFiles(pattern: String, packageName: String? = null): List<String>

    /**
     * Decode the files of the APK matching [patterns] that are not decoded yet. A file a patch already
     * changed, added or deleted is left alone, and what a patch wrote to a values file before it was
     * decoded is merged into it.
     *
     * @param patterns Glob patterns the paths must match, see [app.morphe.patcher.resource.PathPatterns].
     * @param packageName The package whose resources to decode. Defaults to the package name of the APK.
     * @return The paths of the files in the working directory matching [patterns], sorted.
     */
    fun decode(patterns: Collection<String>, packageName: String? = null): List<String>

    /**
     * Called once patches no longer request files, so that what decoding them needs can be released.
     */
    fun finishDecoding()

    /**
     * Encode resources from the working directory into a new APK file.
     *
     * @param outputDir a File object representing the output directory for patched files.
     * @param omitEntry Whether to leave out an archive entry, as the patched APK replaces or deletes it.
     * @return a File object representing the output file of the compiled APK.
     */
    fun encodeResources(outputDir: File, omitEntry: (String) -> Boolean = { false }): File

    /**
     * Get other resource files.
     *
     * @param outputDir a File object representing the output directory for patched files.
     * @param resourceMode the resource mode that is being used.
     * @return a File object representing the output directory of other resource files.
     */
    fun getOtherResourceFiles(outputDir: File, resourceMode: ResourceMode): File?

    /**
     * Get uncompressed files.
     *
     * @param resourceMode the resource mode that is being used.
     * @return a set of all files that should not be compressed.
     */
    fun getUncompressedFiles(resourceMode: ResourceMode): Set<String>

    /**
     * Get deleted files.
     *
     * @param resourceMode the resource mode that is being used.
     * @return a set of all files that should be deleted.
     */
    fun getDeletedFiles(resourceMode: ResourceMode): Set<String>

    /**
     * Get a file from the working directory.
     *
     * @param path The path of the file.
     * @param packageName The package name of the file. Defaults to the package name of the APK.
     * @param copy Whether to decode the file from the original APK if it is not decoded yet. Only applies
     * when files are decoded on request.
     * @return a File object representing the desired file.
     */
    fun getFile(path: String, packageName: String? = null, copy: Boolean = true): File

    /**
     * Add a file to the working directory. The file will be tracked for inclusion in the final resources.apk.
     *
     * @param destPath The path of the file to add, relative to the package directory.
     * @param srcFile The file to add.
     * @param packageName The package name of the resources bundle this file should be added to. Defaults to the package name of the application. The package name should be the original package name before any patches are applied.
     * @return a File object representing the copied file.
     */
    fun addFile(destPath: String, srcFile: File, packageName: String? = null): File

    /**
     * Delete a decoded resource from the working directory, tracked for deletion in the final APK,
     * or exclude any other archive entry (as named by [listApkEntries]) from the rebuilt APK,
     * whether or not it was staged to the working directory.
     *
     * @param path The path of the decoded resource relative to the package directory, or the archive entry name.
     * @param packageName The package name of the resources bundle a decoded resource should be deleted from. Defaults to the package name of the application. The package name should be the original package name before any patches are applied.
     */
    fun deleteFile(path: String, packageName: String? = null)
}
