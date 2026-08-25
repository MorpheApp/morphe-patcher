/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.CpuArchitecture
import app.morphe.patcher.resource.PathMap
import app.morphe.patcher.resource.PublicXmlManager
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.UncompressedFiles
import app.morphe.patcher.resource.processor.AaptMacroProcessor
import app.morphe.patcher.resource.processor.PackageRenamingProcessor
import app.morphe.patcher.resource.processor.ResourceIdProcessor
import app.morphe.patcher.resource.processor.StringsXmlEscapeProcessor
import app.morphe.patcher.resource.processor.StringsXmlSanitizeProcessor
import app.morphe.patcher.resource.processor.StringsXmlUnEscapeProcessor
import app.morphe.patcher.util.Document
import app.morphe.patcher.util.FileUtils.safelyDelete
import app.morphe.patcher.util.FileUtils.safelyMoveTo
import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.archive.block.ApkSignatureBlock
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.AaptXmlStringDecoder
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.coder.xml.XmlEncodeUtil
import com.reandroid.arsc.value.ResConfig
import com.reandroid.json.JSONObject
import com.reandroid.xml.XMLFactory
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * A mobile country code and a mobile network code are three digits, so a device never reports one
 * above 999. A patch that adds a resource configuration the app must never select on its own uses
 * a code of this range for one of the two.
 */
private val PATCH_MOBILE_CODES = 1000..9999

/**
 * A resource table that uses sparse entries cannot be read below Android 8.
 */
private const val SPARSE_ENTRIES_MIN_SDK = 26

/**
 * Holds the resource configurations of patches while the rest of the table is built.
 * Outside the directories the encoder scans, so that it never sees them.
 */
private const val PATCHED_CONFIGURATIONS_DIRECTORY = "patched-configurations"

