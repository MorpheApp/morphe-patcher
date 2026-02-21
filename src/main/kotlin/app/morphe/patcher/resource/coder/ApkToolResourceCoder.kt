/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.resource.ResourceMode
import brut.androlib.AaptInvoker
import brut.androlib.ApkDecoder
import brut.androlib.Config
import brut.androlib.apk.ApkInfo
import brut.androlib.apk.UsesFramework
import brut.androlib.res.Framework
import brut.androlib.res.ResourcesDecoder
import brut.androlib.res.decoder.AndroidManifestPullStreamDecoder
import brut.androlib.res.decoder.AndroidManifestResourceParser
import brut.androlib.res.xml.ResXmlUtils
import brut.directory.ExtFile
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

class ApkToolResourceCoder(
    internal val workingDir: File,
    internal val resourceConfig: Config,
    apkFile: File
): ResourceCoder {
    internal val apkInfo = ApkInfo(ExtFile(apkFile))

    private val logger = Logger.getLogger(ApkToolResourceCoder::class.java.name)
    private val deletedResources = mutableSetOf<String>()

    override fun getPackageMetadata(): PackageMetadata {
        val resourcesDecoder = ResourcesDecoder(apkInfo, resourceConfig)

        // Decode manually instead of using resourceDecoder.decodeManifest
        // because it does not support decoding to an OutputStream.
        AndroidManifestPullStreamDecoder(
            AndroidManifestResourceParser(resourcesDecoder.resTable),
            resourcesDecoder.newXmlSerializer(),
        ).decode(
            apkInfo.apkFile.directory.getFileInput("AndroidManifest.xml"),
            // Older Android versions do not support OutputStream.nullOutputStream()
            object : OutputStream() {
                override fun write(b: Int) { // Do nothing.
                }
            },
        )

        /*
         The ResTable if flagged as sparse if the main package is not loaded, which is the case here,
         because ResourcesDecoder.decodeResources loads the main package
         and not XmlPullStreamDecoder.decodeManifest.
         See ARSCDecoder.readTableType for more info.

         Set this to false again to prevent the ResTable from being flagged as sparse falsely.
         */
        apkInfo.sparseResources = false

        // Get the package name and version from the manifest using the XmlPullStreamDecoder.
        // XmlPullStreamDecoder.decodeManifest() sets metadata.apkInfo.
        return PackageMetadata(
            resourcesDecoder.resTable.packageRenamed,
            apkInfo.versionInfo.versionName,
            apkInfo.versionInfo.versionCode
        )
    }

    /**
     * No-op for apktool. Raw resources are extracted directly from the apk.
     */
    override fun decodeRaw(): PackageMetadata {
        return getPackageMetadata()
    }

    override fun decodeResources(): PackageMetadata {
        val resourcesDecoder = ResourcesDecoder(apkInfo, resourceConfig)

        resourcesDecoder.decodeResources(workingDir)
        resourcesDecoder.decodeManifest(workingDir)

        val apkDecoder = ApkDecoder(apkInfo, resourceConfig)
        apkDecoder.recordUncompressedFiles(resourcesDecoder.resFileMapping)

        apkInfo.usesFramework = UsesFramework().apply {
            ids = resourcesDecoder.resTable.listFramePackages().map { it.id }
        }

        return PackageMetadata(
            resourcesDecoder.resTable.packageRenamed,
            apkInfo.versionInfo.versionName,
            apkInfo.versionInfo.versionCode.toString()
        )
    }

    override fun encodeResources(outputDir: File): File {
        logger.info("Writing resource APK")
        return outputDir.resolve("resources.apk").apply {
            // Compile the resources.apk file.
            AaptInvoker(
                apkInfo,
                resourceConfig,
            ).invoke(
                this,
                workingDir.resolve("AndroidManifest.xml").also {
                    ResXmlUtils.fixingPublicAttrsInProviderAttributes(it)
                },
                workingDir.resolve("res"),
                null,
                null,
                apkInfo.usesFramework.let { usesFramework ->
                    usesFramework.ids.map { id ->
                        Framework(resourceConfig).getApkFile(id, usesFramework.tag)
                    }.toTypedArray()
                },
            )
        }
    }

    override fun getOtherResourceFiles(outputDir: File, resourceMode: ResourceMode): File? {
        val otherFiles =
            workingDir.listFiles()!!.filter {
                // Excluded because present in resources.other.
                // TODO: We are reusing config.apkFiles as a temporarily directory for extracting resources.
                //  This is not ideal as it could conflict with files such as the ones that we filter here.
                //  The problem is that ResourcePatchContext#get returns a File relative to config.apkFiles,
                //  and we need to extract files to that directory.
                //  A solution would be to use config.apkFiles as the working directory for the patching process.
                //  Once all patches have been executed, we can move the decoded resources to a new directory.
                //  The filters wouldn't be needed anymore.
                //  For now, we assume that the files we filter here are not needed for the patching process.
                it.name != "AndroidManifest.xml" &&
                        it.name != "res" &&
                        // Generated by Androlib.
                        it.name != "build"
            }

        val otherResourceFiles =
            if (otherFiles.isNotEmpty()) {
                // Move the other resources files.
                outputDir.resolve("other").also { it.mkdirs() }.apply {
                    otherFiles.forEach { file ->
                        Files.move(file.toPath(), resolve(file.name).toPath())
                    }
                }
            } else {
                null
            }

        return otherResourceFiles
    }

    override fun getUncompressedFiles(): Set<String> = apkInfo.doNotCompress?.toSet() ?: emptySet()
    override fun getDeletedFiles(): Set<String> = deletedResources

    /**
     * Get a file from the working directory.
     *
     * @param path The path of the file.
     * @param packageName No-op for ApkToolResourceCoder, as it does not support multiple package resource bundles.
     * @param copy Whether to copy the file from the original APK if it does not exist yet in the working directory.
     */
    override fun getFile(
        path: String,
        packageName: String?,
        copy: Boolean
    ): File = workingDir.resolve(path).apply {
            if (copy && !exists()) {
                val extFileDir = apkInfo.apkFile.directory
                if (extFileDir.containsFile(path) || extFileDir.containsDir(path)) {
                    extFileDir.copyToDir(workingDir, path)
                }
            }
        }
    /**
     * Add a file to the working directory. The file will be tracked for inclusion in the final resources.apk.
     *
     * @param destPath The path of the file to add, relative to the package directory.
     * @param srcFile The file to add.
     * @param packageName No-op for ApkToolResourceCoder, as it does not support multiple package resource bundles.
     * @return a File object representing the copied file.
     */
    override fun addFile(
        destPath: String,
        srcFile: File,
        packageName: String?
    ): File = workingDir.resolve(destPath).apply {
        Files.copy(srcFile.toPath(), this.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun deleteFile(path: String, packageName: String?) {
        deletedResources.add(path)
    }
}