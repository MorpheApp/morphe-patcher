/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.apk.ApkUtils.writeAlignedApk
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.CpuArchitecture
import app.morphe.patcher.resource.PathMap
import app.morphe.patcher.resource.PathPatterns
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
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import com.reandroid.apk.ApkUtil
import com.reandroid.archive.InputSource
import com.reandroid.archive.block.ApkSignatureBlock
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.AaptXmlStringDecoder
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.ResConfig
import com.reandroid.json.JSONObject
import com.reandroid.xml.XMLFactory
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.logging.Level
import java.util.logging.Logger
import com.android.tools.build.apkzlib.zip.ZFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * A mobile country code and a mobile network code are three digits, so a device never reports one
 * above 999. A patch that adds a resource configuration the app must never select on its own uses
 * a code of this range for one of the two.
 */
private val PATCH_MOBILE_CODES = 1000..9999

/**
 * Set to `true` to rebuild the resource table from every decoded values file instead of encoding
 * only the resources patches changed into the table of the input APK.
 */
internal const val FULL_RESOURCE_ENCODE_PROPERTY = "morphe.patcher.fullResourceEncode"

/**
 * The heap below which the parsed resource table is not kept between the resource id lookups of
 * fingerprints and the encoding of the resources.
 */
private const val RETAINED_TABLE_MIN_HEAP = 1536L * 1024 * 1024

/**
 * A resource table that uses sparse entries cannot be read below Android 8.
 */
internal const val SPARSE_ENTRIES_MIN_SDK = 26

/**
 * Holds the resource configurations of patches while the rest of the table is built.
 * Outside the directories the encoder scans, so that it never sees them.
 */
private const val PATCHED_CONFIGURATIONS_DIRECTORY = "patched-configurations"

/**
 * Holds root files a patch changed while the resource apk is built. Outside the directories the
 * encoder scans, so they travel through the other-files route only, instead of being compressed
 * into resources.apk as well.
 */
private const val PATCHED_ROOT_DIRECTORY = "patched-root"

/**
 * Holds what patches wrote to values files before they were decoded, while those are decoded to be
 * merged with it.
 */
private const val PENDING_MERGE_DIRECTORY = "pending-merge"

private const val IDS_XML = "res/values/ids.xml"

/**
 * Stands in the snapshot for a file that was never decoded as it is now: one a patch deleted without it
 * being decoded, so that it reads as deleted, or one merged with what a patch wrote before it was decoded,
 * so that it reads as changed.
 */
private val UNDECODED_SNAPSHOT = ArsclibResourceCoder.FileSnapshot(FileTime.fromMillis(0), FileTime.fromMillis(0), -1, null)

/** The one archive directory left unstaged, because it dominates the APK's size. */
private const val NATIVE_LIBRARY_DIRECTORY = "lib"
private val DEX_ENTRY_NAME = Regex("classes\\d*\\.dex")

/**
 * @param fullResourceEncode Whether to rebuild the resource table from every decoded values file
 * instead of encoding only the resources patches changed into the table of the input APK.
 */
