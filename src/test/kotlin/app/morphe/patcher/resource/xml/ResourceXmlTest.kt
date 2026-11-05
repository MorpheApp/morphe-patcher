/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.xml

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.coder.ResourceTestApk
import app.morphe.patcher.util.Document
import app.morphe.patcher.xml.ResourceXml
import app.morphe.patcher.xml.XmlElement
import com.reandroid.apk.ApkModule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ResourceXmlTest {
    @TempDir
    private lateinit var tempDir: File
    private lateinit var apk: File

    /** Closed after each test, as an open coder keeps the input APK open, which Windows cannot delete. */
    private val coders = mutableListOf<ArsclibResourceCoder>()

    @BeforeEach
    fun setUp() {
        apk = ResourceTestApk.build(tempDir)
    }

    @AfterEach
    fun tearDown() = coders.forEach(ArsclibResourceCoder::close)

    private fun coder(name: String = "working", input: File = apk) =
        ArsclibResourceCoder(tempDir.resolve(name).apply { mkdirs() }, input).also(coders::add).apply {
            decodeResources(true)
        }

    private val ArsclibResourceCoder.packageDirectory get() = packageDirectories.getValue("com.test.app")

    private fun ArsclibResourceCoder.xml(path: String, copy: Boolean = true): ResourceXml =
        assertNotNull(openXml(path, null, copy), "$path can be opened")

    /** Encodes the resources of [coder], and opens the result with a coder of its own. */
    private fun encodeAndReopen(coder: ArsclibResourceCoder, name: String = "out"): ArsclibResourceCoder {
        val output = coder.encodeResources(tempDir.resolve(name).apply { mkdirs() })
        return coder("$name-decoded", output)
    }

    private fun XmlElement.describe(): String = buildString {
        append(tag)
        attributeNames.filter { !it.startsWith("xmlns") }.sorted().forEach { append(" $it=${attribute(it)}") }
        if (children.isEmpty()) append(" text=$text")
        children.forEach { append(" [").append(it.describe()).append("]") }
    }

    @Test
    fun `a compiled file reads as its decoded text`() {
        val coder = coder()
        val element = coder.xml("res/layout/main.xml").use { it.root.describe() }

        val decoded = Document(coder.getFile("res/layout/main.xml")).use { document ->
            val root = document.documentElement
            (0 until root.attributes.length).associate { root.attributes.item(it).nodeName to root.attributes.item(it).nodeValue }
        }
        assertEquals("LinearLayout android:id=@id/existing_view android:text=@string/hello text=", element)
        assertEquals("@id/existing_view", decoded["android:id"])
        assertEquals("@string/hello", decoded["android:text"])
    }

    @Test
    fun `a compiled file is edited without decoding it`() {
        val coder = coder()
        coder.xml("res/values/strings.xml", copy = false).use { strings ->
            strings.root.appendChild("string") {
                setAttribute("name", "added")
                text = "Added"
            }
        }
        coder.xml("res/layout/main.xml").use { layout ->
            layout.root.setAttribute("android:text", "@string/keep")
            layout.root.setAttribute("android:orientation", "vertical")
            layout.root.appendChild("TextView") {
                setAttribute("android:id", "@+id/added_view")
                setAttribute("android:text", "@string/added")
                setAttribute("android:layout_width", "match_parent")
            }
            layout.root.insertChild(0, "View") {
                setAttribute("android:id", "@id/existing_view")
            }
        }
        assertFalse(coder.packageDirectory.resolve("res/layout/main.xml").exists(), "the layout is not decoded")
        assertFalse(coder.workingDir.resolve("AndroidManifest.xml").exists(), "the manifest is not decoded")

        val output = encodeAndReopen(coder)
        output.xml("res/layout/main.xml").use { layout ->
            assertEquals(
                "LinearLayout android:id=@id/existing_view android:orientation=vertical android:text=@string/keep " +
                        "[View android:id=@id/existing_view text=] " +
                        // The decoder writes -1 as fill_parent, the first name it has.
                        "[TextView android:id=@id/added_view android:layout_width=fill_parent android:text=@string/added text=]",
                layout.root.describe(),
            )
        }
        output.xml("res/values/strings.xml").use { strings ->
            assertEquals("Added", strings.resourceOrNull("added")?.text)
            assertEquals("Hello", strings.resourceOrNull("hello")?.text)
        }
    }

    @Test
    fun `an attribute Android does not know fails when it is set`() {
        coder().xml("res/layout/main.xml").use { layout ->
            assertFailsWith<PatchException> { layout.root.setAttribute("android:notAnAttribute", "1") }
            assertNull(layout.root.attribute("android:notAnAttribute"))
        }
    }

    @Test
    fun `a reference to a resource nothing adds fails when the APK is written`() {
        val coder = coder()
        coder.xml("res/layout/main.xml").use { layout ->
            layout.root.setAttribute("android:text", "@string/missing")
            assertEquals("@string/missing", layout.root.attribute("android:text"))
        }
        val exception = assertFailsWith<PatchException> {
            coder.encodeResources(tempDir.resolve("out").apply { mkdirs() })
        }
        assertTrue("@string/missing" in exception.message.orEmpty())
    }

    @Test
    fun `values read as their decoded file`() {
        val coder = coder()
        listOf(
            "res/values/strings.xml",
            "res/values-de/strings.xml",
            "res/values/colors.xml",
            "res/values/styles.xml",
            "res/values/arrays.xml",
            "res/values/attrs.xml",
        ).forEach { path ->
            // Two resources share a file, which either of them can be decoded for, depending on the order
            // the table lists them in, so they are left out.
            fun ResourceXml.describe() = root.children.filter { it.attribute("name")?.startsWith("shared_") != true }.map { it.describe() }
            val fromTable = coder.xml(path).use { it.describe() }

            // Decoded in a working directory of its own, as decoding it here would make it a text file.
            val textCoder = coder("text-${path.replace('/', '-')}")
            assertTrue(textCoder.getFile(path).isFile)
            val fromText = textCoder.xml(path).use { it.describe() }
            assertEquals(fromText, fromTable, path)
        }
    }

    @Test
    fun `values are changed, removed and added`() {
        val coder = coder()
        coder.xml("res/values/strings.xml").use { strings ->
            assertEquals("It\\'s \\\"quoted\\\"", strings.resourceOrNull("escaped")?.text)
            strings.resourceOrNull("hello")!!.text = "Hi"
            strings.resourceOrNull("escaped")!!.text = "It\\'s"
        }
        coder.xml("res/values-de/strings.xml").use { strings -> strings.resourceOrNull("keep")!!.remove() }
        coder.xml("res/values/colors.xml").use { colors ->
            colors.root.appendChild("color") {
                setAttribute("name", "added_color")
                text = "#ff00ff00"
            }
        }
        coder.xml("res/values/styles.xml").use { styles ->
            styles.resourceOrNull("S")!!.appendChild("item") {
                setAttribute("name", "android:windowBackground")
                text = "@color/added_color"
            }
        }
        coder.xml("res/values/arrays.xml").use { arrays ->
            val array = arrays.resourceOrNull("arr")!!
            array.removeChildren()
            array.appendChild("item") { text = "three" }
        }
        assertTrue(coder.packageDirectory.resolve("res").walkTopDown().none { it.isFile }, "nothing is decoded")

        val output = encodeAndReopen(coder)
        output.xml("res/values/strings.xml").use { strings ->
            assertEquals("Hi", strings.resourceOrNull("hello")?.text)
            assertEquals("It\\'s", strings.resourceOrNull("escaped")?.text)
            assertEquals("Keep", strings.resourceOrNull("keep")?.text)
        }
        output.xml("res/values-de/strings.xml").use { strings ->
            assertNull(strings.resourceOrNull("keep"))
            assertEquals("Hallo", strings.resourceOrNull("hello")?.text)
        }
        output.xml("res/values/colors.xml").use { colors -> assertEquals("#ff00ff00", colors.resourceOrNull("added_color")?.text) }
        output.xml("res/values/styles.xml").use { styles ->
            // Items are encoded in the order of their attribute ids, which puts those of Android first.
            assertEquals(
                "style name=S [item name=android:windowBackground text=@color/added_color] [item name=myAttr text=@color/accent]",
                styles.resourceOrNull("S")?.describe(),
            )
        }
        output.xml("res/values/arrays.xml").use { arrays ->
            assertEquals("string-array name=arr [item text=three]", arrays.resourceOrNull("arr")?.describe())
        }
    }

    @Test
    fun `a values file of a new configuration is added`() {
        val coder = coder()
        coder.xml("res/values-night/colors.xml", copy = false).use { colors ->
            colors.root.appendChild("color") {
                setAttribute("name", "accent")
                text = "#ff0000ff"
            }
        }
        coder.xml("res/values-mcc1001/colors.xml", copy = false).use { colors ->
            colors.root.appendChild("color") {
                setAttribute("name", "accent")
                text = "#ff00ffff"
            }
        }
        assertNull(coder.openXml("res/values-fr/strings.xml", null, true), "a configuration the APK lacks cannot be read")

        val output = encodeAndReopen(coder)
        output.xml("res/values-night/colors.xml").use { assertEquals("#ff0000ff", it.resourceOrNull("accent")?.text) }
        output.xml("res/values-mcc1001/colors.xml").use { assertEquals("#ff00ffff", it.resourceOrNull("accent")?.text) }
        output.xml("res/values/colors.xml").use { assertEquals("#ffff0000", it.resourceOrNull("accent")?.text) }
    }

    @Test
    fun `what a document changed is written out when the file is read`() {
        val coder = coder()
        coder.xml("res/values/strings.xml").use { strings ->
            strings.resourceOrNull("hello")!!.text = "Hi"
            strings.resourceOrNull("keep")!!.remove()
        }
        coder.xml("res/layout/main.xml").use { layout -> layout.root.setAttribute("android:text", "@string/keep") }
        coder.xml("AndroidManifest.xml").use { manifest -> manifest.root.setAttribute("android:versionName", "2.0") }

        val strings = coder.getFile("res/values/strings.xml").readText()
        assertTrue("<string name=\"hello\">Hi</string>" in strings, strings)
        assertFalse("name=\"keep\"" in strings, strings)
        assertTrue("<string name=\"app_name\">Test</string>" in strings, strings)
        assertTrue("android:text=\"@string/keep\"" in coder.getFile("res/layout/main.xml").readText())
        assertTrue("android:versionName=\"2.0\"" in coder.getFile("AndroidManifest.xml").readText())

        // From now on the files are read as text.
        coder.xml("res/values/strings.xml").use { assertEquals("Hi", it.resourceOrNull("hello")?.text) }

        val output = encodeAndReopen(coder)
        output.xml("res/values/strings.xml").use { strings ->
            assertEquals("Hi", strings.resourceOrNull("hello")?.text)
            assertNull(strings.resourceOrNull("keep"))
        }
        output.xml("res/layout/main.xml").use { assertEquals("@string/keep", it.root.attribute("android:text")) }
        output.xml("AndroidManifest.xml").use { assertEquals("2.0", it.root.attribute("android:versionName")) }
    }

    @Test
    fun `decode writes out the documents it matches`() {
        val coder = coder()
        coder.xml("res/layout/main.xml").use { layout -> layout.root.setAttribute("android:text", "@string/keep") }
        assertEquals(listOf("res/layout/main.xml"), coder.decode(listOf("res/layout/*")))
        assertTrue("@string/keep" in coder.getFile("res/layout/main.xml").readText())
    }

    @Test
    fun `the manifest is edited in its binary form`() {
        val coder = coder()
        coder.xml("AndroidManifest.xml").use { manifest ->
            assertEquals("com.test.app", manifest.root.attribute("package"))
            manifest.elements("application").single().apply {
                setAttribute("android:label", "@string/added")
                appendChild("activity") { setAttribute("android:name", "app.morphe.Activity") }
            }
            manifest.root.insertChild(0, "uses-permission") { setAttribute("android:name", "android.permission.INTERNET") }
        }
        coder.xml("res/values/strings.xml", copy = false).use { strings ->
            strings.root.appendChild("string") {
                setAttribute("name", "added")
                text = "Label"
            }
        }

        val output = encodeAndReopen(coder)
        assertFalse(coder.workingDir.resolve("AndroidManifest.xml").exists(), "the manifest is not decoded")
        output.xml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").single()
            assertEquals("@string/added", application.attribute("android:label"))
            assertEquals("app.morphe.Activity", application.children.single().attribute("android:name"))
            assertEquals("android.permission.INTERNET", manifest.root.children.first().attribute("android:name"))
        }
    }

    @Test
    fun `a manifest no patch changed stays as it is`() {
        val coder = coder()
        coder.xml("AndroidManifest.xml").use { assertEquals("com.test.app", it.root.attribute("package")) }
        val output = coder.encodeResources(tempDir.resolve("out").apply { mkdirs() })

        fun manifestBytes(file: File) = ApkModule.loadApkFile(file).use { module ->
            module.zipEntryMap.getInputSource("AndroidManifest.xml").openStream().readBytes()
        }
        assertContentEquals(manifestBytes(apk), manifestBytes(output))
    }

    @Test
    fun `elements are copied from XML read from a stream`() {
        val coder = coder()
        val source = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:yt="http://schemas.android.com/apk/res-auto"
                xmlns:tools="http://schemas.android.com/tools">
                <TextView android:id="@+id/copied" android:text="@string/hello" yt:myAttr="@color/accent"
                    tools:text="Preview">Text &amp; more</TextView>
            </FrameLayout>
        """.trimIndent()

        TextXmlDocument.read(source.byteInputStream()).use { snippet ->
            coder.xml("res/layout/main.xml").use { layout ->
                layout.root.appendCopy(snippet.root.children.single())
            }
        }

        val output = encodeAndReopen(coder)
        output.xml("res/layout/main.xml").use { layout ->
            val copied = layout.root.children.single()
            assertEquals("@id/copied", copied.attribute("android:id"))
            assertEquals("@string/hello", copied.attribute("android:text"))
            assertEquals("@color/accent", copied.attribute("app:myAttr"))
            assertNull(copied.attribute("tools:text"))
            assertEquals("Text & more", copied.text)
        }
    }

    @Test
    fun `text files are read and written as text`() {
        val coder = coder()
        val file = coder.packageDirectory.resolve("res/xml/added.xml")
        file.parentFile.mkdirs()
        file.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- A comment -->
                <Preference android:key="a" android:title="A &amp; B"/>
            </PreferenceScreen>
            """.trimIndent()
        )

        coder.xml("res/xml/added.xml").use { xml ->
            xml.root.insertChild(0, "Preference") { setAttribute("android:key", "first") }
            assertEquals("A & B", xml.elementByAttribute("android:key", "a").attribute("android:title"))
        }

        val text = file.readText()
        assertTrue("<!-- A comment -->" in text, text)
        assertTrue("android:title=\"A &amp; B\"" in text, text)
        assertTrue(text.indexOf("first") < text.indexOf("\"a\""), text)
    }

    @Test
    fun `a document cannot be opened twice or used after it is closed`() {
        val coder = coder()
        val document = coder.xml("res/layout/main.xml")
        assertFailsWith<IllegalStateException> { coder.openXml("res/layout/main.xml", null, true) }
        val root = document.root
        document.close()
        assertFailsWith<IllegalStateException> { root.tag }
    }

    @Test
    fun `values files are filled from several threads`() {
        val coder = coder()
        val locales = listOf("fr", "es", "it", "ja", "ko", "pt", "ru", "zh", "ar", "nl", "sv", "pl", "tr", "cs")
            .map { "res/values-$it/strings.xml" }
        val executor = Executors.newFixedThreadPool(4)
        try {
            locales.map { path ->
                executor.submit {
                    coder.xml(path, copy = false).use { strings ->
                        repeat(50) { index ->
                            strings.root.appendChild("string") {
                                setAttribute("name", "app_name")
                                text = "$path $index"
                            }
                        }
                    }
                }
            }.forEach { it.get() }
        } finally {
            executor.shutdown()
        }

        val output = encodeAndReopen(coder)
        locales.forEach { path ->
            output.xml(path).use { assertEquals("$path 49", it.resourceOrNull("app_name")?.text) }
        }
    }

    @Test
    fun `new resources get the same ids every time`() {
        fun patch(name: String): ByteArray {
            val coder = coder(name)
            coder.xml("res/values/strings.xml", copy = false).use { strings ->
                listOf("b_added", "a_added").forEach { added ->
                    strings.root.appendChild("string") {
                        setAttribute("name", added)
                        text = added
                    }
                }
            }
            coder.xml("res/layout/main.xml").use { layout ->
                layout.root.appendChild("View") { setAttribute("android:id", "@+id/added_view") }
            }
            val output = coder.encodeResources(tempDir.resolve("$name-out").apply { mkdirs() })
            return ApkModule.loadApkFile(output).use { it.zipEntryMap.getInputSource("resources.arsc").openStream().readBytes() }
        }
        assertContentEquals(patch("first"), patch("second"))
    }

    @Test
    fun `documents are kept when the resource table is built again`() {
        val coder = coder()
        coder.failIncrementalForTest = true
        coder.xml("res/values/strings.xml").use { it.resourceOrNull("hello")!!.text = "Hi" }
        coder.xml("res/layout/main.xml").use { layout ->
            layout.root.appendChild("View") { setAttribute("android:id", "@+id/added_view") }
        }
        coder.xml("AndroidManifest.xml").use { it.root.setAttribute("android:versionName", "2.0") }

        val output = encodeAndReopen(coder)
        output.xml("res/values/strings.xml").use { assertEquals("Hi", it.resourceOrNull("hello")?.text) }
        output.xml("res/layout/main.xml").use { assertEquals("@id/added_view", it.root.children.single().attribute("android:id")) }
        output.xml("AndroidManifest.xml").use { assertEquals("2.0", it.root.attribute("android:versionName")) }
    }

    @Test
    fun `a deleted file cannot be opened`() {
        val coder = coder()
        coder.xml("res/layout/main.xml").use { layout -> layout.root.setAttribute("android:text", "@string/keep") }
        coder.deleteFile("res/layout/main.xml")
        assertNull(coder.openXml("res/layout/main.xml", null, true))
    }

    @Test
    fun `a document of a DOM file reads the DOM changes`() {
        val coder = coder()
        Document(coder.getFile("res/layout/main.xml")).use { document ->
            (document.documentElement as Element).setAttribute("android:text", "@string/keep")
        }
        coder.xml("res/layout/main.xml").use { layout -> assertEquals("@string/keep", layout.root.attribute("android:text")) }
    }
}
