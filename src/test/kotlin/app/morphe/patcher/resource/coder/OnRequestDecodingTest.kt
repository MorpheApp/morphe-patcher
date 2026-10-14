/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import app.morphe.patcher.resource.PathPatterns
import app.morphe.patcher.resource.ResourceMode
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OnRequestDecodingTest {
    @TempDir
    private lateinit var tempDir: File
    private lateinit var apk: File

    @BeforeEach
    fun setUp() {
        apk = ResourceTestApk.build(tempDir)
    }

    /** Closed after each test, as an open coder keeps the input APK open, which Windows cannot delete. */
    private val coders = mutableListOf<ArsclibResourceCoder>()

    @AfterEach
    fun tearDown() = coders.forEach(ArsclibResourceCoder::close)

    private fun coder(name: String) =
        ArsclibResourceCoder(tempDir.resolve(name).apply { mkdirs() }, apk).also(coders::add)

    private fun decodedCoder(name: String = "working", onRequest: Boolean = true) =
        coder(name).apply {
            decodeResources(onRequest)
        }

    private val ArsclibResourceCoder.packageDirectory get() = packageDirectories.getValue("com.test.app")

    /** The files below a directory by their path relative to it. */
    private fun File.files(): Map<String, File> =
        walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(this).invariantSeparatorsPath }

    private fun encode(coder: ArsclibResourceCoder): File =
        coder.encodeResources(tempDir.resolve("out").apply { mkdirs() })

    private fun PackageBlock.string(qualifiers: String, name: String) =
        getEntry(qualifiers, "string", name)?.takeUnless { it.isNull }?.resValue?.valueAsString

    @Test
    fun `only the manifest is decoded up front`() {
        val coder = decodedCoder()

        assertTrue(coder.workingDir.resolve("AndroidManifest.xml").isFile)
        assertTrue(coder.packageDirectory.resolve("res").files().isEmpty())
        assertTrue(
            coder.packageDirectory.resolve("res/drawable-xxhdpi").isDirectory,
            "the directories exist as when every resource is decoded",
        )
        assertTrue(coder.otherResourcesRootDirectory.files().isEmpty())
    }

    @Test
    fun `decoding everything on request decodes what decoding up front does`() {
        val upFront = decodedCoder("up-front", onRequest = false)
        val onRequest = decodedCoder("on-request")

        onRequest.decode(listOf("**"))

        // Two resources share a file, which either of them can be decoded for, depending on the order
        // the table lists them in.
        val sharedFile = Regex("res/color/shared_[ab]\\.xml")
        val sharedValue = Regex("<color name=\"shared_[ab]\">res/color/shared_[ab]\\.xml</color>")
        fun File.normalizedFiles() = files().map { (path, file) ->
            path.replace(sharedFile, "res/color/shared.xml") to file.readText().replace(sharedValue, "shared")
        }.toMap()

        listOf(
            { coder: ArsclibResourceCoder -> coder.workingDir.resolve("resources") },
            { coder: ArsclibResourceCoder -> coder.otherResourcesRootDirectory },
        ).forEach { directory ->
            val expected = directory(upFront).normalizedFiles()
            val actual = directory(onRequest).normalizedFiles()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (path, content) -> assertEquals(content, actual.getValue(path), "$path differs") }
        }
        assertEquals(
            upFront.workingDir.resolve("AndroidManifest.xml").readText(),
            onRequest.workingDir.resolve("AndroidManifest.xml").readText(),
        )
    }

    @Test
    fun `listing names what can be decoded without decoding it`() {
        val coder = decodedCoder()

        val files = coder.listDecodableFiles("**")

        listOf(
            "AndroidManifest.xml",
            "assets/data.bin",
            "res/color/selector.xml",
            "res/drawable-xxhdpi/icon.png",
            "res/layout/main.xml",
            "res/values/public.xml",
            "res/values/strings.xml",
            "res/values-de/strings.xml",
        ).forEach { assertTrue(it in files, "$it is not listed: $files") }
        assertFalse(files.any { it == "resources.arsc" || it.endsWith(".dex") || it == "package.json" })
        assertEquals(files.sorted(), files)

        assertEquals(
            listOf("res/values-de/strings.xml", "res/values/strings.xml"),
            coder.listDecodableFiles("res/values*/strings.xml"),
        )
        assertTrue(coder.packageDirectory.resolve("res").files().isEmpty(), "listing decodes nothing")
    }

    @Test
    fun `reading a file by its path decodes only that file`() {
        val coder = decodedCoder()

        val strings = coder.getFile("res/values/strings.xml", null, true)
        val layout = coder.getFile("res/layout/main.xml", null, false)

        assertTrue(strings.isFile)
        assertTrue("<string name=\"hello\">Hello</string>" in strings.readText())
        assertFalse(layout.exists(), "a file requested without decoding it is not decoded")
        assertEquals(listOf("res/values/strings.xml"), coder.packageDirectory.files().keys.filter { it.startsWith("res/") })
    }

    @Test
    fun `decode returns the matching files in the working directory`() {
        val coder = decodedCoder()
        coder.getFile("res/values-fr/strings.xml", null, false).apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"hello\">Bonjour</string></resources>")
        }

        val strings = coder.decode(listOf("res/values*/strings.xml"))

        assertEquals(
            listOf("res/values-de/strings.xml", "res/values-fr/strings.xml", "res/values/strings.xml"),
            strings,
        )
        assertEquals(listOf("res/layout/main.xml"), coder.decode(listOf("res/layout")))
        assertTrue(coder.packageDirectory.resolve("res/drawable").files().isEmpty())
    }

    @Test
    fun `decode returns what matching every file in the working directory returns`() {
        val upFront = decodedCoder("up-front", onRequest = false)
        val onRequest = decodedCoder("on-request").apply { decode(listOf("**")) }

        listOf(
            listOf("res/values*/strings.xml"),
            listOf("res/layout"),
            listOf("res/drawable*/icon.*", "res/color/selector.xml"),
            listOf("res/**/main.xml"),
            listOf("**"),
            listOf(""),
            listOf("assets"),
            listOf("AndroidManifest.xml"),
            listOf("res/drawable-?dpi/*"),
            listOf("nothing/here"),
        ).forEach { patterns ->
            val matcher = PathPatterns(patterns)
            listOf(upFront, onRequest).forEach { coder ->
                val everyFile = sequenceOf("AndroidManifest.xml") +
                    coder.packageDirectory.resolve("res").files().keys.map { "res/$it" } +
                    coder.otherResourcesRootDirectory.files().keys.filterNot { it.startsWith("res/") }

                assertEquals(everyFile.filter(matcher::matches).sorted().toList(), coder.decode(patterns), "$patterns")
            }
        }
    }

    @Test
    fun `decode leaves changed and deleted files alone`() {
        val coder = decodedCoder()
        val strings = coder.getFile("res/values/strings.xml", null, true)
        strings.writeText(strings.readText().replace(">Hello<", ">Changed<"))
        assertTrue(coder.getFile("res/layout/main.xml", null, true).delete())

        coder.decode(listOf("**"))

        assertTrue(">Changed<" in strings.readText())
        assertFalse(coder.packageDirectory.resolve("res/layout/main.xml").exists())
    }

    @Test
    fun `a values file written without decoding adds to the resources of the app`() {
        val coder = decodedCoder()
        coder.getFile("res/values-de/strings.xml", null, false).apply {
            parentFile.mkdirs()
            writeText(
                """<resources><string name="hello">Servus</string><string name="added">Neu</string></resources>"""
            )
        }

        ApkModule.loadApkFile(encode(coder)).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertEquals("Servus", pkg.string("-de", "hello"), "a resource of the same name is replaced")
            assertEquals("Neu", pkg.string("-de", "added"))
            assertEquals("Behalten", pkg.string("-de", "keep"), "the resources the file leaves out are kept")
            assertEquals("Hello", pkg.string("", "hello"))
        }
    }

    @Test
    fun `a values file written before it is decoded keeps what was written`() {
        val coder = decodedCoder()
        val strings = coder.getFile("res/values-de/strings.xml", null, false).apply {
            parentFile.mkdirs()
            writeText("""<resources><string name="hello">Servus</string></resources>""")
        }

        coder.getFile("res/values-de/strings.xml", null, true)

        val text = strings.readText()
        assertTrue("Servus" in text && "Behalten" in text, text)
        assertFalse("Hallo" in text, "the decoded resource of the same name is replaced: $text")

        // Decoded, the file holds the whole configuration again, so removing a resource removes it.
        strings.writeText(text.replace(Regex("""<string name="keep">[^<]*</string>"""), ""))

        ApkModule.loadApkFile(encode(coder)).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertEquals("Servus", pkg.string("-de", "hello"))
            assertNull(pkg.string("-de", "keep"))
        }
    }

    @Test
    fun `a resource written as an item replaces the decoded one of the same name`() {
        val coder = decodedCoder()
        val strings = coder.getFile("res/values-de/strings.xml", null, false).apply {
            parentFile.mkdirs()
            writeText("""<resources><item type="string" name="hello">Servus</item></resources>""")
        }

        coder.getFile("res/values-de/strings.xml", null, true)

        val text = strings.readText()
        assertTrue("Servus" in text && "Behalten" in text, text)
        assertFalse("Hallo" in text, "the decoded resource of the same name is replaced: $text")
    }

    @Test
    fun `deleting files that were never decoded deletes their resources`() {
        val coder = decodedCoder()

        coder.deleteFile("res/values-de/strings.xml")
        coder.deleteFile("res/drawable-xxhdpi/icon.png")

        val output = encode(coder)
        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertNull(pkg.string("-de", "hello"))
            assertEquals("Hello", pkg.string("", "hello"))
            assertTrue(pkg.getEntry("-xxhdpi", "drawable", "icon")?.isNull ?: true)
        }
        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("res/drawable-xxhdpi/icon.png"))
            assertNotNull(zip.getEntry("res/drawable/icon.png"))
        }

        // Decoding everything later does not bring them back.
        assertFalse("res/values-de/strings.xml" in coder.decode(listOf("**")))
    }

    @Test
    fun `root entries are extracted on request`() {
        val coder = decodedCoder()

        assertFalse(coder.getFile("assets/data.bin", null, false).exists())
        assertEquals(listOf("assets/data.bin"), coder.decode(listOf("assets")))
        assertContentEquals(ByteArray(1024) { it.toByte() }, coder.getFile("assets/data.bin", null, false).readBytes())
    }

    @Test
    fun `raw resources are extracted on request`() {
        val coder = coder("raw")
        coder.decodeRaw(onRequest = true)

        assertTrue(coder.otherResourcesRootDirectory.files().isEmpty())
        assertEquals(listOf("assets/data.bin"), coder.listDecodableFiles("**"))

        val data = coder.getFile("assets/data.bin", null, true)
        assertTrue(data.isFile)
        assertNull(
            coder.getOtherResourceFiles(tempDir.resolve("raw-out").apply { mkdirs() }, ResourceMode.RAW_ONLY),
            "an entry a patch only read is not written again",
        )
    }
}