internal class ArsclibResourceCoder(
    internal val workingDir: File,
    internal val apkFile: File,
    private val keepArchitectures: Set<CpuArchitecture> = emptySet(),
    private val fullResourceEncode: Boolean = System.getProperty(FULL_RESOURCE_ENCODE_PROPERTY).toBoolean(),
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
     * Archive entry names of decoded resource files under `res/` a patch deleted. The entries
     * of the table that named them are emptied, and the archive entries are not carried over.
     */
    internal val deletedResourceFiles = mutableSetOf<String>()

    /** Decoded values files a patch deleted, whose resources are gone from their configuration. */
    internal val deletedValuesFiles = mutableSetOf<File>()

    /**
     * Snapshot of file metadata and identity captured after decoding resources.
     * High-resolution timestamps and file keys improve same-size change detection when the filesystem exposes
     * distinguishable metadata, without rereading every decoded resource payload.
     */
    internal class FileSnapshot(
        val creationTime: FileTime,
        val lastModified: FileTime,
        val size: Long,
        val fileKey: Any?,
    )
    internal var fileSnapshotCache: MutableMap<String, FileSnapshot> = mutableMapOf()

    /**
     * Native libraries are left in the input APK instead of being staged to disk:
     * [reuseUnchangedArchiveEntries] carries them into the compiled resource APK without extraction.
     * They are extracted through [getFile] only when requested. Other root entries are staged
     * during decode so patches can find them by walking the directory.
     */
    private val lazilyExtractedRootFiles = mutableMapOf<String, ExtractedRootFile>()

    /** Relocated root files, mapped to the archive entry name they must be written back as. */
    private val relocatedRootFiles = mutableMapOf<String, String>()

    /**
     * Content of a root entry as extracted, for detecting whether a patch went on to change it.
     * Length is compared alongside the hash, which is [java.util.Arrays.hashCode] over the bytes:
     * fast and good enough to spot a patch's edit, but not a cryptographic digest.
     */
    internal data class ExtractedRootFile(val length: Long, val hash: Int)

    /**
     * Files are keyed by a canonical path string rather than by [File], whose equality is a plain
     * path comparison and so misses when the same file is reached by a differently spelled path.
     */
    private fun pathKey(file: File) = file.absoluteFile.invariantSeparatorsPath

    /**
     * The working directory holds *alias* paths (readable, e.g. `res/drawable-mdpi/icon.png`)
     * while the archive holds *original* entry names (often obfuscated, e.g. `res/-5N.png`).
     * [PathMap.getAlias] maps archive name to on-disk alias; [PathMap.getOriginalName] maps back.
     * Everything that crosses that boundary goes through these two helpers.
     */
    private fun archiveNameOf(aliasedPath: String) = pathMap.getOriginalName(aliasedPath) ?: aliasedPath

    private fun aliasOf(archiveName: String) = pathMap.getAlias(archiveName) ?: archiveName

    /**
     * Native library entries removed by [stripNativeLibraries], as in-APK paths.
     * Held separately from [deletedFiles] because [detectFileChanges] clears that set.
     */
    private val strippedLibraries = mutableSetOf<String>()

    /**
     * Archive entries a patch deleted by name through [deleteFile] that are not decoded resources:
     * native libraries, or root entries a patch discovered with [listApkEntries]. Held apart from
     * [deletedFiles] for the same reason as [strippedLibraries].
     */
    private val deletedArchiveEntries = mutableSetOf<String>()

    /** Entry names of the input APK, read once, since [deleteFile] is often called per entry. */
    private val apkEntryNames: Set<String> by lazy {
        ZFile.openReadOnly(apkFile).use { zFile ->
            zFile.entries().mapTo(LinkedHashSet()) { it.centralDirectoryHeader.name }
        }
    }

    /** Paths that must be stored uncompressed regardless of what the input APK did. */
    private val uncompressedOverrides = mutableSetOf<String>()
    internal var pathMap: PathMap = PathMap.EMPTY

    /**
     * Decodes resources when patches request them, or null if every resource was decoded up front.
     */
    internal var onRequestDecoder: OnRequestDecoder? = null
        private set

    /**
     * Entries patches can request in a raw resource mode that extracts them on request, or null if
     * every entry was extracted up front.
     */
    private var rawEntriesOnRequest: Set<String>? = null

    /** How many files were decoded on request. */
    private var decodedOnRequestCount = 0

    /**
     * What was decoded up front, as [listDecodableFiles] lists it: package name to the paths of the
     * package, and the root entries under the empty name.
     */
    private var decodedUpFront: Map<String, List<String>> = emptyMap()

    /**
     * Whether the parsed resource table is kept even on a small heap, because files are decoded from it
     * when patches request them.
     */
    private var holdInputModule = false

    /**
     * Whether the input module is prepared the way the decoder of every resource prepares the module it
     * decodes, so that files decoded from it on request read the same.
     */
    private var validateResourceNames = false
    private var validatedModule: ApkModule? = null

    /**
     * Recursively scan the working directory and build a map of file paths to their metadata.
     */
    internal fun buildFileSnapshot(): MutableMap<String, FileSnapshot> {
        val snapshot = mutableMapOf<String, FileSnapshot>()
        sequenceOf(workingDir.resolve("resources"), otherResourcesRootDirectory).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                snapshot[pathKey(file)] = file.snapshot()
            }
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
        deletedResourceFiles.clear()
        deletedValuesFiles.clear()

        // Sorted, because walk order follows the file system, which is not stable on every one,
        // and new resource ids are numbered in the order of this set.
        packageDirectories.forEach { (_, packageDir) ->
            packageDir.resolve("res").walkTopDown().filter { it.isFile }.filter { file ->
                val relativePath = file.relativeTo(packageDir).invariantSeparatorsPath
                !excludedPaths.contains(relativePath) && file.differsFrom(fileSnapshotCache[pathKey(file)])
            }.sortedBy { it.invariantSeparatorsPath }.forEach { modifiedResResources.add(it) }
        }

        otherResourcesRootDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
            val extracted = lazilyExtractedRootFiles[pathKey(file)]
            if (extracted != null) {
                // Extracted mid-run, so a timestamp comparison is unreliable here; compare the
                // content instead, length first since that settles most edits without a read.
                if (file.length() != extracted.length ||
                    file.contentHashStreaming() != extracted.hash
                ) modifiedBinaryResources.add(file)
                return@forEach
            }
            val cached = fileSnapshotCache[pathKey(file)]
            if (file.differsFrom(cached)) {
                modifiedBinaryResources.add(file)
            }
        }

        // Detect files that existed at decode time but are now removed.
        // These need to be communicated to applyTo() so that they're excluded from the rebuilt APK.
        val rootPathPrefix = otherResourcesRootDirectory.absoluteFile.invariantSeparatorsPath
        fileSnapshotCache.keys.forEach { key ->
            if (File(key).exists()) return@forEach
            val deleted = File(key)
            packageDirectories.values.firstNotNullOfOrNull { deleted.archivePathRelativeToOrNull(it) }?.let {
                if (ApkUtil.isValuesDirectoryName(deleted.parentFile.name, true)) deletedValuesFiles += deleted
                else deletedResourceFiles += it
            }
            if (key.startsWith("$rootPathPrefix/")) {
                // Snapshot keys are absolute while the working directory may be relative
                // (PatcherConfig defaults it to one), and relativising across that throws.
                // The prefix has already matched, so take the remainder of the key.
                val relativePath = key.removePrefix("$rootPathPrefix/")
                // applyTo matches against archive entry names, not on-disk aliases.
                deletedFiles += archiveNameOf(relativePath)
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
        // Null when the manifest declares none of compileSdkVersion, platformBuildVersionCode or
        // targetSdkVersion, as old APKs that only carry minSdkVersion do. ARSCLib then loads its
        // latest framework by itself.
        val frameworkVersion: Int?,
        val signatureBlock: ApkSignatureBlock?
    )

    /**
     * `android:extractNativeLibs` as the input APK declares it. Libraries are not rebuilt into
     * resources.apk anymore, so if a patch flips this the target's own libraries would keep the
     * old compression. [routeNativeLibrariesIfCompressionChanged] handles that case.
     */
    private var originalExtractNativeLibs: Boolean? = null

    private val packageInfo by lazy {
        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifest
            originalExtractNativeLibs = manifest.isExtractNativeLibs
            PackageInfo(
                manifest.packageName,
                manifest.versionName,
                manifest.versionCode.toString(),
                module.androidFrameworkVersion,
                module.apkSignatureBlock
            )
        }
    }

    /**
     * The input APK with its resource table parsed, shared by the resource id lookups of
     * fingerprints and the incremental encoder, which builds the output from it. Parsing the
     * table of a large app takes over a second on a phone, so it is done once and kept until
     * [close].
     */
    private var inputModule: ApkModule? = null

    @Synchronized
    private fun inputModule(): ApkModule = (inputModule ?: ApkModule.loadApkFile(apkFile).also {
        if (it.hasAndroidManifest()) {
            packageInfo.frameworkVersion?.let { version -> it.setPreferredFramework(version) }
        }
        inputModule = it
    }).also { module ->
        // Names that are not valid resource names are renamed, as the decoder of every resource does.
        // The incremental encoder gives the output those same names from public.xml, so encoding into a
        // table that already has them changes nothing.
        if (validateResourceNames && validatedModule !== module) {
            ApkModuleXmlDecoder(module).validateResourceNames()
            validatedModule = module
        }
    }

    /** Hands the module over to a consumer that changes it, so no lookup uses it afterward. */
    @Synchronized
    private fun takeInputModule(): ApkModule = inputModule().also {
        inputModule = null
        validatedModule = null
        onRequestDecoder?.releaseModule()
    }

    /**
     * Releases the parsed table unless the heap can afford to hold it until the resources are
     * encoded. It is parsed again then, which costs about a second on a phone, while holding the
     * table of a large app through the DEX compilation costs a few hundred megabytes right
     * where small heaps run out.
     */
    @Synchronized
    private fun releaseInputModuleUnlessRetainable() {
        if (holdInputModule || Runtime.getRuntime().maxMemory() >= RETAINED_TABLE_MIN_HEAP) return
        inputModule?.close()
        inputModule = null
        // Closing keeps the parsed table, so no reference to the module may remain for it to be freed.
        validatedModule = null
        onRequestDecoder?.releaseModule()
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
            packageInfo.packageName,
            packageInfo.versionName,
            packageInfo.versionCode,
            packageInfo.signatureBlock
        )
    }

    override fun decodeRaw(onRequest: Boolean): PackageMetadata {
        if (onRequest) {
            // Nothing is staged up front: getFile and decode extract what patches ask for.
            ApkModule.loadApkFile(apkFile).use { it.writeUncompressedFiles(workingDir) }
            otherResourcesRootDirectory.mkdirs()
            rawEntriesOnRequest = apkEntryNames.filterTo(HashSet()) { name ->
                !name.endsWith("/") && name != "AndroidManifest.xml" && name != "resources.arsc" &&
                        !name.startsWith("res/") && stagesRootEntry(name)
            }
            fileSnapshotCache = mutableMapOf()
            pathMap = PathMap.EMPTY
            return getPackageMetadata()
        }

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
        decodedUpFront = listDecodedUpFront(includeResources = false)

        return getPackageMetadata()
    }

    override fun decodeResources(onRequest: Boolean): PackageMetadata {
        // A full rebuild of the resource table needs every values file, so it decodes everything.
        if (onRequest && !fullResourceEncode && prepareDecodingOnRequest()) return getPackageMetadata()

        // The decoder renames the resource files of the table it works on to the paths it writes
        // them under, so the module is not the one the encoder builds the output from.
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            val xmlDecoder = object : ApkModuleXmlDecoder(apkModule) {
                override fun extractRootFiles(mainDirectory: File) {
                    var skipped = 0
                    apkModule.inputSources.forEach { source ->
                        if (!stagesRootEntry(source.alias)) {
                            addDecodedPath(source.alias)
                            skipped++
                        }
                    }
                    if (skipped != 0) logger.info("Leaving $skipped native library files in the input APK")
                    super.extractRootFiles(mainDirectory)
                }
            }.also {
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
            { path, pkg -> getFile(path, pkg) },
            packageDirectories,
        ).process()

        StringsXmlEscapeProcessor(
            { path, pkg -> getFile(path, pkg) },
            packageDirectories,
        ).process()

        // Build a snapshot of all file metadata after decoding, so we can detect
        // which files are added or modified when it's time to encode.
        // Native libraries are not staged here, and patches also add files of their own, so
        // make sure the directory exists for both.
        otherResourcesRootDirectory.mkdirs()

        fileSnapshotCache = buildFileSnapshot()
        pathMap = readPathMap()
        decodedUpFront = listDecodedUpFront(includeResources = true)

        return getPackageMetadata()
    }

    /**
     * Prepares decoding resources when patches request them: catalogs what can be decoded and decodes
     * the manifest, which patches read most and is small.
     *
     * @return False if the APK lacks a manifest or a resource table, which only the decoder of every
     * resource handles.
     */
    private fun prepareDecodingOnRequest(): Boolean {
        val module = inputModule()
        if (!module.hasAndroidManifest() || !module.hasTableBlock() || module.tableBlock.size() == 0) {
            logger.info("Decoding all resources, the APK has no manifest or resource table to decode on request")
            return false
        }

        validateResourceNames = true
        holdInputModule = true
        val decoder = OnRequestDecoder(workingDir, ::inputModule)

        decoder.writePackageInfo()
        decoder.writeUncompressedFiles()
        decoder.decodeManifest()

        decoder.packages.values.forEach { decodablePackage ->
            packageDirectories[decodablePackage.name] = decodablePackage.directory
            // The directories exist as when every resource is decoded, so a patch can check for one or
            // write a file into one without decoding anything first.
            decodablePackage.directory.resolve("res").mkdirs()
            decodablePackage.paths.mapNotNullTo(HashSet()) { it.substringBeforeLast('/', "").ifEmpty { null } }
                .forEach { decodablePackage.directory.resolve(it).mkdirs() }
        }
        otherResourcesRootDirectory.mkdirs()

        fileSnapshotCache = mutableMapOf()
        pathMap = decoder.pathMap
        onRequestDecoder = decoder

        logger.info("Decoding resources on request, ${decoder.size} files can be decoded")
        releaseInputModuleUnlessRetainable()
        return true
    }

    /**
     * Lists what the decoder of every resource decoded, from the snapshot it took.
     *
     * @param includeResources Whether the manifest and the resources were decoded, not only root entries.
     */
    private fun listDecodedUpFront(includeResources: Boolean): Map<String, List<String>> = buildMap {
        val rootPrefix = "${otherResourcesRootDirectory.absoluteFile.invariantSeparatorsPath}/"
        put("", fileSnapshotCache.keys.mapNotNull { key ->
            key.takeIf { it.startsWith(rootPrefix) }?.removePrefix(rootPrefix)?.takeUnless { it.startsWith("res/") }
        })

        if (!includeResources) return@buildMap
        packageDirectories.forEach { (packageName, packageDirectory) ->
            val resPrefix = "${packageDirectory.absoluteFile.invariantSeparatorsPath}/"
            put(packageName, listOf("AndroidManifest.xml") + fileSnapshotCache.keys.mapNotNull { key ->
                key.takeIf { it.startsWith("${resPrefix}res/") }?.removePrefix(resPrefix)
            })
        }
    }

    /**
     * Remove native library directories for architectures not in [keepArchitectures].
     * This is a no-op if [keepArchitectures] is empty.
     */
    internal fun stripNativeLibraries() {
        if (keepArchitectures.isEmpty()) return

        logger.info("Stripping libs (keeping architectures " +
                "${keepArchitectures.joinToString(", ") { it.arch }})")

        // Libraries are not staged on disk, so mark them for deletion from the rebuilt APK
        // instead of deleting extracted copies. This is what NONE mode already does.
        for (name in apkEntryNames) {
            val parts = name.split("/")
            if (name.startsWith("$NATIVE_LIBRARY_DIRECTORY/") && parts.size > 1 &&
                CpuArchitecture.valueOfOrNull(parts[1]) !in keepArchitectures
            ) {
                strippedLibraries += name
            }
        }

        // A patch may have pulled one of these in; drop the staged copy so it is not re-added.
        otherResourcesRootDirectory.resolve(NATIVE_LIBRARY_DIRECTORY)
            .takeIf { it.exists() }
            ?.listFiles { dir ->
                dir.isDirectory && CpuArchitecture.valueOfOrNull(dir.name) !in keepArchitectures
            }?.forEach { it.safelyDelete() }

        logger.info("Stripped ${strippedLibraries.size} lib files")
    }

    /**
     * If a patch changed `android:extractNativeLibs`, the native libraries the target APK already
     * holds are compressed the old way. Stage them so they travel through the other-files route
     * and are re-added with the compression the new value calls for.
     */
    private fun routeNativeLibrariesIfCompressionChanged() {
        packageInfo // populates originalExtractNativeLibs
        // Absent means the platform default (true since API 23), so treat it as a value rather
        // than skipping: adding or removing the attribute is exactly the interesting case.
        val original = originalExtractNativeLibs ?: true
        val current = Document(getFile("AndroidManifest.xml")).use { manifest ->
            val node = manifest.getElementsByTagName("application").item(0) as? Element
                ?: return@use null
            node.getAttribute("android:extractNativeLibs").takeIf { it.isNotEmpty() }?.toBooleanStrictOrNull()
        } ?: true
        if (current == original) return

        logger.info("extractNativeLibs changed to $current, restaging native libraries")
        val staging = workingDir.resolve(PATCHED_ROOT_DIRECTORY)
        ZFile.openReadOnly(apkFile).use { zFile ->
            zFile.entries().forEach entries@{ entry ->
                val name = entry.centralDirectoryHeader.name
                if (!name.startsWith("$NATIVE_LIBRARY_DIRECTORY/") || name.endsWith("/") ||
                    name in strippedLibraries || name in deletedArchiveEntries) return@entries
                // Straight into the staging directory: these only need to reach the output,
                // no patch is going to read them.
                val destination = resolveInside(staging, name) ?: return@entries
                if (!destination.exists()) {
                    destination.parentFile?.mkdirs()
                    zFile.get(name)?.open()?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (destination.exists()) {
                    relocatedRootFiles[pathKey(destination)] = name
                    if (!current) uncompressedOverrides += name
                }
            }
        }
    }

    /**
     * Move root files a patch changed out of the directory the encoder scans, so they are
     * written back through the other-files route rather than compressed into the resource apk as
     * well. Unchanged files a patch merely read are dropped, since the target APK still holds
     * them; unchanged files staged during decode are left in place for the encoder.
     */
    private fun relocateChangedRootFiles() {
        val staging = workingDir.resolve(PATCHED_ROOT_DIRECTORY)

        // An extraction a patch only read is already correct in the target apk.
        val changed = modifiedBinaryResources.mapTo(mutableSetOf()) { pathKey(it) }
        lazilyExtractedRootFiles.keys
            .filter { it !in changed }
            .forEach { File(it).safelyDelete() }

        modifiedBinaryResources.toList().forEach { file ->
            if (!file.exists()) return@forEach
            val aliased = file.relativeTo(otherResourcesRootDirectory).invariantSeparatorsPath
            val destination = staging.resolve(aliased)
            destination.parentFile?.mkdirs()
            file.safelyMoveTo(destination)
            relocatedRootFiles[pathKey(destination)] = archiveNameOf(aliased)
            modifiedBinaryResources -= file
        }
    }

    override fun encodeResources(outputDir: File, omitEntry: (String) -> Boolean): File {
        val outputApk = outputDir.resolve("resources.apk")

        stripNativeLibraries()

        // TODO: We could potentially remove unused resource splits here as well

        // Detect which files were added or modified since decoding.
        detectFileChanges()

        routeNativeLibrariesIfCompressionChanged()
        relocateChangedRootFiles()
        prepareBookkeepingFiles()

        val newPackageName = Document(getFile("AndroidManifest.xml")).use { manifest ->
            val manifestNode = manifest.getElementsByTagName("manifest").item(0) as Element
            manifestNode.getAttribute("package")
        }
        val originalPackageName = packageInfo.packageName
        val packageRenamed = originalPackageName != newPackageName

        val incremental = !fullResourceEncode

        // The incremental encoder re-encodes only the files patches changed, so only those need
        // the processing the encoder expects. The full rebuild reads every file.
        // These look up files they found or create, so none of them asks to decode one.
        val unescaper = StringsXmlUnEscapeProcessor({ path, pkg -> getFile(path, pkg, false) }, packageDirectories)
        val renamer = PackageRenamingProcessor(
            { path, pkg -> getFile(path, pkg, false) },
            packageDirectories,
            originalPackageName,
            newPackageName,
        )
        val changedFiles = modifiedResResources.toList()

        val publicIds = PublicXmlManager(getFile("res/values/public.xml", null, false)).use { publicXmlManager ->
            unescaper.process(if (incremental) changedFiles else unescaper.stringsFiles())

            renamer.renameDeclarations(publicXmlManager)
            modifiedResResources += renamer.process(if (incremental) changedFiles else renamer.resourceXmlFiles())

            // Post process all aapt:attr macros in XML files.
            AaptMacroProcessor(
                { path -> getFile(path, null, false) },
                modifiedResResources
            ).process()

            // Process all XMLs to ensure we have IDs generated for each one.
            ResourceIdProcessor(
                { path -> getFile(path, null, false) },
                publicXmlManager,
                modifiedResResources
            ).process()

            publicXmlManager.getDefinedIds()
        }

        logger.info("Writing resource APK")
        XmlCoder.getInstance().setting = CoderSetting().also {
            it.stringDecoder = AaptXmlStringDecoder()
        }

        fun fallBack(cause: Throwable) {
            logger.log(
                Level.WARNING,
                "Encoding the changed resources into the resource table failed, rebuilding the table: $cause",
                cause
            )
            // The rebuild reads every values file, so whatever was not decoded on request is decoded now.
            onRequestDecoder?.packages?.values?.forEach { decodeOnRequest(it, it.paths.toList()) }

            // Finish the processing the incremental path limited to the changed files.
            unescaper.process(unescaper.stringsFiles() - changedFiles.toSet())
            modifiedResResources += renamer.process()
        }

        if (incremental) {
            try {
                return encodeResourcesIncrementally(outputApk, publicIds, originalPackageName, newPackageName, omitEntry)
            } catch (exception: Exception) {
                fallBack(exception)
            } catch (error: LinkageError) {
                // A host may run this against an ARSCLib that lacks something this path calls.
                fallBack(error)
            }
        }

        return encodeResourcesFully(outputApk, packageRenamed, omitEntry)
    }

    /**
     * Makes sure the files the encoder reads to find resource ids exist. Decoding every resource writes
     * them, while decoding on request writes them only now: `public.xml` of every package from the
     * resource table, and `ids.xml` empty, as only the ids patches declare in it matter.
     */
    private fun prepareBookkeepingFiles() {
        onRequestDecoder?.let { decoder ->
            decoder.packages.values.forEach { decodeOnRequest(it, listOf(PUBLIC_XML)) }
            logger.info("Decoded $decodedOnRequestCount of ${decoder.size} files on request")
        }

        val idsXml = getFile(IDS_XML, null, false)
        if (!idsXml.exists()) {
            idsXml.parentFile.mkdirs()
            idsXml.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
    }

    /**
     * Encodes the resources patches changed into the resource table of the input APK, which is
     * then written out with the archive entries it still holds.
     */
    private fun encodeResourcesIncrementally(
        outputApk: File,
        publicIds: Map<Pair<String, String>, Int>,
        originalPackageName: String,
        newPackageName: String,
        omitEntry: (String) -> Boolean,
    ): File {
        takeInputModule().use { module ->
            val encoder = IncrementalResourceEncoder(
                module,
                workingDir,
                packageDirectories,
                archiveNameOf = { file ->
                    packageDirectories.values.firstNotNullOfOrNull { file.archivePathRelativeToOrNull(it) }
                        ?: throw PatchException("$file is not a decoded resource")
                },
                isNewFile = { file -> fileSnapshotCache[pathKey(file)] == null },
                originalNameOf = pathMap::getOriginalName,
                // A values file that was not decoded never held the resources it leaves out.
                isAddition = { file -> fileSnapshotCache[pathKey(file)] == null },
            )

            val scanDuration = measureTime {
                encoder.encode(
                    modifiedResResources,
                    deletedResourceFiles,
                    deletedValuesFiles,
                    packageDirectories.values.flatMap(::patchedConfigurationDirectories),
                    publicIds,
                    originalPackageName,
                    newPackageName,
                )
            }.roundToTenths()

            // A rename needs no rebuild here: compiled resources reference the package by id.
            val droppedEntries = changedArchiveEntries(packageRenamed = false)
                .filter { it !in encoder.encodedEntries }
                .count { module.zipEntryMap.remove(it) != null }
            val omittedEntries = module.omitEntries(omitEntry)
            module.zipEntryMap.autoSortApkFiles()

            logger.info(
                "Reusing ${module.zipEntryMap.listInputSources().size - encoder.encodedEntries.size} " +
                        "unchanged archive entries, rebuilding ${encoder.encodedEntries.size} entries, " +
                        "dropping $droppedEntries entries, leaving out $omittedEntries replaced entries",
            )

            val writeDuration = measureTime {
                module.writeAlignedApk(outputApk)
            }.roundToTenths()

            logger.info("Resource APK scan: $scanDuration write: $writeDuration")
        }

        return outputApk
    }

    /**
     * Rebuilds the resource table from every decoded values file.
     */
    private fun encodeResourcesFully(outputApk: File, packageRenamed: Boolean, omitEntry: (String) -> Boolean): File {
        val patchedConfigurations = stashPatchedConfigurations()

        try {
            val encoder = ApkModuleXmlEncoder()
            encoder.apkModule.use { loadedModule ->
                packageInfo.frameworkVersion?.let { loadedModule.setPreferredFramework(it) }

                val scanDuration = measureTime {
                    encoder.scanDirectory(workingDir)
                    loadedModule.encodePatchedConfigurations(patchedConfigurations)
                }.roundToTenths()

                ApkModule.loadApkFile(apkFile).use { originalModule ->
                    val changedEntries = changedArchiveEntries(packageRenamed)
                    val reusedEntries = reuseUnchangedArchiveEntries(originalModule, loadedModule, changedEntries)
                    val omittedEntries = loadedModule.omitEntries(omitEntry)
                    val rebuiltEntries = loadedModule.zipEntryMap.listInputSources().size - reusedEntries + omittedEntries

                    logger.info(
                        "Resource APK inputs: reusing ${reusedEntries - omittedEntries} unchanged archive entries, " +
                                "rebuilding $rebuiltEntries entries, leaving out $omittedEntries entries the patched APK replaces",
                    )

                    val writeDuration = measureTime {
                        loadedModule.writeAlignedApk(outputApk)
                    }.roundToTenths()

                    logger.info("Resource APK scan: $scanDuration write: $writeDuration")
                }
            }
        } finally {
            patchedConfigurations.forEach { it.restore() }
            workingDir.resolve(PATCHED_CONFIGURATIONS_DIRECTORY).safelyDelete()
        }

        return outputApk
    }

    /**
     * Leaves out the entries of this module that [omitEntry] matches.
     *
     * @return How many entries were left out.
     */
    private fun ApkModule.omitEntries(omitEntry: (String) -> Boolean): Int {
        val omitted = zipEntryMap.listInputSources().filter { source -> omitEntry(source.alias) }
        omitted.forEach { source -> zipEntryMap.remove(source) }
        return omitted.size
    }

    private fun Duration.roundToTenths(): Duration {
        val roundedMs = ((inWholeMilliseconds + 50) / 100) * 100
        return roundedMs.milliseconds
    }

    /**
     * Returns original APK entry names which cannot be reused.
     */
    internal fun changedArchiveEntries(packageRenamed: Boolean = false): Set<String> = buildSet {
        add("AndroidManifest.xml")
        add("resources.arsc")
        addAll(deletedFiles)
        addAll(deletedResourceFiles)
        addAll(strippedLibraries)
        addAll(deletedArchiveEntries)
        addAll(relocatedRootFiles.values)

        modifiedResResources.forEach { file ->
            packageDirectories.values.firstNotNullOfOrNull { packageDirectory ->
                file.archivePathRelativeToOrNull(packageDirectory)
            }?.let(::add)
        }

        modifiedBinaryResources.forEach { file ->
            file.archivePathRelativeToOrNull(otherResourcesRootDirectory)?.let(::add)
        }

        // PackageRenamingProcessor may rewrite resource XMLs which patches did not directly touch.
        // Rebuild all compiled resources when that processor ran, while still reusing unchanged APK-root files.
        if (packageRenamed) {
            packageDirectories.values.forEach { packageDirectory ->
                packageDirectory.resolve("res").walkTopDown().filter { it.isFile }.forEach { file ->
                    file.archivePathRelativeToOrNull(packageDirectory)?.let(::add)
                }
            }
        }
    }

    /**
     * Reuses unchanged archive entries, including root files omitted by the encoder.
     * ARSCLib copies their compressed data without recompressing it.
     */
    internal fun reuseUnchangedArchiveEntries(
        originalModule: ApkModule,
        encodedModule: ApkModule,
        changedEntries: Set<String>,
    ): Int {
        val encodedEntries = encodedModule.zipEntryMap
        var reusedEntries = 0

        originalModule.zipEntryMap.listInputSources().forEach { originalSource: InputSource ->
            val entryName = originalSource.alias
            val alias = aliasOf(entryName)
            // Root entries are not staged when resources are decoded on request, so every one is kept.
            val rootEntry = onRequestDecoder?.let { entryName !in it.resourceArchiveNames }
                ?: (!stagesRootEntry(alias) || pathKey(otherResourcesRootDirectory.resolve(alias)) in fileSnapshotCache)
            if (entryName !in changedEntries && (encodedEntries.contains(entryName) || rootEntry)) {
                encodedEntries.add(originalSource)
                reusedEntries++
            }
        }

        return reusedEntries
    }

    private fun File.archivePathRelativeToOrNull(baseDirectory: File): String? {
        val filePath = absoluteFile.toPath().normalize()
        val basePath = baseDirectory.absoluteFile.toPath().normalize()
        if (!filePath.startsWith(basePath)) return null

        val alias = basePath.relativize(filePath).toString().replace(File.separatorChar, '/')
        return pathMap.getOriginalName(alias) ?: alias
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

            patchedConfigurationDirectories(packageDirectory).map { valuesDirectory ->
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
     * The values directories of the resource configurations patches added to a package, told
     * apart by a mobile country or network code no device reports.
     */
    internal fun patchedConfigurationDirectories(packageDirectory: File): List<File> =
        packageDirectory.resolve("res").listFiles { file: File ->
            file.isDirectory && file.name.startsWith("values-")
        }.orEmpty().filter { valuesDirectory ->
            val config = ResConfig.parse(qualifiersOf(valuesDirectory))
            config.mcc in PATCH_MOBILE_CODES || config.mnc in PATCH_MOBILE_CODES
        }.sortedBy { it.name }

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

        val useSparseEntries = useSparseEntries()
        if (!useSparseEntries) {
            logger.info("Not using sparse entries, the app supports Android ${androidManifest.minSdkVersion}")
        }

        val valuesCoder = XmlCoder.getInstance().VALUES_XML
        val encodedPackages = mutableSetOf<PackageBlock>()

        configurations.forEach { configuration ->
            val packageBlock = tableBlock.getPackageBlockByTag(configuration.publicXml)
                ?: throw PatchException(
                    "No resource package was built for ${configuration.publicXml}"
                )

            configuration.valuesFiles.forEach { valuesFile ->
                val typeBlock = packageBlock.patchedTypeBlock(valuesFile, useSparseEntries)
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

    private fun File.snapshot(): FileSnapshot {
        val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
        return FileSnapshot(
            attributes.creationTime(),
            attributes.lastModifiedTime(),
            attributes.size(),
            attributes.fileKey(),
        )
    }

    private fun File.differsFrom(snapshot: FileSnapshot?): Boolean {
        if (snapshot == null) return true
        val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
        return attributes.creationTime() != snapshot.creationTime ||
                attributes.lastModifiedTime() != snapshot.lastModified ||
                attributes.size() != snapshot.size ||
                attributes.fileKey() != snapshot.fileKey
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

        val workingDirPath = workingDir.absoluteFile.invariantSeparatorsPath

        // Add all touched files to the other files list in raw only mode since we won't be creating a resources.apk.
        if (resourceMode == ResourceMode.RAW_ONLY) {
            // Detect which files were added or modified since decoding.
            detectFileChanges()

            modifiedResResources.forEach {
                val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
                val subPath = path.substringAfter("/resources/").substringAfter("/")
                val unaliasedPath = pathMap.getOriginalName(subPath) ?: subPath
                otherFiles[it] = otherResourcesDir.resolve(unaliasedPath)
            }

            val binaryManifest = workingDir.resolve("AndroidManifest.xml.bin")
            if (binaryManifest.exists()) {
                otherFiles[binaryManifest] = workingDir.resolve("AndroidManifest.xml")
            }
        }

        // Root entries no longer travel inside resources.apk, so anything a patch added or
        // changed under root/ takes the same route raw mode has always used for them.
        // Root files a patch changed: relocated ones in FULL mode, and any still staged
        // under root/ in either mode.
        relocatedRootFiles.forEach { (key, apkPath) ->
            otherFiles[File(key)] = otherResourcesDir.resolve(apkPath)
        }
        modifiedBinaryResources.forEach {
            val path = it.absoluteFile.invariantSeparatorsPath.replace(workingDirPath, "")
            val subPath = path.substringAfter("/root/")
            val unaliasedPath = pathMap.getOriginalName(subPath) ?: subPath
            otherFiles[it] = otherResourcesDir.resolve(unaliasedPath)
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
        if (!uncompressedJsonFile.exists()) return uncompressedOverrides.toSet()

        // Defensive copy: close() clears the backing set, and applyTo commonly runs after the
        // enclosing Patcher use block. Same reason getDeletedFiles copies.
        return UncompressedFiles(
            uncompressedJsonFile.readText(Charsets.UTF_8),
            pathMap,
            uncompressedOverrides.toSet(),
        )
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
                for (name in apkEntryNames) {
                    val parts = name.split("/")
                    if (name.startsWith("$NATIVE_LIBRARY_DIRECTORY/") && parts.size > 1 &&
                        CpuArchitecture.valueOfOrNull(parts[1]) !in keepArchitectures
                    ) {
                        add(name)
                        strippedLibCount++
                    }
                }
                logger.info("Stripped $strippedLibCount lib files")
            } + deletedFiles + strippedLibraries + deletedArchiveEntries
        } else {
            deletedFiles + strippedLibraries + deletedArchiveEntries
        }

    /**
     * Get a file from the working directory.
     *
     * @param path The path of the file.
     * @param packageName The package name of the file. Defaults to the package name of the APK.
     * @param copy Whether to decode the file if it is not decoded yet, when files are decoded on
     * request. Directories are never decoded this way. When everything was decoded up front, a path
     * that is not staged is extracted from [apkFile] regardless, which is what makes native libraries
     * reachable even though [decodeResources] leaves them in the archive.
     * @return a File object representing the desired file.
     */
    override fun getFile(
        path: String,
        packageName: String?,
        copy: Boolean,
    ): File {
        val aliasedPath = pathMap.getAlias(path) ?: path
        val decodesOnRequest = onRequestDecoder != null || rawEntriesOnRequest != null

        val retval = if (aliasedPath == "res" || aliasedPath.startsWith("res/") || aliasedPath == "package.json") {
            // Only resource paths are package scoped, so do not read the manifest for the rest.
            val pkgName = packageName ?: packageInfo.packageName
            val file = packageDirectories[pkgName]?.resolve(aliasedPath) ?: throw PatchException("Package $pkgName not found")
            if (copy) onRequestDecoder?.packages?.get(pkgName)?.let { decodeOnRequest(it, listOf(aliasedPath)) }
            file
        } else if (aliasedPath == "AndroidManifest.xml") {
            // TODO: Doesn't handle modifications to binary AndroidManifest.xml, but then again neither does apktool in raw mode.
            workingDir.resolve(aliasedPath)
        } else {
            otherResourcesRootDirectory.resolve(aliasedPath).also { file ->
                if (!file.exists()) {
                    if (copy || !decodesOnRequest) extractRootEntries(aliasedPath)
                    // Not in the archive either, so a patch is adding it; staging used to
                    // guarantee the directory existed for that.
                    if (!file.exists()) file.parentFile?.mkdirs()
                }
            }
        }

        return retval
    }

    override fun resourceIds(): Map<String, Long> = try {
        inputModule().let { module ->
            if (!module.hasTableBlock()) return@let emptyMap()

            val ids = HashMap<String, Long>(1024, 0.5f)
            module.tableBlock.forEach { packageBlock ->
                packageBlock.listSpecTypePairs().forEach { specTypePair ->
                    specTypePair.forEach { typeBlock ->
                        typeBlock.listEntries(true).forEach { entry ->
                            // Unsigned: ids are 0x7fxxxxxx for the app, so this is a plain Long.
                            ids.putIfAbsent(
                                "${typeBlock.typeName}/${entry.name}",
                                entry.resourceId.toLong() and 0xffffffffL,
                            )
                        }
                    }
                }
            }
            ids
        }
    } finally {
        releaseInputModuleUnlessRetainable()
    }

    override fun listDecodableFiles(pattern: String, packageName: String?): List<String> {
        val pkgName = packageName ?: packageInfo.packageName
        val matcher = PathPatterns(listOf(pattern))

        val paths = onRequestDecoder?.let { decoder ->
            val decodablePackage = decoder.packages[pkgName] ?: throw PatchException("Package $pkgName not found")
            (sequenceOf("AndroidManifest.xml") + decoder.rootEntries.asSequence()).filter(matcher::matches) +
                    decodablePackage.matchingPaths(matcher)
        } ?: (rawEntriesOnRequest?.asSequence()
            ?: (decodedUpFront[pkgName].orEmpty() + decodedUpFront[""].orEmpty()).asSequence()).filter(matcher::matches)

        return paths.sorted().toList()
    }

    override fun decode(patterns: Collection<String>, packageName: String?): List<String> {
        val pkgName = packageName ?: packageInfo.packageName
        val matcher = PathPatterns(patterns)

        onRequestDecoder?.let { decoder ->
            val decodablePackage = decoder.packages[pkgName] ?: throw PatchException("Package $pkgName not found")
            decodeOnRequest(decodablePackage, decodablePackage.matchingPaths(matcher).toList())
            extractRootEntriesOnRequest(decoder.rootEntries.filter(matcher::matches))
        }
        rawEntriesOnRequest?.let { entries -> extractRootEntriesOnRequest(entries.filter(matcher::matches)) }

        return workingFiles(pkgName, matcher).sorted().toList()
    }

    override fun finishDecoding() {
        if (!holdInputModule) return

        // Decoding a file afterward loads the table again, which nothing should need to.
        holdInputModule = false
        releaseInputModuleUnlessRetainable()
    }

    /**
     * Decodes files of a package that are not decoded yet, which are files that are neither in the snapshot,
     * as decoded or deleted files are, nor in the working directory. A values file a patch wrote before it
     * was decoded is decoded as well, and what the patch wrote is merged into it.
     */
    private fun decodeOnRequest(decodablePackage: DecodablePackage, paths: Collection<String>) {
        val decoder = onRequestDecoder ?: return
        val pending = paths.filter { path ->
            val file = decodablePackage.directory.resolve(path)
            path in decodablePackage && pathKey(file) !in fileSnapshotCache &&
                    (!file.exists() || decodablePackage.isValuesFile(path))
        }
        if (pending.isEmpty()) return

        val written = pending.mapNotNull { path ->
            val file = decodablePackage.directory.resolve(path)
            if (!file.isFile) return@mapNotNull null
            val held = workingDir.resolve(PENDING_MERGE_DIRECTORY).resolve(decodablePackage.directory.name).resolve(path)
            held.parentFile.mkdirs()
            file.safelyMoveTo(held)
            file to held
        }

        val decoded = try {
            decoder.decode(decodablePackage, pending)
        } catch (exception: Exception) {
            written.forEach { (file, held) -> held.safelyMoveTo(file) }
            throw exception
        } finally {
            releaseInputModuleUnlessRetainable()
        }

        // Strings are escaped as when decoding every resource, which is the form patches expect.
        val stringsFiles = decoded.filter { it.name == "strings.xml" }
        if (stringsFiles.isNotEmpty()) {
            StringsXmlSanitizeProcessor({ path, pkg -> getFile(path, pkg, false) }, packageDirectories)
                .process(stringsFiles, log = false)
            StringsXmlEscapeProcessor({ path, pkg -> getFile(path, pkg, false) }, packageDirectories)
                .process(stringsFiles, log = false)
        }
        decoded.forEach { fileSnapshotCache[pathKey(it)] = it.snapshot() }

        written.forEach { (file, held) ->
            if (file.isFile) {
                mergeValues(file, held)
                held.safelyDelete()
                // Differs from what was decoded, and is now the whole configuration rather than an addition.
                fileSnapshotCache[pathKey(file)] = UNDECODED_SNAPSHOT
            } else {
                // The resource table has no values for it, so what the patch wrote stays an addition.
                held.safelyMoveTo(file)
            }
        }

        decodedOnRequestCount += pending.size
        logger.fine { "Decoded on request: ${pending.joinToString()}" }
    }

    /**
     * Merges the resources a patch wrote to a values file before it was decoded into the decoded file. A
     * resource the patch wrote replaces the decoded one of the same name, as encoding the patch's file into
     * the decoded resources would have.
     */
    private fun mergeValues(decoded: File, written: File) {
        // <item type="string" name="x"> declares the same resource as <string name="x">.
        fun Element.resourceKey() = "${getAttribute("type").ifEmpty { tagName }}/${getAttribute("name")}"
        fun org.w3c.dom.Node.childElements() =
            (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

        val writtenElements = written.inputStream().use { input ->
            Document(input).documentElement?.childElements().orEmpty()
        }

        Document(decoded).use { document ->
            val resources = document.documentElement
            val replaced = writtenElements.mapTo(HashSet()) { it.resourceKey() }
            resources.childElements().filter { it.resourceKey() in replaced }.forEach { resources.removeChild(it) }
            writtenElements.forEach { resources.appendChild(document.importNode(it, true)) }
        }
    }

    /**
     * Extracts root entries that are not extracted yet, unless a patch deleted them or removed what was
     * extracted.
     */
    private fun extractRootEntriesOnRequest(aliases: Collection<String>) {
        val pending = aliases.filter { alias ->
            val file = otherResourcesRootDirectory.resolve(alias)
            val archiveName = archiveNameOf(alias)
            !file.exists() && pathKey(file) !in fileSnapshotCache &&
                    archiveName !in deletedArchiveEntries && archiveName !in strippedLibraries
        }
        if (pending.isEmpty()) return

        ZFile.openReadOnly(apkFile).use { zFile ->
            pending.forEach { alias -> extractEntry(zFile, archiveNameOf(alias)) }
        }
        decodedOnRequestCount += pending.size
    }

    /**
     * The paths of the files in the working directory a patch can reach with [getFile] that match [matcher].
     */
    private fun workingFiles(packageName: String, matcher: PathPatterns): Sequence<String> {
        val manifest = sequenceOf("AndroidManifest.xml").filter { matcher.matches(it) && workingDir.resolve(it).isFile }

        val resources = packageDirectories[packageName]?.takeIf { matcher.mayMatchBelow("res") }?.let { packageDirectory ->
            matchingFiles(packageDirectory.resolve("res"), "res", matcher)
        }.orEmpty()

        // Root entries named like resources cannot be reached by their name, which reaches the resources.
        val rootFiles = matchingFiles(otherResourcesRootDirectory, "", matcher).filterNot { it.startsWith("res/") }

        return manifest + resources + rootFiles
    }

    /**
     * The paths of the files below [directory] that match [matcher], where [path] is the path of [directory].
     * Only the directories a match can be in are listed, and only the files whose path matches are read, since
     * the working directory holds every resource of a large app when they were all decoded up front.
     */
    private fun matchingFiles(directory: File, path: String, matcher: PathPatterns): Sequence<String> = sequence {
        directory.list()?.forEach { name ->
            val childPath = if (path.isEmpty()) name else "$path/$name"
            val matches = matcher.matches(childPath)
            if (!matches && !matcher.mayMatchBelow(childPath)) return@forEach

            // Most entries are files, so a matching file is told apart with one read of its attributes.
            val child = directory.resolve(name)
            if (matches && child.isFile) {
                yield(childPath)
            } else if (child.isDirectory) {
                yieldAll(matchingFiles(child, childPath, matcher))
            }
        }
    }

    override fun listApkEntries(prefix: String): List<String> =
        apkEntryNames.filter { it.startsWith(prefix) }

    /**
     * Whether a root entry is staged to the working directory during decode. Everything is,
     * except native libraries and DEX files. Native libraries are the bulk of an APK, and nothing
     * enumerates them on disk. DEX files are never written by the decoder either (the DEX decoder
     * is a no-op) and are handled by the bytecode side; declaring them unstaged here lets
     * [reuseUnchangedArchiveEntries] carry them into the compiled resource APK, which is what the
     * output is built from, so they survive when no bytecode was patched.
     * Staging the rest matters because a patch that discovers files by walking the directory can
     * only see what is on disk, and the on-demand extraction in [getFile] cannot serve a walk.
     */
    internal fun stagesRootEntry(alias: String) =
        !alias.startsWith("$NATIVE_LIBRARY_DIRECTORY/") && !DEX_ENTRY_NAME.matches(alias)

    /**
     * Extract a single root entry from the input APK into the working directory, and record it
     * in the snapshot so that [detectFileChanges] treats it as pre-existing rather than added.
     * A patch that goes on to modify or delete it is then detected exactly as before.
     * Entries are looked up in [apkEntryNames] first, since patches adding many new files request
     * paths the archive does not have, and opening a large APK for each of them dominates patching.
     */
    private fun extractRootEntries(aliasedPath: String) {
        // The working directory holds on-disk alias paths, the archive holds original names.
        val apkPath = archiveNameOf(aliasedPath)
        val entries = if (apkPath in apkEntryNames) {
            listOf(apkPath)
        } else {
            // A patch may ask for a directory (and then list it), which staging used to provide.
            val prefix = "${apkPath.trimEnd('/')}/"
            apkEntryNames.filter { it.startsWith(prefix) }
        }
        if (entries.isEmpty()) return

        ZFile.openReadOnly(apkFile).use { zFile ->
            entries.forEach { name -> extractEntry(zFile, name) }
        }
    }

    /**
     * Resolve an archive entry name inside a directory, refusing names that escape it. Archive
     * names come from the input APK, so a crafted entry like `lib/../../x` must not become a
     * write outside the working directory. ARSCLib's own staging sanitizes names when it builds
     * its input sources; this is the equivalent guard for the paths extracted here.
     */
    private fun resolveInside(directory: File, entryName: String): File? {
        val resolved = directory.resolve(entryName).normalize().absoluteFile
        if (!resolved.startsWith(directory.normalize().absoluteFile)) {
            logger.warning("Refusing archive entry escaping the working directory: $entryName")
            return null
        }
        return resolved
    }

    private fun extractEntry(zFile: ZFile, apkPath: String) {
        val entry = zFile.get(apkPath) ?: return
        if (entry.centralDirectoryHeader.name.endsWith("/")) return
        val destination = resolveInside(otherResourcesRootDirectory, aliasOf(apkPath)) ?: return
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        val hash = entry.open().use { input -> destination.copyFromComputingHash(input) }
        if (!destination.exists()) return
        // Hash rather than (mtime, size): a patch usually rewrites the file within the same
        // filesystem timestamp tick, and same-length edits would otherwise read as unchanged.
        lazilyExtractedRootFiles[pathKey(destination)] = ExtractedRootFile(destination.length(), hash)
        fileSnapshotCache[pathKey(destination)] = destination.snapshot()
        logger.fine("Extracted root entry on demand: $apkPath")
    }

    /**
     * Copies [input] into this file while hashing what was written in the same pass, so a root
     * entry as large as a native library or a bundled asset never needs a full second read.
     */
    internal fun File.copyFromComputingHash(input: InputStream): Int {
        var hash = 1
        outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                for (i in 0 until read) hash = 31 * hash + buffer[i]
            }
        }
        return hash
    }

    /** Same result as `readBytes().contentHashCode()`, bounded to one buffer instead of the whole file. */
    internal fun File.contentHashStreaming(): Int {
        var hash = 1
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (i in 0 until read) hash = 31 * hash + buffer[i]
            }
        }
        return hash
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
        val pkgName = packageName ?: packageInfo.packageName
        val destFile =
            packageDirectories[pkgName]?.resolve(destPath) ?: throw PatchException("Package $pkgName not found")
        Files.copy(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        return destFile
    }

    override fun deleteFile(path: String, packageName: String?) {
        val alias = aliasOf(path)

        // Decoded resources are package scoped and live in the working directory; removing the
        // file there is enough, the snapshot diff reports it as deleted.
        if (alias == "res" || alias.startsWith("res/") || alias == "package.json") {
            val pkgName = packageName ?: packageInfo.packageName
            val packageDirectory = packageDirectories[pkgName] ?: throw PatchException("Package $pkgName not found")
            val file = resolveInside(packageDirectory, alias)
                ?: throw PatchException("Refusing to delete \"$path\": it escapes the working directory")
            Files.deleteIfExists(file.toPath())

            // A resource that was never decoded has no copy whose removal the snapshot diff would notice,
            // so it is recorded as one that was decoded and then deleted.
            val decodablePackage = onRequestDecoder?.packages?.get(pkgName)
            if (decodablePackage != null && alias in decodablePackage && pathKey(file) !in fileSnapshotCache) {
                fileSnapshotCache[pathKey(file)] = UNDECODED_SNAPSHOT
            }
            return
        }

        if (alias == "AndroidManifest.xml" || alias == "resources.arsc") {
            throw PatchException("\"$path\" cannot be deleted")
        }

        // Anything else is an archive entry, as named by listApkEntries. A staged copy is removed,
        // but that alone is not enough: native libraries are never staged, and a copy extracted on
        // demand is not in the decode snapshot, so its removal would only discard the extraction
        // and leave the original entry in the output. Record the entry for exclusion instead, the
        // same way stripped native libraries are.
        val staged = resolveInside(otherResourcesRootDirectory, alias)
            ?: throw PatchException("Refusing to delete \"$path\": it escapes the working directory")
        val archiveName = archiveNameOf(alias)

        // A directory, either named with a trailing slash as archives list them or staged as one,
        // stands for everything below it.
        if (alias.endsWith("/") || staged.isDirectory) {
            val prefix = "${archiveName.trimEnd('/')}/"
            val removedStagedCopies = staged.exists() && staged.deleteRecursively()
            val entries = apkEntryNames.filter { it == archiveName || it.startsWith(prefix) }
            deletedArchiveEntries += entries
            if (entries.isEmpty() && !removedStagedCopies) {
                logger.fine { "Nothing to delete for \"$path\": not a decoded resource or an APK entry" }
            }
            return
        }

        val removedStagedCopy = Files.deleteIfExists(staged.toPath())
        if (archiveName in apkEntryNames) {
            deletedArchiveEntries += archiveName
        } else if (!removedStagedCopy) {
            logger.fine { "Nothing to delete for \"$path\": not a decoded resource or an APK entry" }
        }
    }

    override fun close() {
        packageDirectories.clear()
        modifiedResResources.clear()
        modifiedBinaryResources.clear()
        deletedFiles.clear()
        deletedResourceFiles.clear()
        deletedValuesFiles.clear()
        lazilyExtractedRootFiles.clear()
        relocatedRootFiles.clear()
        strippedLibraries.clear()
        deletedArchiveEntries.clear()
        uncompressedOverrides.clear()
        fileSnapshotCache = mutableMapOf()
        onRequestDecoder = null
        rawEntriesOnRequest = null
        decodedUpFront = emptyMap()
        holdInputModule = false
        validatedModule = null
        inputModule?.close()
        inputModule = null
    }
}
