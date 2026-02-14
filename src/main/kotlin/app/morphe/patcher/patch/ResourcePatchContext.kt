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
    private val addedResources = mutableSetOf<String>()

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
                config.apkFiles.resolve("resources").listFiles { it.isDirectory }.forEach { dir ->
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
                    document("AndroidManifest.xml").use { manifest ->
                        val manifestNode = manifest.getElementsByTagName("manifest").item(0) as Element
                        val newPackageName = manifestNode.getAttribute("package")
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
                            AaptMacroProcessor(this@ResourcePatchContext::get, this@ResourcePatchContext::document, addedResources).process()

                            // Process all XMLs to ensure we have IDs generated for each one.
                            ResourceIdProcessor(this@ResourcePatchContext::get, this@ResourcePatchContext::document, publicXmlManager).process()
                        }
                    }

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

        /*
        val otherFiles =
            // TODO: Is this needed still?
            config.apkFiles.listFiles()!!.filter {
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
                resources.resolve("other").also { it.mkdirs() }.apply {
                    otherFiles.forEach { file ->
                        Files.move(file.toPath(), resolve(file.name).toPath())
                    }
                }
            } else {
                null
            }

        */

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
        if (path == "AndroidManifest.xml") {
            return config.apkFiles.resolve(path);
        }
        return get(path, packageMetadata.packageName)
    }

    operator fun get(
        path: String,
        packageName: String
    ): File {
        if (path == "AndroidManifest.xml") {
            return config.apkFiles.resolve(path);
        }

        return packageDirectories[packageName]!!.resolve(path)
    }

    fun addFile(destPath: String, srcFile: File) {
        addedResources.add(destPath)
        val destFile = packageDirectories[packageMetadata.packageName]!!.resolve(destPath)
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