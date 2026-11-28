package app.morphe.patcher.apk

import app.morphe.patcher.patch.FormFactor
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.DataType
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class ApkManifestTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `reads what the app declares about itself`() {
        val manifest = ApkManifest.of(apk(manifest(minSdk = 33)))

        assertEquals(PACKAGE, manifest.packageName)
        assertEquals("5.1.4", manifest.versionName)
        assertEquals(260903140, manifest.versionCode)
        assertEquals(33, manifest.minSdkVersion)
        assertEquals(FormFactor.MOBILE, manifest.formFactor)
    }

    @Test
    fun `the major version sits above the version code`() {
        val manifest = manifest().apply {
            manifestElement.getOrCreateAndroidAttribute("versionCodeMajor", ID_VERSION_CODE_MAJOR)
                .setTypeAndData(DataType.DEC, 2)
            refreshFull()
        }

        assertEquals((2L shl 32) or 260903140, ApkManifest.of(apk(manifest)).versionCode)
    }

    @Test
    fun `a manifest without a minimum Android version declares none`() {
        assertNull(ApkManifest.of(apk(manifest())).minSdkVersion)
    }

    @Test
    fun `a label written in the manifest is read as is`() {
        val manifest = manifest().apply {
            setApplicationLabel("Clip Studio")
            refreshFull()
        }

        assertEquals("Clip Studio", ApkManifest.of(apk(manifest)).label)
    }

    @Test
    fun `a label kept in the resources is looked up only on request`() {
        val app = labeledApk()

        assertNull(ApkManifest.of(app).label)
        assertEquals("Clip Studio", ApkManifest.of(app, resolveLabel = true).label)
    }

    @Test
    fun `a split archive is read through its base module, resources included`() {
        val bundle = zip(
            "bundle.xapk",
            "config.xxhdpi.apk" to apk(manifest(split = "config.xxhdpi")).readBytes(),
            "com.example.apk" to labeledApk().readBytes(),
        )

        val manifest = ApkManifest.of(bundle, resolveLabel = true)

        assertEquals(PACKAGE, manifest.packageName)
        assertEquals("Clip Studio", manifest.label)
    }

    @Test
    fun `the form factor is read along`() {
        val manifest = manifest().apply {
            manifestElement.newElement(AndroidManifest.TAG_uses_feature)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setDataAsString("android.software.leanback")
            refreshFull()
        }

        assertEquals(FormFactor.TV, ApkManifest.of(apk(manifest)).formFactor)
    }

    @Test
    fun `a split module on its own is not taken for an app`() {
        assertFailsWith<IOException> { ApkManifest.of(apk(manifest(split = "config.arm64_v8a"))) }
    }

    @Test
    fun `throws for a manifest without a package name`() {
        val manifest = AndroidManifestBlock().apply {
            versionCode = 1
            refreshFull()
        }

        assertFailsWith<IOException> { ApkManifest.of(apk(manifest)) }
    }

    private fun manifest(minSdk: Int? = null, split: String? = null) = AndroidManifestBlock().apply {
        setPackageName(PACKAGE)
        versionCode = 260903140
        versionName = "5.1.4"
        if (minSdk != null) setMinSdkVersion(minSdk)
        if (split != null) setSplit(split, true)
        refreshFull()
    }

    /** An APK whose label is a string resource, as apps ship it. */
    private fun labeledApk(): File {
        val table = TableBlock()
        val label = table.newPackage(0x7f, PACKAGE).getOrCreate("", "string", "app_name").apply {
            setDataAsString("Clip Studio")
        }
        table.refresh()

        val manifest = manifest().apply {
            setApplicationLabel(label.resourceId)
            refreshFull()
        }
        return zip(
            "app-${System.nanoTime()}.apk",
            "AndroidManifest.xml" to manifest.bytes,
            TableBlock.FILE_NAME to table.bytes,
        )
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
        const val PACKAGE = "com.example"
        const val ID_VERSION_CODE_MAJOR = 0x01010576
    }
}
