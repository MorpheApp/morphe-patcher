package app.morphe.patcher

import app.morphe.patcher.profile.ProfileEntries
import java.io.File
import java.io.InputStream

/**
 * The result of a patcher.
 *
 * @param dexFiles The patched dex files.
 * @param resources The patched resources.
 * @param dexFilesComplete Whether [dexFiles] replaces every dex file of the input,
 *   rather than only those it names.
 * @param storeDexUncompressed Whether the [dexFiles] are stored uncompressed in the patched APK, see
 *   [app.morphe.patcher.apk.storesDexUncompressed].
 * @param retainsV1Signature Whether the patched APK keeps the v1 signature files of the input, see
 *   [app.morphe.patcher.patch.retainOriginalV1Signature].
 * @param retainedV2Signature The v2 signature of the input, which the patched APK hands to the signer to keep,
 *   see [app.morphe.patcher.patch.retainOriginalV2Signature].
 * @param profileEntries The baseline profile entries that replace those of the input APK, if any do.
 */
@Suppress("MemberVisibilityCanBePrivate")
class PatcherResult internal constructor(
    val dexFiles: Set<PatchedDexFile>,
    val resources: PatchedResources,
    internal val dexFilesComplete: Boolean = true,
    internal val storeDexUncompressed: Boolean = false,
    internal val retainsV1Signature: Boolean = false,
    internal val retainedV2Signature: ByteArray? = null,
    internal val profileEntries: ProfileEntries? = null,
) {

    /**
     * A dex file.
     *
     * @param name The original name of the dex file.
     * @param stream The dex file as [InputStream].
     */
    class PatchedDexFile internal constructor(val name: String, val stream: InputStream)

    /**
     * The resources of a patched apk.
     *
     * @param resourcesApk The compiled resources.apk file.
     * @param otherResources The directory containing other resources files.
     * @param doNotCompress List of files that should not be compressed.
     * @param deleteResources List of resources that should be deleted.
     */
    class PatchedResources internal constructor(
        val resourcesApk: File?,
        val otherResources: File?,
        val doNotCompress: Set<String>,
        val deleteResources: Set<String>,
    )
}
