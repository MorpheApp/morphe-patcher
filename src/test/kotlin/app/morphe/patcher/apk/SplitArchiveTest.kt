package app.morphe.patcher.apk

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SplitArchiveTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `a module whose manifest declares no split is the base, whatever its name`() {
        assertTrue(isBase("com.example.config.tl.apk", module(MANIFEST to manifest(split = null))))
    }

    @Test
    fun `a module whose manifest declares a split is not the base`() {
        assertFalse(isBase("split_config.tl.apk", module(MANIFEST to manifest(split = "config.tl"))))
    }

    @Test
    fun `the manifest is found past the entries written before it`() {
        val base = module("classes.dex" to byteArrayOf(0), MANIFEST to manifest(split = null))

        assertTrue(isBase("com.example.app.apk", base))
    }

    @Test
    fun `a module without a readable manifest is not taken for the base`() {
        assertFalse(isBase("broken.apk", module("classes.dex" to byteArrayOf(0))))
        assertFalse(isBase("garbage.apk", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `the base goes by its conventional name`() {
        val base = withArchive(
            "split_config.en.apk" to module(MANIFEST to manifest(split = "config.en")),
            "base.apk" to module(MANIFEST to manifest(split = null)),
        ) { SplitArchive.baseModule(it)?.name }

        assertEquals("base.apk", base)
    }

    @Test
    fun `past the conventional names the base goes by its manifest`() {
        val base = withArchive(
            "config.xxhdpi.apk" to module(MANIFEST to manifest(split = "config.xxhdpi")),
            "com.example.app.apk" to module(MANIFEST to manifest(split = null)),
        ) { SplitArchive.baseModule(it)?.name }

        assertEquals("com.example.app.apk", base)
    }

    @Test
    fun `an archive without modules has no base`() {
        assertNull(withArchive("Android/obb/main.obb" to byteArrayOf(0)) { SplitArchive.baseModule(it) })
    }

    @Test
    fun `nested APKs and Windows paths are not modules`() {
        val modules = withArchive(
            "base.apk" to module(MANIFEST to manifest(split = null)),
            "res/raw/helper.apk" to byteArrayOf(0),
            "..\\evil.apk" to byteArrayOf(0),
        ) { zip -> SplitArchive.modules(zip).map { it.name }.toList() }

        assertEquals(listOf("base.apk"), modules)
    }

    @Test
    fun `an archive is told by its extension or else by the modules it holds`() {
        val modules = arrayOf("base.apk" to module(MANIFEST to manifest(split = null)))

        assertTrue(SplitArchive.isSplitArchive(archive("bundle.apkm", *modules)))
        assertTrue(SplitArchive.isSplitArchive(archive("bundle.zip", *modules)))
        assertFalse(SplitArchive.isSplitArchive(archive("app.apk", MANIFEST to manifest(split = null))))
    }

    private fun isBase(name: String, content: ByteArray) =
        withArchive(name to content) { zip -> SplitArchive.isBaseModule(zip, zip.getEntry(name)) }

    private fun manifest(split: String?): ByteArray = AndroidManifestBlock().apply {
        packageName = "com.example.app"
        if (split != null) setSplit(split, true)
        refresh()
    }.bytes

    private fun module(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun archive(name: String, vararg entries: Pair<String, ByteArray>): File =
        temporaryDirectory.resolve(name).apply { writeBytes(module(*entries)) }

    private fun <T> withArchive(vararg entries: Pair<String, ByteArray>, block: (ZipFile) -> T): T =
        ZipFile(archive("bundle-${System.nanoTime()}.xapk", *entries)).use(block)

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
    }
}
