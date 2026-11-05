/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package app.morphe.patcher.patch

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.PatcherBehavior
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.CompressedDexFiles
import app.morphe.patcher.logging.PhaseTimings
import app.morphe.patcher.resource.PathPatterns
import app.morphe.patcher.resource.ResourceIds
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.coder.ResourceCoder
import app.morphe.patcher.util.Document
import app.morphe.patcher.xml.ResourceXml
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.logging.Logger


/**
 * A context for patches containing the current state of resources.
 *
 * Resources are decoded when patches request them, instead of all before patches execute. A file a patch
 * reads by its path with [get] or [document] is decoded then, the manifest included, and
 * [decode] decodes every file matching a pattern, which a patch that lists a directory must call first,
 * since a directory only holds the files decoded so far.
 * [listDecodableFiles] lists what can be decoded without decoding it.
 *
 * Patches do not need a context anymore, since only one APK is ever patched at a time. Use the global
 * functions instead, such as [app.morphe.patcher.patch.document], which work on the state this class
 * still holds. Existing patch bundles are executed with this class, so it cannot be removed.
 */
@Deprecated("Patches do not need a context. Use the global functions, such as document().")
class ResourcePatchContext internal constructor(
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources>, Closeable {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    override val fileWorkspace = config.fileWorkspace

    override val validate = config.validate

    private val resourceCoder: ResourceCoder = ArsclibResourceCoder(
        config.apkFiles,
        config.apkFile,
        config.keepArchitectures,
        progress = config.progress,
    )

    /**
     * The resource ids of the APK being patched, read from its resource table on first use.
     * It is also reachable without a context through [app.morphe.patcher.resource.resourceId] and
     * [app.morphe.patcher.resource.hasResourceId], which is what [app.morphe.patcher.resourceLiteral] uses.
     */
    @Deprecated("Use the global property.", ReplaceWith("resourceIds", "app.morphe.patcher.patch.resourceIds"))
    val resourceIds: ResourceIds = ResourceIds { resourceCoder.resourceIds() }.also { ResourceIds.current = it }

    @Deprecated("Use the global property.", ReplaceWith("packageMetadata", "app.morphe.patcher.patch.packageMetadata"))
    val packageMetadata = resourceCoder.getPackageMetadata()

    /**
     * Read a document from an [InputStream].
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("document(inputStream)", "app.morphe.patcher.patch.document"),
    )
    @Suppress("unused")
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     * The file is decoded first if it is not decoded yet.
     */
    @Deprecated("Use the global function.", ReplaceWith("document(path)", "app.morphe.patcher.patch.document"))
    @Suppress("unused")
    fun document(path: String): Document {
        decodeOnFirstUseIfNeededFor(path)
        return Document(resourceCoder.getFile(path))
    }

    @Deprecated(
        "Use the global function.",
        ReplaceWith("document(path, packageName)", "app.morphe.patcher.patch.document"),
    )
    @Suppress("unused")
    fun document(path: String, packageName: String): Document {
        decodeOnFirstUseIfNeededFor(path)
        return Document(resourceCoder.getFile(path, packageName))
    }

    /**
     * Opens an XML file of the APK as a document.
     *
     * @see app.morphe.patcher.patch.resourceXml
     */
    internal fun xml(path: String, copy: Boolean, packageName: String?): ResourceXml? {
        decodeOnFirstUseIfNeededFor(path)
        return resourceCoder.openXml(path, packageName, copy)
    }

    /**
     * Decode resources of [PatcherConfig.apkFile].
     *
     * @param mode The [ResourceMode] to use.
     */
    internal fun decodeResources(mode: ResourceMode): PackageMetadata {
        val onRequest = config.uses(PatcherBehavior.ON_REQUEST_RESOURCE_DECODING)
        if (mode == ResourceMode.FULL) {
            logger.info(if (onRequest) "Decoding the manifest" else "Decoding all resources")
            return resourceCoder.decodeResources(onRequest)
        } else {
            logger.info(if (onRequest) "Extracting raw resources on request" else "Decoding resources in raw mode")
            return resourceCoder.decodeRaw(onRequest)
        }
    }

    /**
     * Whether resources are decoded when a patch first needs them, instead of before patches execute.
     */
    @Volatile
    internal var decodeOnFirstUse = false

    /**
     * Time spent decoding resources when they were first needed.
     */
    internal var decodeNanos = 0L
        private set

    /**
     * Decodes what a patch needs to reach [path], if resources are decoded when they are first needed.
     */
    private fun decodeOnFirstUseIfNeededFor(path: String) {
        if (decodeOnFirstUse) decodeOnFirstUse(usesDecodedResources = isDecodedResource(path))
    }

    /**
     * Decodes what a patch needs to reach the files matching [patterns], if resources are decoded when
     * they are first needed.
     */
    private fun decodeOnFirstUseIfNeededFor(patterns: Collection<String>) {
        if (!decodeOnFirstUse) return

        val matcher = PathPatterns(patterns)
        val decodedPaths = sequenceOf("AndroidManifest.xml", "package.json").any(matcher::matches)
        decodeOnFirstUse(usesDecodedResources = decodedPaths || matcher.mayMatchBelow("res"))
    }

    /**
     * Whether [path] is reached by decoding resources, rather than by extracting an entry of the APK.
     */
    private fun isDecodedResource(path: String) =
        path == "AndroidManifest.xml" || path == "package.json" || path == "res" || path.startsWith("res/")

    /**
     * Decodes as little as patches need so far. Entries of the APK that are not resources are extracted
     * in raw mode, which keeps the manifest and the resource table as they are. Resources are decoded
     * once a patch needs them, which keeps what was extracted and changed until then.
     * That only works when decoding on request, since otherwise every file is extracted and decoded.
     *
     * @param usesDecodedResources Whether a patch needs decoded resources.
     */
    @Synchronized
    private fun decodeOnFirstUse(usesDecodedResources: Boolean) {
        if (!decodeOnFirstUse) return

        val startNanos = System.nanoTime()
        if (usesDecodedResources || !config.uses(PatcherBehavior.ON_REQUEST_RESOURCE_DECODING)) {
            decodeResources(ResourceMode.FULL)
            finishDecoding()
            config.resourceMode = ResourceMode.FULL
            decodeOnFirstUse = false
        } else if (config.resourceMode == ResourceMode.NONE) {
            decodeResources(ResourceMode.RAW_ONLY)
            config.resourceMode = ResourceMode.RAW_ONLY
        }
        decodeNanos += System.nanoTime() - startNanos
    }

    /**
     * Called once patches no longer request resources, which releases the resource table on small heaps
     * before the DEX files are written.
     */
    internal fun finishDecoding() = resourceCoder.finishDecoding()

    /**
     * Whether the resource APK [get] writes leaves out an archive entry, as the patched APK replaces or
     * deletes it. Set once the DEX files are written.
     */
    internal var omitEntry: (String) -> Boolean = { false }

    /**
     * Compile resources in [PatcherConfig.apkFiles].
     *
     * @return The [PatcherResult.PatchedResources].
     */
    @InternalApi
    override fun get(): PatcherResult.PatchedResources {
        // Patching ended, so nothing needs the resources that were not decoded.
        decodeOnFirstUse = false

        val resourcesApkFile = if (config.resourceMode == ResourceMode.FULL) {
            logger.info("Compiling modified resources")
            resourceCoder.encodeResources(config.patchedFiles, omitEntry)
        } else {
            null
        }

        // FIXME: All of this stuff is handled by arsclib using metadata files. Clean this up.
        return PatcherResult.PatchedResources(
            resourcesApkFile,
            resourceCoder.getOtherResourceFiles(config.patchedFiles, config.resourceMode),
            resourceCoder.getUncompressedFiles(config.resourceMode),
            resourceCoder.getDeletedFiles(config.resourceMode),
        )
    }

    /**
     * Compile resources in [PatcherConfig.apkFiles] and write the patched APK with [dexFiles], as [get]
     * followed by [app.morphe.patcher.apk.ApkUtils.applyTo] would.
     *
     * @param outputApk The file to write the patched APK to.
     * @param dexFiles The patched DEX files, compressed in the background.
     * @param timings Where the steps of writing the APK are measured.
     */
    internal fun writeApk(outputApk: File, dexFiles: CompressedDexFiles, timings: PhaseTimings) {
        if (config.resourceMode == ResourceMode.FULL) logger.info("Compiling modified resources")
        resourceCoder.writeApk(outputApk, config.patchedFiles, config.resourceMode, omitEntry, dexFiles, timings)
    }

    /**
     * Get a file from [PatcherConfig.apkFiles].
     *
     * A directory only holds the files that were decoded or added so far, so call [decode] before listing it.
     *
     * @param path The path of the file.
     * @param copy Whether to decode the file from [PatcherConfig.apkFile] if it is not decoded yet. Pass false
     * to replace or add a file without decoding what it replaces. A values file written this way, such as
     * `res/values-de/strings.xml`, adds its resources to those of the APK or replaces the ones of the same
     * name, but removes none.
     */
    @Deprecated(
        "Use the global function, which has this new name as a global function cannot be an operator.",
        ReplaceWith("resourceFile(path, copy)", "app.morphe.patcher.patch.resourceFile"),
    )
    operator fun get(
        path: String,
        copy: Boolean = true,
    ): File {
        decodeOnFirstUseIfNeededFor(path)
        return resourceCoder.getFile(path, copy = copy)
    }

    /**
     * List the files of the APK being patched that can be decoded, by the path they are decoded to,
     * without decoding them. These are the manifest, the resource files under `res/` and the values files
     * `resources.arsc` is decoded to, such as `res/values-de/strings.xml` and `res/values/public.xml`, and
     * the other entries of the APK, such as `assets/data.bin`, which are extracted as they are. Native
     * libraries and DEX files are not listed, but [get] extracts a native library all the same.
     *
     * The listing describes the input APK, so it does not reflect files a patch has since added or deleted.
     *
     * @param pattern A glob pattern the paths must match. `*` matches any characters within one segment of
     * a path, `**` matches any characters across segments, and `?` matches one character. A pattern that
     * matches a directory matches everything below it, so `res/xml` lists the files in that directory.
     * Lists every file by default.
     * @param packageName The package whose resources to list. Defaults to the package name of the APK.
     * @return The matching paths, sorted.
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("listDecodableFiles(pattern, packageName)", "app.morphe.patcher.patch.listDecodableFiles"),
    )
    fun listDecodableFiles(pattern: String = "**", packageName: String? = null): List<String> {
        decodeOnFirstUseIfNeededFor(listOf(pattern))
        return resourceCoder.listDecodableFiles(pattern, packageName)
    }

    /**
     * Decode the files of the APK being patched that match [patterns] and are not decoded yet, so that a
     * patch can find them in [PatcherConfig.apkFiles], for example to list a directory.
     *
     * A file that was decoded before, or that a patch changed, added or deleted, is left as it is. A values
     * file a patch wrote before it was decoded is decoded and keeps what the patch wrote, which replaces the
     * resources of the same name. Calling this when every resource was decoded up front does nothing more.
     *
     * ```kt
     * // Every layout of every configuration.
     * decode("res/layout*").forEach { path ->
     *     document(path).use { document -> }
     * }
     * ```
     *
     * @param patterns Glob patterns the paths must match, see [listDecodableFiles]. `**` decodes everything.
     * @param packageName The package whose resources to decode. Defaults to the package name of the APK.
     * @return The paths of the files in [PatcherConfig.apkFiles] matching [patterns], sorted. These include
     * files patches added, but not the ones they deleted.
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("decode(*patterns, packageName = packageName)", "app.morphe.patcher.patch.decode"),
    )
    fun decode(vararg patterns: String, packageName: String? = null): List<String> {
        decodeOnFirstUseIfNeededFor(patterns.toList())
        return resourceCoder.decode(patterns.toList(), packageName)
    }

    /**
     * List the entries of the APK being patched, without staging them to the working directory.
     *
     * Use this to discover what an APK contains, rather than walking the working directory: not
     * every entry is staged there, and native libraries in particular are left in the archive.
     * Names are archive names, so they can be passed straight to [get].
     *
     * The listing describes the input APK, so it does not reflect files a patch has since added,
     * changed or deleted.
     *
     * @param prefix Restricts the listing to entries starting with it, e.g. `lib/`.
     */
    @Deprecated(
        "Use the global function.",
        ReplaceWith("listApkEntries(prefix)", "app.morphe.patcher.patch.listApkEntries"),
    )
    fun listApkEntries(prefix: String = "") = resourceCoder.listApkEntries(prefix)

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * The name is either a decoded resource path such as `res/drawable/icon.png`, or any other
     * entry of the APK being patched as returned by [listApkEntries], such as
     * `lib/x86/libfoo.so` or `assets/data.bin`. Archive entries are excluded from the rebuilt APK
     * staged to the working directory or not, so this works for native
     * libraries too. A directory name, such as `lib/x86/`, deletes everything below it. Deleting a
     * name that matches nothing is a no-op. A file deleted and then recreated with [get] keeps the
     * recreated content. The manifest and `resources.arsc` cannot be deleted.
     *
     * @param name The name of the file to delete.
     * @param packageName The package name a decoded resource exists in. Defaults to the package name of the APK.
     */
    @Deprecated(
        "Use the global function, which has this new name as a global delete function would be unclear.",
        ReplaceWith("deleteResource(name, packageName)", "app.morphe.patcher.patch.deleteResource"),
    )
    @Suppress("unused")
    fun delete(name: String, packageName: String? = null) {
        decodeOnFirstUseIfNeededFor(name)
        resourceCoder.deleteFile(name, packageName)
    }

    override fun close() {
        if (ResourceIds.current === resourceIds) ResourceIds.current = null
        resourceCoder.close()
    }
}