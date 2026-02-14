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
import app.morphe.patcher.resource.AaptMacroProcessor
import app.morphe.patcher.resource.PackageRenamingProcessor
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.ResourceIdProcessor
import app.morphe.patcher.util.Document
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger
import kotlin.io.resolve


/**
 * A context for patches containing the current state of resources.
 *
 * @param packageMetadata The [PackageMetadata] of the target apk.
 */
class ResourcePatchContext internal constructor(
    val packageMetadata: PackageMetadata,
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources?> {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    /**
     * Read a document from an [InputStream].
     */
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     */
    fun document(path: String) = Document(get(path))
    fun document(path: String, packageName: String) = Document(get(path, packageName))

    /**
     * Set of resources from [PatcherConfig.apkFiles] to delete.
     */
    private val deleteResources = mutableSetOf<String>()

    private val packageDirectories = mutableMapOf<String, File>()
    private val modifiedResources = mutableSetOf<File>()
    private val addedResources = mutableSetOf<File>()

    /**
     * Decode resources of [PatcherConfig.apkFile].
     *
     * @param mode The [ResourceMode] to use.
     */
    internal fun decodeResources(mode: ResourceMode) =
        with(packageMetadata.apkInfo) {
            config.initializeTemporaryFilesDirectories()

            val xmlDecoder = ApkModuleXmlDecoder(this)
            xmlDecoder.setKeepResPath(false)
            if (mode == ResourceMode.FULL) {
                logger.info("Decoding resources")

                xmlDecoder.decode(config.apkFiles)

                // Delete all the dex files so they don't get built into the final resources.apk.
                config.apkFiles.resolve("dex").deleteRecursively()

                // Update ARSCLib package metadata so the resources will be accessible under the correct package name.
                config.apkFiles.resolve("resources").listFiles { it.isDirectory }?.forEach { dir ->
                    val packageJson = JSONObject(dir.resolve("package.json"))
                    val packageName = packageJson.getString("package_name")
                    packageDirectories[packageName] = dir
                }
            } else {
                logger.info("Decoding app manifest")

                xmlDecoder.decodeAndroidManifest(config.apkFiles)
            }

            val manifest = this.androidManifest
            packageMetadata.let { metadata ->
                metadata.packageName = manifest.packageName
                metadata.packageVersion = manifest.versionName ?: manifest.versionCode.toString()
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

        val resources = config.patchedFiles.resolve("resources").also { it.mkdirs() }

        val resourcesApkFile =
            if (config.resourceMode == ResourceMode.FULL) {
                resources.resolve("resources.apk").apply {
                    val newPackageName = document("AndroidManifest.xml").use { manifest ->
                        val manifestNode = manifest.getElementsByTagName("manifest").item(0) as Element
                        manifestNode.getAttribute("package")
                    }
                    val originalPackageName = packageMetadata.packageName

                    PublicXmlManager(document("res/values/public.xml")).use { publicXmlManager ->
                        PackageRenamingProcessor(
                            this@ResourcePatchContext::get,
                            this@ResourcePatchContext::document,
                            publicXmlManager,
                            packageDirectories,
                            originalPackageName,
                            newPackageName
                        ).process()

                        // Post process all aapt:attr macros in XML files.
                        // TODO: We should only need to do this in new files, have a way of tracking those.
                        AaptMacroProcessor(
                            this@ResourcePatchContext::get,
                            modifiedResources,
                            addedResources
                        ).process()

                        // Process all XMLs to ensure we have IDs generated for each one.
                        ResourceIdProcessor(
                            this@ResourcePatchContext::get,
                            this@ResourcePatchContext::document,
                            publicXmlManager,
                            modifiedResources,
                            addedResources
                        ).process()
                    }

                    logger.info("Writing resource APK")
                    val encoder = ApkModuleXmlEncoder()

                    val loadedModule = encoder.apkModule
                    loadedModule.setPreferredFramework(packageMetadata.apkInfo.androidFrameworkVersion)
                    packageMetadata.apkInfo.loadedFrameworks.forEach {
                        loadedModule.addExternalFramework(it)
                    }
                    encoder.scanDirectory(config.apkFiles)
                    loadedModule.writeApk(resources.resolve("resources.apk"))
                    loadedModule.close()
                }
            } else {
                null
            }

        // FIXME: All of this stuff is handled by arsclib using metadata files. Clean this up.
        return PatcherResult.PatchedResources(
            resourcesApkFile,
            null,
            emptySet(), //packageMetadata.apkInfo.uncompressedFiles,
            deleteResources,
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
    ): File {
        return get(path, packageMetadata.packageName)
    }

    operator fun get(
        path: String,
        packageName: String
    ): File {
        if (path == "AndroidManifest.xml") {
            return config.apkFiles.resolve(path)
        } else {
            val retval = packageDirectories[packageName]!!.resolve(path)
            if (path != "res/values/public.xml" && path != "res/values/ids.xml") {
                // Only add files that aren't the manifest, public.xml, or ids.xml, because these will always be modified anyway.
                modifiedResources.add(retval)
            }
            return retval
        }
    }

    fun addFile(destPath: String, srcFile: File) {
        val destFile = packageDirectories[packageMetadata.packageName]!!.resolve(destPath)
        addedResources.add(destFile)
        Files.copy(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * @param name The name of the file to delete.
     */
    fun delete(name: String) = deleteResources.add(name)

    /**
     * How to handle resources decoding and compiling.
     */
    internal enum class ResourceMode {
        /**
         * Decode and compile all resources.
         */
        FULL,

        /**
         * Only extract resources from the APK.
         * The AndroidManifest.xml and resources inside /res are not decoded or compiled.
         */
        RAW_ONLY,

        /**
         * Do not decode or compile any resources.
         */
        NONE,
    }
}