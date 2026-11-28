/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.patch.FormFactor
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.DataType
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * What an app declares about itself in the manifest of its base module.
 *
 * Read straight from the archive, so it is available where Android refuses to parse the APK, such as
 * on a device older than the app's [minSdkVersion].
 *
 * @param packageName The package name of the app.
 * @param versionName The version name, or null if the manifest declares none.
 * @param versionCode The version code with `android:versionCodeMajor` in its upper bits, as Android
 *   reports it, or zero if the manifest declares none.
 * @param minSdkVersion The lowest Android API level the app runs on, or null if the manifest declares
 *   none or names a preview release by its codename.
 * @param label The name of the app, or null if it declares none or [of] was not asked to resolve one
 *   kept in its resources.
 * @param formFactor The device the app is built for, see [ApkFormFactor.of].
 */
class ApkManifest internal constructor(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdkVersion: Int?,
    val label: String?,
    val formFactor: FormFactor,
) {
    companion object {
        // android:versionCodeMajor, absent from the public constants of ARSCLib.
        private const val ID_VERSION_CODE_MAJOR = 0x01010576

        /**
         * The manifest of [file], an APK or a split archive such as `.apkm`, `.apks` or `.xapk`, read
         * from its base module.
         *
         * @param resolveLabel Whether to look up a [label] the manifest names by resource. That loads
         *   the resource table of the app, the one costly part of the read.
         *
         * @throws FileNotFoundException If [file] does not exist or is not a file.
         * @throws IOException If [file] cannot be read, holds no manifest of a base module, or its
         *   manifest names no package.
         */
        @JvmStatic
        @JvmOverloads
        @Throws(IOException::class)
        fun of(file: File, resolveLabel: Boolean = false): ApkManifest = BaseModule.read(file) { module ->
            val manifest = module.manifest
            ApkManifest(
                packageName = manifest.packageName ?: throw IOException("No package name in the manifest of: $file"),
                versionName = manifest.versionName,
                versionCode = manifest.longVersionCode(),
                minSdkVersion = manifest.minSdkVersion,
                label = manifest.applicationLabelString
                    ?: if (resolveLabel) manifest.resourceLabel(module::resources) else null,
                formFactor = ApkFormFactor.of(manifest),
            )
        }

        // Android treats the version code as unsigned and keeps the major version above it.
        private fun AndroidManifestBlock.longVersionCode(): Long {
            val major = manifestElement?.searchAttributeByResourceId(ID_VERSION_CODE_MAJOR)
                ?.takeIf { it.dataType == DataType.DEC || it.dataType == DataType.HEX }
                ?.data
                ?: 0
            return (major.toLong() shl 32) or ((versionCode ?: 0).toLong() and 0xffffffffL)
        }

        private fun AndroidManifestBlock.resourceLabel(resources: () -> TableBlock?): String? {
            val labelId = applicationLabelReference ?: return null

            // A broken resource table costs the label, which is optional, rather than the whole read.
            return try {
                resources()?.getResource(labelId)?.resolveReference()?.get()?.resValue
                    ?.takeIf { it.dataType == DataType.STRING }
                    ?.dataAsPoolString?.get()
            } catch (_: Exception) {
                null
            }
        }
    }
}
