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
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.coder.ApkToolResourceCoder
import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.coder.ResourceCoder
import app.morphe.patcher.util.Document
import java.io.InputStream
import java.util.logging.Logger


/**
 * A context for patches containing the current state of resources.
 *
 * @param packageMetadata The [PackageMetadata] of the target apk.
 */
class ResourcePatchContext internal constructor(
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources?> {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    private val resourceCoder: ResourceCoder = if (config.useArsclib) {
        ArsclibResourceCoder(config.apkFiles, config.apkFile)
    } else {
        ApkToolResourceCoder(config.apkFiles, config.resourceConfig, config.apkFile)
    }

    val packageMetadata = resourceCoder.getPackageMetadata()

    /**
     * Read a document from an [InputStream].
     */
    @Suppress("unused")
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     */
    @Suppress("unused")
    fun document(path: String) = Document(resourceCoder.getFile(path))
    @Suppress("unused")
    fun document(path: String, packageName: String) = Document(resourceCoder.getFile(path, packageName))

    /**
     * Decode resources of [PatcherConfig.apkFile].
     *
     * @param mode The [ResourceMode] to use.
     */
    internal fun decodeResources(mode: ResourceMode): PackageMetadata {
        config.initializeTemporaryFilesDirectories()
        if (mode == ResourceMode.FULL) {
            logger.info("Decoding all resources")
            return resourceCoder.decodeResources()
        } else {
            logger.info("Decoding resources in raw mode")
            return resourceCoder.decodeRaw()
        }
    }

    /**
     * Compile resources in [PatcherConfig.apkFiles].
     *
     * @return The [PatcherResult.PatchedResources].
     */
    @InternalApi
    override fun get(): PatcherResult.PatchedResources? {
        if (config.resourceMode == ResourceMode.NONE) return null

        logger.info("Compiling modified resources")

        val resourcesApkFile = if (config.resourceMode == ResourceMode.FULL) {
            resourceCoder.encodeResources(config.patchedFiles)
        } else {
            null
        }

        // FIXME: All of this stuff is handled by arsclib using metadata files. Clean this up.
        return PatcherResult.PatchedResources(
            resourcesApkFile,
            resourceCoder.getOtherResourceFiles(config.patchedFiles, config.resourceMode),
            resourceCoder.getUncompressedFiles(),
            resourceCoder.getDeletedFiles(),
        )
    }

    /**
     * Get a file from [PatcherConfig.apkFiles].
     *
     * @param path The path of the file.
     * @param copy Whether to copy the file from [PatcherConfig.apkFile] if it does not exist yet in [PatcherConfig.apkFiles].
     */
    operator fun get(
        path: String,
        copy: Boolean = true,
    ) = resourceCoder.getFile(path, copy = copy)

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * @param name The name of the file to delete.
     * @param packageName The package name the file exists in. Defaults to the package name of the APK.
     */
    @Suppress("unused")
    fun delete(name: String, packageName: String? = null) = resourceCoder.deleteFile(name, packageName)
}