internal class ArsclibResourceCoder(
    internal val workingDir: File,
    internal val apkFile: File,
    private val keepArchitectures: Set<CpuArchitecture> = emptySet()
) : ResourceCoder {
    private val logger = Logger.getLogger(ArsclibResourceCoder::class.java.name)

    internal val packageDirectories = mutableMapOf<String, File>()
    internal val otherResourcesRootDirectory = workingDir.resolve("root")
    internal val modifiedResResources = mutableSetOf<File>()
    internal val modifiedBinaryResources = mutableSetOf<File>()

    /**
     * Set of file paths (relative to the APK root e.g: "lib/armeabi-v7a/libfoo.so")
     * that existed at decode time but no longer exist on disk after patches and
     * other transformations have run. Populated by [detectFileChanges] and returned
     * by [getDeletedFiles] so [ApkUtils.applyTo] can exclude them from the rebuilt APK.
     */
    internal val deletedFiles = mutableSetOf<String>()

    /**
     * Snapshot of file metadata (modification time and size) captured after decoding resources.
     * Used to detect which files were added or modified between decoding and encoding.
     */
    internal data class FileSnapshot(val lastModified: Long, val size: Long)
    internal var fileSnapshotCache: Map<File, FileSnapshot> = emptyMap()
    internal var pathMap: PathMap = PathMap.EMPTY

    /**
     * Recursively scan the working directory and build a map of file paths to their metadata.
     */
    internal fun buildFileSnapshot(): Map<File, FileSnapshot> {
        val snapshot = mutableMapOf<File, FileSnapshot>()
        workingDir.resolve("resources").walkTopDown().filter { it.isFile }.forEach { file ->
            snapshot[file] = FileSnapshot(file.lastModified(), file.length())
        }
        workingDir.resolve("root").walkTopDown().filter { it.isFile }.forEach { file ->
            snapshot[file] = FileSnapshot(file.lastModified(), file.length())
        }
        return snapshot
    }

    /**
     * Compare the current file state against the cached snapshot to populate
     * [modifiedResResources] and [modifiedBinaryResources].
     */
    internal fun detectFileChanges() {
        modifiedResResources.clear()
        modifiedBinaryResources.clear()
        deletedFiles.clear()

        packageDirectories.forEach { (_, packageDir) ->
            packageDir.resolve("res").walkTopDown().filter { it.isFile }.forEach { file ->
                val relativePath = file.relativeTo(packageDir).invariantSeparatorsPath
                if (excludedPaths.contains(relativePath)) return@forEach

                val cached = fileSnapshotCache[file]
                if (cached == null || file.lastModified() != cached.lastModified || file.length() != cached.size) {
                    modifiedResResources.add(file)
                }
            }
        }

        otherResourcesRootDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
            val cached = fileSnapshotCache[file]
            if (cached == null || file.lastModified() != cached.lastModified || file.length() != cached.size) {
                modifiedBinaryResources.add(file)
            }
        }

        // Detect files that existed at decode time but are now removed.
        // These need to be communicated to applyTo() so that they're excluded from the rebuilt APK.
        val rootPathPrefix = otherResourcesRootDirectory.absoluteFile.invariantSeparatorsPath
        fileSnapshotCache.keys.forEach { snapshotFile ->
            if (snapshotFile.exists()) return@forEach
            val absPath = snapshotFile.absoluteFile.invariantSeparatorsPath
            if (absPath.startsWith("$rootPathPrefix/")) {
                val relativePath = snapshotFile.relativeTo(otherResourcesRootDirectory).invariantSeparatorsPath
                deletedFiles += relativePath
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
        val signatureBlock: ApkSignatureBlock?
    )

    private val lazyPackageInfo = lazy {
        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifest
            PackageInfo(
                manifest.packageName,
                manifest.versionName,
                manifest.versionCode.toString(),
                module.androidFrameworkVersion,
                module.apkSignatureBlock
            )
        }
    }

    private fun readPathMap(): PathMap {
        val pathMapJsonFile = workingDir.resolve("path-map.json")
        return if (pathMapJsonFile.exists()) {
            PathMap(pathMapJsonFile.readText(Charsets.UTF_8))
        } else {
            PathMap.EMPTY
        }
    }

    override fun getPackageMetadata(): PackageMetadata {
        return PackageMetadata(
            lazyPackageInfo.value.packageName,
            lazyPackageInfo.value.versionName,
            lazyPackageInfo.value.versionCode,
            lazyPackageInfo.value.signatureBlock
        )
    }

    override fun decodeRaw(): PackageMetadata {
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            val rawDecoder = ApkModuleRawDecoder(apkModule)

            rawDecoder.setDexDecoder { _, _ -> }
            rawDecoder.dexProfileDecoder = null
            rawDecoder.decode(workingDir)
        }

        // Build a snapshot of all file metadata after decoding, so we can detect
        // which files are added or modified when it's time to encode.
        fileSnapshotCache = buildFileSnapshot()
        pathMap = readPathMap()

        return getPackageMetadata()
    }

    override fun decodeResources(): PackageMetadata {
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            val xmlDecoder = ApkModuleXmlDecoder(apkModule).also {
                it.setKeepResPath(false)
            }

            xmlDecoder.setDexDecoder { _, _ -> }
            xmlDecoder.dexProfileDecoder = null
            xmlDecoder.decode(workingDir)

            // Update ARSCLib package metadata so the resources will be accessible under the correct package name.
            workingDir.resolve("resources").listFiles { it.isDirectory }?.forEach { dir ->
                val packageJson = JSONObject(dir.resolve("package.json"))
                val packageName = packageJson.getString("package_name")
                packageDirectories[packageName] = dir
            }
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
        pathMap = readPathMap()

        return getPackageMetadata()
    }

    /**
     * Remove native library directories for architectures not in [keepArchitectures].
     * This is a no-op if [keepArchitectures] is empty.
     */
    internal fun stripNativeLibraries() {
        if (keepArchitectures.isNotEmpty()) {
            logger.info("Stripping libs (keeping architectures " +
                    "${keepArchitectures.joinToString(", ") { it.arch }})")

            var strippedLibCount = 0
            otherResourcesRootDirectory.resolve("lib")
                .takeIf { it.exists() }
                ?.listFiles { dir ->
                    dir.isDirectory && CpuArchitecture.valueOfOrNull(dir.name) !in keepArchitectures
                }?.forEach { it ->
                    it.walkTopDown().filter { it.isFile }.forEach { _ -> strippedLibCount++ }
                    it.safelyDelete()
                }

            logger.info("Stripped $strippedLibCount lib files")
        }
    }

    override fun encodeResources(outputDir: File): File {
        val outputApk = outputDir.resolve("resources.apk")

        stripNativeLibraries()

        // TODO: We could potentially remove unused resource splits here as well

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
                modifiedResResources
            ).process()

            // Process all XMLs to ensure we have IDs generated for each one.
            ResourceIdProcessor(
                this@ArsclibResourceCoder::getFile,
                publicXmlManager,
                modifiedResResources
            ).process()
        }

        logger.info("Writing resource APK")
        XmlCoder.getInstance().setting = CoderSetting().also {
            it.stringDecoder = AaptXmlStringDecoder()
        }

        val patchedConfigurations = stashPatchedConfigurations()

        try {
            val encoder = ApkModuleXmlEncoder()
            encoder.apkModule.use { loadedModule ->
                loadedModule.setPreferredFramework(lazyPackageInfo.value.frameworkVersion)
                encoder.scanDirectory(workingDir)
                loadedModule.encodePatchedConfigurations(patchedConfigurations)
                loadedModule.writeApk(outputApk)
            }
        } finally {
            patchedConfigurations.forEach { it.restore() }
            workingDir.resolve(PATCHED_CONFIGURATIONS_DIRECTORY).safelyDelete()
        }

        return outputApk
    }

    /**
     * A resource configuration of a patch, held outside the working directory while the encoder
     * builds the table.
     *
     * @param publicXml The public.xml of the package the configuration belongs to, which is the
     * tag the encoder gives the package it builds from it.
     */
    internal class HeldConfiguration(
        val publicXml: File,
        private val originalDirectory: File,
        private val heldDirectory: File
    ) {
        /**
         * The directory keeps its name while it is held, so the qualifiers of the configuration
         * are still read off it.
         */
        val valuesFiles
            get() = heldDirectory.listFiles { file: File ->
                file.isFile && file.extension == "xml"
            }.orEmpty().asList()

        fun restore() = heldDirectory.safelyMoveTo(originalDirectory)
    }

    /**
     * Moves the resource configurations that patches add out of the directory the encoder scans,
     * to be encoded by [encodePatchedConfigurations] once the rest of the table stands.
     *
     * A configuration is given a dense entry table the moment the encoder creates it, sized to
     * the largest configuration of its type: an offset for every resource of the type, whether
     * this configuration defines it or not. That is a fair trade for the configurations of an
     * app, which are few and mostly populated. A patch can add more than a thousand of them to
     * select a color with, and each defines a handful of resources out of thousands. Building
     * those along with the app costs more memory than the whole rest of the table.
     *
     * A configuration of a patch is recognized by a mobile country or network code that no device
     * can report, which is how a patch keeps the app from selecting one of them on its own.
     */
    internal fun stashPatchedConfigurations(): List<HeldConfiguration> {
        val heldRoot = workingDir.resolve(PATCHED_CONFIGURATIONS_DIRECTORY)
        heldRoot.safelyDelete()

        return packageDirectories.values.flatMap { packageDirectory ->
            val publicXml = packageDirectory.resolve("res/values/public.xml")
            if (!publicXml.isFile) return@flatMap emptyList()

            packageDirectory.resolve("res").listFiles { file: File ->
                file.isDirectory && file.name.startsWith("values-")
            }.orEmpty().filter { valuesDirectory ->
                val config = ResConfig.parse(qualifiersOf(valuesDirectory))
                config.mcc in PATCH_MOBILE_CODES || config.mnc in PATCH_MOBILE_CODES
            }.map { valuesDirectory ->
                val heldDirectory = heldRoot
                    .resolve(packageDirectory.name)
                    .resolve(valuesDirectory.name)

                valuesDirectory.safelyMoveTo(heldDirectory)

                HeldConfiguration(publicXml, valuesDirectory, heldDirectory)
            }
        }.also { held ->
            if (held.isNotEmpty()) {
                logger.info("Holding back ${held.size} resource configurations of patches")
            }
        }
    }

    /**
     * Encodes the configurations [stashPatchedConfigurations] held back, into a table that is
     * already built.
     *
     * A configuration created here carries a sparse offset table, which lists only the resources
     * it defines. It is set while the configuration is still empty, so no entry table is ever
     * built for the resources it leaves out.
     *
     * Below Android 8 the resource system cannot read a sparse table, so those apps keep the
     * dense one and pay for it in memory.
     */
    private fun ApkModule.encodePatchedConfigurations(configurations: List<HeldConfiguration>) {
        if (configurations.isEmpty()) return

        val minSdk = androidManifest.minSdkVersion
        val useSparseEntries = minSdk != null && minSdk >= SPARSE_ENTRIES_MIN_SDK
        if (!useSparseEntries) {
            logger.info("Not using sparse entries, the app supports Android $minSdk")
        }

        val valuesCoder = XmlCoder.getInstance().VALUES_XML
        val encodedPackages = mutableSetOf<PackageBlock>()

        configurations.forEach { configuration ->
            val packageBlock = tableBlock.getPackageBlockByTag(configuration.publicXml)
                ?: throw PatchException(
                    "No resource package was built for ${configuration.publicXml}"
                )

            configuration.valuesFiles.forEach { valuesFile ->
                val resConfig = ResConfig.parse(
                    XmlEncodeUtil.getQualifiersFromValuesXml(valuesFile)
                )
                val specTypePair = packageBlock.getOrCreateSpecTypePair(
                    XmlEncodeUtil.getTypeFromValuesXml(valuesFile)
                )

                val denseEntryCount = specTypePair.highestEntryCount

                val typeBlock = specTypePair.getTypeBlock(resConfig)
                    ?: specTypePair.getOrCreateTypeBlock(resConfig).also {
                        if (useSparseEntries) {
                            it.headerBlock.isSparse = true
                        } else {
                            // The dense table the encoder gives a configuration of its own,
                            // sized to the largest configuration of the type
                            it.ensureEntriesCount(denseEntryCount)
                        }
                    }

                valuesCoder.encode(XMLFactory.newPullParser(valuesFile), typeBlock)
            }

            encodedPackages += packageBlock
        }

        encodedPackages.forEach { packageBlock ->
            packageBlock.sortTypes()
            packageBlock.refresh()
        }
        tableBlock.refresh()

        logger.info(
            "Encoded ${configurations.size} resource configurations of patches " +
                    "(sparse=$useSparseEntries)"
        )
    }

    /**
     * The qualifiers a resource directory carries, in the form the encoder reads them, which
     * keeps the leading separator.
     */
    private fun qualifiersOf(resourceDirectory: File): String {
        val separator = resourceDirectory.name.indexOf('-')
        return if (separator > 0) resourceDirectory.name.substring(separator) else ""
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
            // Detect which files were added or modified since decoding.
            detectFileChanges()

            val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath

            modifiedResResources.forEach {
                val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
                val subPath = path.substringAfter("/resources/").substringAfter("/")
                val unaliasedPath = pathMap.getOriginalName(subPath) ?: subPath
                otherFiles[it] = otherResourcesDir.resolve(unaliasedPath)
            }

            modifiedBinaryResources.forEach {
                val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
                val subPath = path.substringAfter("/root/")
                val unaliasedPath = pathMap.getOriginalName(subPath) ?: subPath
                otherFiles[it] = otherResourcesDir.resolve(unaliasedPath)
            }

            val binaryManifest = workingDir.resolve("AndroidManifest.xml.bin")
            if (binaryManifest.exists()) {
                otherFiles[binaryManifest] = workingDir.resolve("AndroidManifest.xml")
            }
        }

        return if (otherFiles.isNotEmpty()) {
            logger.info("Moving ${otherFiles.size} resource files")
            otherFiles.forEach { (src, dest) ->
                src.safelyMoveTo(dest)
            }
            otherResourcesDir
        } else {
            null
        }
    }

    override fun getUncompressedFiles(resourceMode: ResourceMode): Set<String> {
        val uncompressedJsonFile = workingDir.resolve("uncompressed-files.json")
        if (!uncompressedJsonFile.exists()) return emptySet()

        return UncompressedFiles(uncompressedJsonFile.readText(Charsets.UTF_8), pathMap)
    }

    /**
     * Returns the relative paths (in-zip APK paths, e.g: "lib/armeabi-v7a/libfoo.so")
     * of files that existed at decode time but are no longer present on disk after
     * patches and resource transformations have run. Populated by [detectFileChanges].
     * [PatcherResult] uses this set to exclude entries from the rebuilt apk when
     * assembling the output from the original input.
     *
     * Defensive copy: [close] clears [deletedFiles], and callers commonly hold this result past
     * the enclosing [Patcher] `use` block via [PatcherResult.PatchedResources.deleteResources].
     * Returning the raw reference caused `applyTo` to read an empty set and silently skip every
     * deletion, leaving stripped native libs in the final APK. Adding a `.toSet()` returns an
     * independent snapshot instead of a live reference to [deletedFiles] `.close()` clears the
     * backing field, and `applyTo` typically runs after the [Patcher] `.use` block exits.
     */
    override fun getDeletedFiles(resourceMode: ResourceMode): Set<String> =
        if (resourceMode == ResourceMode.NONE && keepArchitectures.isNotEmpty()) {
            // When no resource patches are provided, stripNativeLibs() never got a chance to run
            // so do the filtering here
            buildSet {
                logger.info(
                    "Stripping libs (keeping architectures " +
                            "${keepArchitectures.joinToString(", ") { it.arch }})"
                )
                var strippedLibCount = 0
                ZFile.openReadOnly(apkFile).use { zFile ->
                    zFile.entries().map { entry ->
                        entry.centralDirectoryHeader.name
                    }.filter { name ->
                        name.startsWith("lib/") && CpuArchitecture.valueOfOrNull(name.split("/")[1]) !in keepArchitectures
                    }.forEach { name ->
                        add(name)
                        strippedLibCount++
                    }
                }
                logger.info("Stripped $strippedLibCount lib files")
            } + deletedFiles
        } else {
            deletedFiles.toSet()
        }

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

        val aliasedPath = pathMap.getAlias(path) ?: path

        val retval = if (aliasedPath == "res" || aliasedPath.startsWith("res/") || aliasedPath == "package.json") {
            packageDirectories[pkgName]?.resolve(aliasedPath) ?: throw PatchException("Package $pkgName not found")
        } else if (aliasedPath == "AndroidManifest.xml") {
            // TODO: Doesn't handle modifications to binary AndroidManifest.xml, but then again neither does apktool in raw mode.
            workingDir.resolve(aliasedPath)
        } else {
            otherResourcesRootDirectory.resolve(aliasedPath)
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

    override fun close() {
        packageDirectories.clear()
        modifiedResResources.clear()
        modifiedBinaryResources.clear()
        deletedFiles.clear()
        fileSnapshotCache = emptyMap()
    }
}
