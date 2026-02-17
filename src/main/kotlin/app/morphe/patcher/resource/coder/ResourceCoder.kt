/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.resource.ResourceMode
import java.io.File

interface ResourceCoder {
    /**
     * Get package metadata from the APK.
     *
     * @return The package's metadata.
     */
    fun getPackageMetadata(): PackageMetadata

    /**
     * Decode raw resources from the APK into the working directory and update the package metadata.
     *
     * @return The package's metadata.
     */
    fun decodeRaw(): PackageMetadata

    /**
     * Decode resources from the APK into the working directory and update the package metadata.
     *
     * @return The package's metadata.
     */
    fun decodeResources(): PackageMetadata

    /**
     * Encode resources from the working directory into a new APK file.
     *
     * @param outputDir a File object representing the output directory for patched files.
     * @return a File object representing the output file of the compiled APK.
     */
    fun encodeResources(outputDir: File): File

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
     * @return a set of all files that should not be compressed.
     */
    fun getUncompressedFiles(): Set<String>

    /**
     * Get deleted files.
     *
     * @return a set of all files that should be deleted.
     */
    fun getDeletedFiles(): Set<String>

    /**
     * Get a file from the working directory.
     *
     * @param path The path of the file.
     * @param packageName The package name of the file. Defaults to the package name of the APK.
     * @param copy Whether to copy the file from the original APK if it does not exist yet in the working directory.
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
     * Delete a file from the working directory. The file will be tracked for deletion in the final resources.apk.
     *
     * @param path The path of the file to delete, relative to the package directory.
     * @param packageName The package name of the resources bundle this file should be deleted from. Defaults to the package name of the application. The package name should be the original package name before any patches are applied.
     */
    fun deleteFile(path: String, packageName: String? = null)
}