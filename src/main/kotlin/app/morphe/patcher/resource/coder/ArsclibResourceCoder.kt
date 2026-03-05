/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.processor.AaptMacroProcessor
import app.morphe.patcher.resource.processor.StringsXmlEscapeProcessor
import app.morphe.patcher.resource.processor.PackageRenamingProcessor
import app.morphe.patcher.resource.processor.ResourceIdProcessor
import app.morphe.patcher.resource.processor.StringsXmlSanitizeProcessor
import app.morphe.patcher.resource.processor.StringsXmlUnEscapeProcessor
import app.morphe.patcher.util.Document
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.AaptXmlStringDecoder
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.json.JSONObject
import org.w3c.dom.Element
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

class ArsclibResourceCoder(
    internal val workingDir: File,
    internal val apkFile: File
) : ResourceCoder {
    private val logger = Logger.getLogger(ArsclibResourceCoder::class.java.name)

    private val packageDirectories = mutableMapOf<String, File>()
    private val modifiedResources = mutableSetOf<File>()
    private val addedResources = mutableSetOf<File>()

    /**
     * Snapshot of file metadata (modification time and size) captured after decoding resources.
     * Used to detect which files were added or modified between decoding and encoding.
     */
    private data class FileSnapshot(val lastModified: Long, val size: Long)

    private var fileSnapshotCache: Map<File, FileSnapshot> = emptyMap()

    /**
     * Recursively scan the working directory and build a map of file paths to their metadata.
     */
    private fun buildFileSnapshot(): Map<File, FileSnapshot> {
        val snapshot = mutableMapOf<File, FileSnapshot>()
        workingDir.walkTopDown().filter { it.isFile }.forEach { file ->
            snapshot[file] = FileSnapshot(file.lastModified(), file.length())
        }
        return snapshot
    }

    /**
     * Compare the current file state against the cached snapshot to populate
     * [addedResources] and [modifiedResources].
     */
    private fun detectFileChanges() {
        addedResources.clear()
        modifiedResources.clear()

        packageDirectories.forEach { (_, packageDir) ->
            packageDir.resolve("res").walkTopDown().filter { it.isFile }.forEach { file ->
                val relativePath = file.relativeTo(packageDir).invariantSeparatorsPath
                if (excludedPaths.contains(relativePath)) return@forEach

                val cached = fileSnapshotCache[file]
                if (cached == null) {
                    // File did not exist in the snapshot — it was added.
                    addedResources.add(file)
                } else if (file.lastModified() != cached.lastModified || file.length() != cached.size) {
                    // File existed but was changed.
                    modifiedResources.add(file)
                }
            }
        }
    }

    // Exclude these files from being tracked by modification/adding to prevent issues during resource encoding.
    private val excludedPaths = setOf(
        "AndroidManifest.xml",
        "res/values/public.xml",
        "res/values/ids.xml",
    )

    class PackageInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val frameworkVersion: Int,
        val externalFrameworks: MutableList<TableBlock>
    ) : Closeable {
        // No way to reload this once closed. Might not be a real issue though.
        override fun close() {
            externalFrameworks.clear()
        }
    }

    private val lazyPackageInfo = lazy {
        val module = ApkModule.loadApkFile(apkFile)
        val manifest = module.androidManifest
        PackageInfo(
            manifest.packageName,
            manifest.versionName,
            manifest.versionCode.toString(),
            module.androidFrameworkVersion,
            module.loadedFrameworks
        )
    }

    override fun getPackageMetadata(): PackageMetadata {
        return PackageMetadata(
            lazyPackageInfo.value.packageName,
            lazyPackageInfo.value.versionName,
            lazyPackageInfo.value.versionCode
        )
    }

    override fun decodeRaw(): PackageMetadata {
        val apkModule = ApkModule.loadApkFile(apkFile)
        val rawDecoder = ApkModuleRawDecoder(apkModule)
        rawDecoder.decode(workingDir)

        return getPackageMetadata()
    }

    override fun decodeResources(): PackageMetadata {
        val apkModule = ApkModule.loadApkFile(apkFile)
        val xmlDecoder = ApkModuleXmlDecoder(apkModule).also {
            it.setKeepResPath(false)
        }

        xmlDecoder.decode(workingDir)
        xmlDecoder.dexDecoder = null
        xmlDecoder.dexProfileDecoder = null

        // Delete all the dex files so they don't get built into the final resources.apk.
        workingDir.resolve("dex").deleteRecursively()

        // Update ARSCLib package metadata so the resources will be accessible under the correct package name.
        workingDir.resolve("resources").listFiles { it.isDirectory }?.forEach { dir ->
            val packageJson = JSONObject(dir.resolve("package.json"))
            val packageName = packageJson.getString("package_name")
            packageDirectories[packageName] = dir
        }

        StringsXmlSanitizeProcessor(
            this::getFile,
            packageDirectories,
        ).process()

        StringsXmlEscapeProcessor(
            this::getFile,
            packageDirectories,
        ).process()

        // Build a snapshot of all file metadata after decoding, so we can detect
        // which files are added or modified when it's time to encode.
        fileSnapshotCache = buildFileSnapshot()

        return getPackageMetadata()
    }

    override fun encodeResources(outputDir: File): File {
        val outputApk = outputDir.resolve("resources.apk")

        // Detect which files were added or modified since decoding.
        detectFileChanges()

        val newPackageName = Document(getFile("AndroidManifest.xml")).use { manifest ->
            val manifestNode = manifest.getElementsByTagName("manifest").item(0) as Element
            manifestNode.getAttribute("package")
        }
        val originalPackageName = lazyPackageInfo.value.packageName

        PublicXmlManager(getFile("res/values/public.xml")).use { publicXmlManager ->
            StringsXmlUnEscapeProcessor(
                this@ArsclibResourceCoder::getFile,
                packageDirectories,
            ).process()

            PackageRenamingProcessor(
                this@ArsclibResourceCoder::getFile,
                publicXmlManager,
                packageDirectories,
                originalPackageName,
                newPackageName
            ).process()

            // Post process all aapt:attr macros in XML files.
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
        XmlCoder.getInstance().setting = CoderSetting().also {
            it.stringDecoder = AaptXmlStringDecoder()
        }

        val encoder = ApkModuleXmlEncoder()
        encoder.apkModule.use { loadedModule ->
            loadedModule.setPreferredFramework(lazyPackageInfo.value.frameworkVersion)
            lazyPackageInfo.value.externalFrameworks.forEach { loadedModule.addExternalFramework(it) }
            encoder.scanDirectory(workingDir)
            loadedModule.writeApk(outputApk)
        }

        return outputApk
    }

    override fun getOtherResourceFiles(outputDir: File, resourceMode: ResourceMode): File? {
        if (resourceMode == ResourceMode.NONE) return null

        val otherResourcesDir = outputDir.resolve("other")
        otherResourcesDir.mkdirs()

        val otherFiles = mutableMapOf<File, File>()
        packageDirectories.values.forEach { packageDirectory ->
            packageDirectory.listFiles()?.filter {
                // Include any files that were copied to the resources folder root.
                // This is the equivalent of copying to the APK root when using apktool.
                // TODO: This is kind of bad. We should treat the resources folder as a read-only view and have all
                //  modified/added files end up somewhere else.

                // In RAW_ONLY mode, AndroidManifest.xml is not decoded and is named AndroidManifest.xml.bin.
                // We only want to include the manifest in this mode.
                it.isFile && it.name != "package.json" && it.name != "AndroidManifest.xml"
            }?.forEach {
                otherFiles[it] = otherResourcesDir.resolve(it.name)
            }
        }

        // Add all touched files to the other files list in raw only mode since we won't be creating a resources.apk.
        if (resourceMode == ResourceMode.RAW_ONLY) {
            (addedResources + modifiedResources).forEach {
                val path = it.absolutePath.replace(workingDir.absolutePath, "")
                if (path.startsWith("/root/")) {
                    otherFiles[it] = otherResourcesDir.resolve(path.replace("/root/", ""))
                } else {
                    val subPath = path.substringAfter("/resources/").substringAfter("/")
                    otherFiles[it] = otherResourcesDir.resolve(subPath)
                }
            }

            val binaryManifest = workingDir.resolve("AndroidManifest.xml.bin")
            val modifiedManifest = workingDir.resolve("AndroidManifest.xml")
            if (binaryManifest.exists()) {
                otherFiles[binaryManifest] = modifiedManifest
            }
        }

        return if (otherFiles.isNotEmpty()) {
            otherFiles.forEach { (src, dest) ->
                dest.parentFile.mkdirs()
                Files.move(src.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            otherResourcesDir
        } else {
            null
        }
    }

    /**
     * No-op, this is already handled by arsclib during encoding.
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
     * @param copy No-op for backwards compatibility with APKTool. All files from the APK are always available.
     * @return a File object representing the desired file.
     */
    override fun getFile(
        path: String,
        packageName: String?,
        copy: Boolean,
    ): File {
        val pkgName = packageName ?: lazyPackageInfo.value.packageName

        val retval: File

        if (path == "res" || path.startsWith("res/") || path == "package.json") {
            retval = packageDirectories[pkgName]?.resolve(path) ?: throw PatchException("Package $pkgName not found")
        } else if (path == "AndroidManifest.xml") {
            // TODO: Doesn't handle modifications to binary AndroidManifest.xml, but then again neither does apktool in raw mode.
            retval = workingDir.resolve(path)
        } else {
            retval = workingDir.resolve("root").resolve(path)
        }

        return retval
    }

    /**
     * Add a file to the working directory.
     *
     * @param destPath The path of the file to add, relative to the package directory.
     * @param srcFile The file to add.
     * @param packageName The package name of the resources bundle this file should be added to. Defaults to the package name of the application. The package name should be the original package name before any patches are applied.
     * @return a File object representing the copied file.
     */
    override fun addFile(destPath: String, srcFile: File, packageName: String?): File {
        val pkgName = packageName ?: lazyPackageInfo.value.packageName
        val destFile =
            packageDirectories[pkgName]?.resolve(destPath) ?: throw PatchException("Package $pkgName not found")
        Files.copy(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        return destFile
    }

    override fun deleteFile(path: String, packageName: String?) {
        val pkgName = packageName ?: lazyPackageInfo.value.packageName
        val file = packageDirectories[pkgName]?.resolve(path) ?: throw PatchException("Package $pkgName not found")

        Files.deleteIfExists(file.toPath())
    }
}