/*
 * Copyright 2026 Morphe.
 * https://github.com/morpheapp/morphe-patches
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.processor.AaptMacroProcessor
import app.morphe.patcher.resource.processor.PackageRenamingProcessor
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.processor.ResourceIdProcessor
import app.morphe.patcher.util.Document
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

class ArsclibResourceCoder(
    internal val workingDir: File,
    apkFile: File
) : ResourceCoder {
    internal val apkModule: ApkModule = ApkModule.loadApkFile(apkFile)

    private val logger = Logger.getLogger(ArsclibResourceCoder::class.java.name)

    private val packageDirectories = mutableMapOf<String, File>()
    private val modifiedResources = mutableSetOf<File>()
    private val addedResources = mutableSetOf<File>()

    // Exclude these files from being tracked by modification/adding to prevent issues during resource encoding.
    private val excludedPaths = setOf(
        "AndroidManifest.xml",
        "res/values/public.xml",
        "res/values/ids.xml",
    )

    private val xmlDecoder = ApkModuleXmlDecoder(apkModule).let {
        it.setKeepResPath(false)
        it
    }

    override fun decodeManifest(): PackageMetadata {
        xmlDecoder.decodeAndroidManifest(workingDir)

        val manifest = apkModule.androidManifest
        return PackageMetadata(
            manifest.packageName,
            manifest.versionName ?: manifest.versionCode.toString()
        )
    }

    override fun decodeResources(): PackageMetadata {
        xmlDecoder.decode(workingDir)

        // Delete all the dex files so they don't get built into the final resources.apk.
        workingDir.resolve("dex").deleteRecursively()

        // Update ARSCLib package metadata so the resources will be accessible under the correct package name.
        workingDir.resolve("resources").listFiles { it.isDirectory }?.forEach { dir ->
            val packageJson = JSONObject(dir.resolve("package.json"))
            val packageName = packageJson.getString("package_name")
            packageDirectories[packageName] = dir
        }

        val manifest = apkModule.androidManifest
        return PackageMetadata(
            manifest.packageName,
            manifest.versionName ?: manifest.versionCode.toString()
        )
    }

    override fun encodeResources(outputDir: File): File {
        val outputApk = outputDir.resolve("resources.apk")

        val newPackageName = Document(getFile("AndroidManifest.xml")).use { manifest ->
            val manifestNode = manifest.getElementsByTagName("manifest").item(0) as Element
            manifestNode.getAttribute("package")
        }
        val originalPackageName = apkModule.packageName

        PublicXmlManager(getFile("res/values/public.xml")).use { publicXmlManager ->
            PackageRenamingProcessor(
                this@ArsclibResourceCoder::getFile,
                publicXmlManager,
                packageDirectories,
                originalPackageName,
                newPackageName
            ).process()

            // Post process all aapt:attr macros in XML files.
            // TODO: We should only need to do this in new files, have a way of tracking those.
            AaptMacroProcessor(
                this@ArsclibResourceCoder::getFile,
                modifiedResources,
                addedResources
            ).process()

            // Process all XMLs to ensure we have IDs generated for each one.
            ResourceIdProcessor(
                this@ArsclibResourceCoder::getFile,
                publicXmlManager,
                modifiedResources,
                addedResources
            ).process()
        }

        logger.info("Writing resource APK")
        val encoder = ApkModuleXmlEncoder()

        encoder.apkModule.use { loadedModule ->
            loadedModule.setPreferredFramework(apkModule.androidFrameworkVersion)
            apkModule.loadedFrameworks.forEach {
                loadedModule.addExternalFramework(it)
            }
            encoder.scanDirectory(workingDir)
            loadedModule.writeApk(outputApk)
        }

        return outputApk
    }

    /**
     * No-op, not currently supported by ArsclibResourceCoder.
     */
    override fun getOtherResourceFiles(outputDir: File): File? = null

    /**
     * No-op, not currently supported by ArsclibResourceCoder.
     */
    override fun getUncompressedFiles(): Set<String> = emptySet()

    /**
     * No-op, not currently supported by ArsclibResourceCoder.
     */
    override fun getDeletedFiles(): Set<String> = emptySet()

    /**
     * Get a file from the working directory.
     *
     * @param path The path of the file.
     * @param packageName The package name of the file. Defaults to the package name of the APK.
     * @param copy No-op for backwards compatibility with APKTool. All files are always available from the APK.
     * @return a File object representing the desired file.
     */
    override fun getFile(
        path: String,
        packageName: String?,
        copy: Boolean?,
    ): File {
        val pkgName = packageName ?: apkModule.packageName

        val retval: File
        if (path == "AndroidManifest.xml") {
            retval = workingDir.resolve(path)
        } else {
            retval = packageDirectories[pkgName]?.resolve(path) ?: throw PatchException("Package $pkgName not found")
        }

        if (!excludedPaths.contains(path)) {
            modifiedResources.add(retval)
        }

        return retval
    }

    /**
     * Add a file to the working directory. The file will be tracked for inclusion in the final resources.apk.
     *
     * @param destPath The path of the file to add, relative to the package directory.
     * @param srcFile The file to add.
     * @param packageName The package name of the resources bundle this file should be added to. Defaults to the package name of the application. The package name should be the original package name before any patches are applied.
     * @return a File object representing the copied file.
     */
    override fun addFile(destPath: String, srcFile: File, packageName: String?): File {
        val pkgName = packageName ?: apkModule.packageName
        val destFile =
            packageDirectories[pkgName]?.resolve(destPath) ?: throw PatchException("Package $pkgName not found")
        addedResources.add(destFile)
        if (!excludedPaths.contains(destPath)) {
            modifiedResources.add(destFile)
        }
        Files.copy(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        return destFile
    }

    override fun deleteFile(path: String, packageName: String?) {
        val pkgName = packageName ?: apkModule.packageName
        val file = packageDirectories[pkgName]?.resolve(path) ?: throw PatchException("Package $pkgName not found")

        Files.deleteIfExists(file.toPath())
    }
}