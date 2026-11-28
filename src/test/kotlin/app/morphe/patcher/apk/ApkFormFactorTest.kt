package app.morphe.patcher.apk

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.FormFactor
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ApkFormFactorTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `an app without device features is a mobile app`() {
        assertEquals(FormFactor.MOBILE, ApkFormFactor.of(apk(manifest(launchers = listOf(LAUNCHER)))))
    }

    @Test
    fun `required device features name the form factor`() {
        assertEquals(FormFactor.TV, ApkFormFactor.of(apk(manifest(features = mapOf(LEANBACK to true)))))
        assertEquals(FormFactor.TV, ApkFormFactor.of(apk(manifest(features = mapOf(TELEVISION to true)))))
        assertEquals(FormFactor.WEARABLE, ApkFormFactor.of(apk(manifest(features = mapOf(WATCH to true)))))
        assertEquals(FormFactor.AUTOMOTIVE, ApkFormFactor.of(apk(manifest(features = mapOf(AUTOMOTIVE to true)))))
    }

    @Test
    fun `a feature without the required attribute is required`() {
        assertEquals(FormFactor.TV, ApkFormFactor.of(apk(manifest(features = mapOf(LEANBACK to null)))))
    }

    @Test
    fun `an optional device feature does not name the form factor`() {
        val manifest = manifest(features = mapOf(LEANBACK to false), launchers = listOf(LAUNCHER, LEANBACK_LAUNCHER))

        assertEquals(FormFactor.MOBILE, ApkFormFactor.of(apk(manifest)))
    }

    @Test
    fun `an app opened only from the TV launcher is a TV app`() {
        val manifest = manifest(features = mapOf(LEANBACK to false), launchers = listOf(LEANBACK_LAUNCHER))

        assertEquals(FormFactor.TV, ApkFormFactor.of(apk(manifest)))
    }

    @Test
    fun `a split archive is read through its base module`() {
        val bundle = zip(
            "bundle.apkm",
            "split_config.arm64_v8a.apk" to apk(manifest(split = "config.arm64_v8a")).readBytes(),
            "base.apk" to apk(manifest(features = mapOf(WATCH to true))).readBytes(),
        )

        assertEquals(FormFactor.WEARABLE, ApkFormFactor.of(bundle))
    }

    @Test
    fun `the base module of a split archive need not be named base`() {
        val bundle = zip(
            "bundle.xapk",
            "config.xxhdpi.apk" to apk(manifest(split = "config.xxhdpi")).readBytes(),
            "com.example.apk" to apk(manifest(features = mapOf(LEANBACK to true))).readBytes(),
        )

        assertEquals(FormFactor.TV, ApkFormFactor.of(bundle))
    }

    @Test
    fun `throws for an archive without a base module`() {
        val bundle = zip("bundle.apks", "split.apk" to apk(manifest(split = "config.en")).readBytes())

        assertFailsWith<IOException> { ApkFormFactor.of(bundle) }
    }

    @Test
    fun `throws for a missing file`() {
        assertFailsWith<FileNotFoundException> { ApkFormFactor.of(temporaryDirectory.resolve("missing.apk")) }
    }

    @Test
    fun `a declaration covers the APKs of its own form factor`() {
        val tv = Compatibility(name = "Example", packageName = "com.example", formFactor = FormFactor.TV)
        val any = Compatibility(name = "Example", packageName = "com.example", formFactor = null)

        assertTrue(tv.supports(FormFactor.TV))
        assertFalse(tv.supports(FormFactor.MOBILE))
        assertTrue(any.supports(FormFactor.TV))
        assertTrue(any.supports(FormFactor.MOBILE))
        assertTrue(Compatibility(name = "Example", packageName = "com.example").supports(FormFactor.MOBILE))
    }

    /** Feature names to their `android:required` value, null leaving the attribute out. */
    private fun manifest(
        features: Map<String, Boolean?> = emptyMap(),
        launchers: List<String> = emptyList(),
        split: String? = null,
    ) = AndroidManifestBlock().apply {
        setPackageName("com.example")
        versionCode = 1
        versionName = "1.0"
        if (split != null) setSplit(split, true)
        features.forEach { (name, required) ->
            manifestElement.newElement(AndroidManifest.TAG_uses_feature).apply {
                getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name).setDataAsString(name)
                if (required != null) getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(required)
            }
        }
        if (launchers.isNotEmpty()) {
            val filter = getOrCreateApplicationElement()
                .newElement(AndroidManifest.TAG_activity)
                .newElement(AndroidManifest.TAG_intent_filter)
            filter.newElement(AndroidManifest.TAG_action)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setDataAsString("android.intent.action.MAIN")
            launchers.forEach { category ->
                filter.newElement(AndroidManifest.TAG_category)
                    .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                    .setDataAsString(category)
            }
        }
        refreshFull()
    }

    private fun apk(manifest: AndroidManifestBlock): File =
        zip("app-${System.nanoTime()}.apk", "AndroidManifest.xml" to manifest.bytes)

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = temporaryDirectory.resolve(name)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content)
                zip.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }

    private companion object {
        const val ID_REQUIRED = 0x0101028e

        const val LEANBACK = "android.software.leanback"
        const val TELEVISION = "android.hardware.type.television"
        const val WATCH = "android.hardware.type.watch"
        const val AUTOMOTIVE = "android.hardware.type.automotive"

        const val LAUNCHER = "android.intent.category.LAUNCHER"
        const val LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    }
}
