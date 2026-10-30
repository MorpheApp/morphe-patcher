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
import app.morphe.patcher.PatcherBehavior
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.resource.ResourceIds
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.coder.ResourceCoder
import app.morphe.patcher.util.Document
import java.io.Closeable
import java.io.InputStream
import java.util.logging.Logger


/**
 * A context for patches containing the current state of resources.
 *
 * Resources are decoded when patches request them, instead of all before patches execute. The manifest
 * is always decoded. A file a patch reads by its path with [get] or [document] is decoded then, and
 * [decode] decodes every file matching a pattern, which a patch that lists a directory must call first,
 * since a directory only holds the files decoded so far.
 * [listDecodableFiles] lists what can be decoded without decoding it.
 */
class ResourcePatchContext internal constructor(
    private val config: PatcherConfig,
) : PatchContext<PatcherResult.PatchedResources>, Closeable {
    private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

    override val fileWorkspace = config.fileWorkspace

    override val validate = config.validate

    private val resourceCoder: ResourceCoder = ArsclibResourceCoder(config.apkFiles, config.apkFile, config.keepArchitectures)

    /**
     * The resource ids of the APK being patched, read from its resource table on first use.
     * Also reachable without a context through [app.morphe.patcher.resource.resourceId] and
     * [app.morphe.patcher.resource.hasResourceId], which is what [app.morphe.patcher.resourceLiteral] uses.
     */
    val resourceIds: ResourceIds = ResourceIds { resourceCoder.resourceIds() }.also { ResourceIds.current = it }

    val packageMetadata = resourceCoder.getPackageMetadata()

    /**
     * Read a document from an [InputStream].
     */
    @Suppress("unused")
    fun document(inputStream: InputStream) = Document(inputStream)

    /**
     * Read and write documents in the [PatcherConfig.apkFiles].
     * The file is decoded first if it is not decoded yet.
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
    operator fun get(
        path: String,
        copy: Boolean = true,
    ) = resourceCoder.getFile(path, copy = copy)

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
    fun listDecodableFiles(pattern: String = "**", packageName: String? = null) =
        resourceCoder.listDecodableFiles(pattern, packageName)

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
    fun decode(vararg patterns: String, packageName: String? = null) =
        resourceCoder.decode(patterns.toList(), packageName)

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
    fun listApkEntries(prefix: String = "") = resourceCoder.listApkEntries(prefix)

    /**
     * Mark a file for deletion when the APK is rebuilt.
     *
     * The name is either a decoded resource path such as `res/drawable/icon.png`, or any other
     * entry of the APK being patched as returned by [listApkEntries], such as
     * `lib/x86/libfoo.so` or `assets/data.bin`. Archive entries are excluded from the rebuilt APK
     * whether or not they were staged to the working directory, so this works for native
     * libraries too. A directory name, such as `lib/x86/`, deletes everything below it. Deleting a
     * name that matches nothing is a no-op. A file deleted and then recreated with [get] keeps the
     * recreated content. The manifest and `resources.arsc` cannot be deleted.
     *
     * @param name The name of the file to delete.
     * @param packageName The package name a decoded resource exists in. Defaults to the package name of the APK.
     */
    @Suppress("unused")
    fun delete(name: String, packageName: String? = null) = resourceCoder.deleteFile(name, packageName)

    override fun close() {
        if (ResourceIds.current === resourceIds) ResourceIds.current = null
        resourceCoder.close()
    }
}