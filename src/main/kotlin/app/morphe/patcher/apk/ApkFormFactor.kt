/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.apk

import app.morphe.patcher.patch.FormFactor
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.DataType
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads which [FormFactor] an original app is built for, so that the patches offered for it can
 * follow the APK rather than the device it is patched on.
 *
 * Only the manifest is read, which keeps it cheap even for the largest apps.
 */
object ApkFormFactor {
    private const val MANIFEST = "AndroidManifest.xml"

    // android:required, absent from the public constants of ARSCLib.
    private const val ID_REQUIRED = 0x0101028e

    private const val FEATURE_WATCH = "android.hardware.type.watch"
    private const val FEATURE_AUTOMOTIVE = "android.hardware.type.automotive"
    private val TV_FEATURES = setOf(
        "android.software.leanback",
        "android.software.leanback_only",
        "android.hardware.type.television",
    )

    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    private const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"

    /**
     * The form factor of the app in [file], an APK or a split archive such as `.apkm`, `.apks`
     * or `.xapk`, read from its base module.
     *
     * An app is [FormFactor.WEARABLE], [FormFactor.AUTOMOTIVE] or [FormFactor.TV] when its manifest
     * requires the feature of that device. A TV build that leaves leanback optional is still
     * [FormFactor.TV] when it can only be opened from the TV launcher. Anything else is
     * [FormFactor.MOBILE].
     *
     * @throws FileNotFoundException If [file] does not exist or is not a file.
     * @throws IOException If [file] cannot be read, or holds no manifest of a base module.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun of(file: File): FormFactor {
        if (!file.isFile) throw FileNotFoundException("APK not found: $file")

        return ZipFile(file).use { zip ->
            val manifest = zip.getEntry(MANIFEST)?.let { zip.getInputStream(it).use(::load) }
                ?: baseModuleManifest(zip)
                ?: throw IOException("No base APK manifest found in: $file")
            of(manifest)
        }
    }

    internal fun of(manifest: AndroidManifestBlock): FormFactor {
        val root = manifest.manifestElement ?: return FormFactor.MOBILE
        val features = root.getElements(AndroidManifest.TAG_uses_feature).asSequence()
            .filter { it.isRequired() }
            .mapNotNull { it.androidName() }
            .toSet()

        return when {
            FEATURE_WATCH in features -> FormFactor.WEARABLE
            FEATURE_AUTOMOTIVE in features -> FormFactor.AUTOMOTIVE
            features.any { it in TV_FEATURES } -> FormFactor.TV
            manifest.isTvLauncherOnly() -> FormFactor.TV
            else -> FormFactor.MOBILE
        }
    }

    /**
     * The manifest of the base module of a split archive. Module names differ between formats,
     * so the base is the module whose manifest names no split, with `base.apk` tried first.
     */
    private fun baseModuleManifest(zip: ZipFile): AndroidManifestBlock? =
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .sortedByDescending { it.name.substringAfterLast('/').equals("base.apk", ignoreCase = true) }
            .firstNotNullOfOrNull { entry ->
                zip.getInputStream(entry).use(::nestedManifest)?.takeUnless { it.isSplit }
            }

    /** The manifest of the APK streamed in [input], read without unpacking the module. */
    private fun nestedManifest(input: InputStream): AndroidManifestBlock? {
        val apk = ZipInputStream(input)
        while (true) {
            val entry = apk.nextEntry ?: return null
            if (entry.name == MANIFEST) return load(apk)
        }
    }

    private fun load(input: InputStream): AndroidManifestBlock = AndroidManifestBlock.load(input)

    /**
     * Whether the app can be opened from the TV launcher and not from the phone one. Apps built for
     * both declare the two categories, and an app without a launcher entry says nothing either way.
     */
    private fun AndroidManifestBlock.isTvLauncherOnly(): Boolean {
        val application = applicationElement ?: return false
        val categories = sequenceOf(AndroidManifest.TAG_activity, AndroidManifest.TAG_activity_alias)
            .flatMap { application.getElements(it).asSequence() }
            .flatMap { it.getElements(AndroidManifest.TAG_intent_filter).asSequence() }
            .flatMap { it.getElements(AndroidManifest.TAG_category).asSequence() }
            .mapNotNull { it.androidName() }
            .toSet()

        return CATEGORY_LEANBACK_LAUNCHER in categories && CATEGORY_LAUNCHER !in categories
    }

    // A feature is required unless it says otherwise, as on the device.
    private fun ResXmlElement.isRequired(): Boolean {
        val required = searchAttributeByResourceId(ID_REQUIRED) ?: return true
        return required.dataType != DataType.BOOLEAN || required.data != 0
    }

    private fun ResXmlElement.androidName(): String? =
        searchAttributeByResourceId(AndroidManifest.ID_name)?.dataAsString
}
