/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import com.android.tools.build.apkzlib.zip.ZFile
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.coder.CoderSetting
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import com.reandroid.xml.XMLFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes a small APK, edits its resources the way patches do, encodes it again and checks the
 * result. Runs against the incremental encoder and against the full rebuild, which must agree.
 */
internal class IncrementalResourceEncoderTest {
    private val warnings = mutableListOf<LogRecord>()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level.intValue() >= Level.WARNING.intValue()) warnings += record
        }

        override fun flush() {}
        override fun close() {}
    }
    private val coderLogger = Logger.getLogger(ArsclibResourceCoder::class.java.name).also { it.addHandler(handler) }

    @AfterEach
    fun tearDown() = coderLogger.removeHandler(handler)

    /** Decodes a fresh input APK with a coder on the requested path. */
    private fun decode(fullRebuild: Boolean, tempDir: File): Pair<File, ArsclibResourceCoder> {
        val apk = buildInputApk(tempDir)
        val coder = ArsclibResourceCoder(
            tempDir.resolve("working").apply { mkdirs() },
            apk,
            fullResourceEncode = fullRebuild,
        )
        coder.decodeResources()
        return apk to coder
    }

    /** Encodes, and on the incremental path checks it did not fall back to the full rebuild. */
    private fun encode(coder: ArsclibResourceCoder, fullRebuild: Boolean, tempDir: File): File {
        val output = coder.encodeResources(tempDir.resolve("out").apply { mkdirs() })
        if (!fullRebuild) assertTrue(
            warnings.none { it.message.orEmpty().contains("rebuilding the table") },
            "The incremental encoder fell back: ${warnings.map { it.message }}",
        )
        return output
    }

    // ==================== Fixture ====================

    private fun buildInputApk(dir: File): File {
        val packageName = "com.test.app"
        // The coder under test installs an aapt-style string decoder on this singleton, which
        // would otherwise leak into the fixture of the next test.
        XmlCoder.getInstance().setting = CoderSetting()

        val table = TableBlock()
        table.addFramework(AndroidFrameworks.getLatest().tableBlock)
        val pkg = table.newPackage(0x7f, packageName)

        fun declare(type: String, vararg names: String) = names.forEach { pkg.getOrCreate("", type, it) }
        declare("attr", "myAttr")
        declare("string", "app_name", "hello", "keep", "styled", "escaped")
        declare("color", "accent", "selector")
        declare("style", "S")
        declare("array", "arr")
        declare("layout", "main")
        declare("drawable", "icon", "sparse_a", "sparse_b", "sparse_c")
        declare("id", "existing_view")

        fun values(qualifiers: String, type: String, xml: String) {
            val typeBlock = pkg.getOrCreateTypeBlock(qualifiers, type)
            XmlCoder.getInstance().VALUES_XML.encode(XMLFactory.newPullParser("<resources>$xml</resources>"), typeBlock)
        }
        values("", "attr", """<attr name="myAttr" format="reference|color"/>""")
        values(
            "", "string",
            """<string name="app_name">Test</string>
               <string name="hello">Hello</string>
               <string name="keep">Keep</string>
               <string name="styled">A <b>bold</b> word</string>
               <string name="escaped">It's "quoted"</string>"""
        )
        values("-de", "string", """<string name="hello">Hallo</string><string name="keep">Behalten</string>""")
        values("", "color", """<color name="accent">#ffff0000</color>""")
        values("", "style", """<style name="S"><item name="myAttr">@color/accent</item></style>""")
        values("", "array", """<string-array name="arr"><item>one</item><item>two</item></string-array>""")
        pkg.getOrCreate("", "id", "existing_view").setValueAsBoolean(false)
        pkg.getOrCreate("", "layout", "main").setValueAsString("res/layout/main.xml")
        pkg.getOrCreate("", "drawable", "icon").setValueAsString("res/drawable/icon.png")
        pkg.getOrCreate("-xxhdpi", "drawable", "icon").setValueAsString("res/drawable-xxhdpi/icon.png")
        // A sparse configuration, as apps built with aapt2 --enable-sparse-encoding ship them.
        pkg.getOrCreate("-hdpi", "drawable", "sparse_a").setValueAsString("res/drawable-hdpi/sparse_a.png")
        pkg.getOrCreate("-hdpi", "drawable", "sparse_b").setValueAsString("res/drawable-hdpi/sparse_b.png")
        pkg.getOrCreate("-hdpi", "drawable", "sparse_c").setValueAsString("res/drawable-hdpi/sparse_c.png")
        pkg.getOrCreateSpecTypePair("drawable").getTypeBlock(ResConfig.parse("-hdpi"))!!.headerBlock.isSparse = true
        pkg.getOrCreate("", "color", "selector").setValueAsString("res/color/selector.xml")
        table.refresh()

        fun binaryXml(xml: String) = ResXmlDocument().apply {
            setPackageBlock(pkg)
            parse(XMLFactory.newPullParser(xml))
            refreshFull()
        }.bytes

        val layout = binaryXml(
            """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                   android:id="@id/existing_view" android:text="@string/hello"/>"""
        )
        val selector = binaryXml(
            """<selector xmlns:android="http://schemas.android.com/apk/res/android">
                   <item android:color="@color/accent"/>
               </selector>"""
        )

        val manifest = AndroidManifestBlock().apply {
            setPackageName(packageName)
            setVersionCode(1)
            setVersionName("1.0")
            setMinSdkVersion(26)
            setTargetSdkVersion(34)
            setCompileSdkVersion(34)
            setPlatformBuildVersionCode(34)
            getOrCreateApplicationElement()
            refreshFull()
        }

        table.clearFrameworks()
        table.refresh()

        val apk = dir.resolve("input.apk")
        ZFile.openReadWrite(apk).use { zip ->
            zip.add("resources.arsc", ByteArrayInputStream(table.bytes))
            zip.add("AndroidManifest.xml", ByteArrayInputStream(manifest.bytes))
            zip.add("res/layout/main.xml", ByteArrayInputStream(layout))
            zip.add("res/color/selector.xml", ByteArrayInputStream(selector))
            zip.add("res/drawable/icon.png", ByteArrayInputStream("PNG default".toByteArray()))
            zip.add("res/drawable-xxhdpi/icon.png", ByteArrayInputStream("PNG xxhdpi".toByteArray()))
            for (name in listOf("sparse_a", "sparse_b", "sparse_c")) {
                zip.add("res/drawable-hdpi/$name.png", ByteArrayInputStream("PNG $name".toByteArray()))
            }
            zip.add("assets/data.bin", ByteArrayInputStream(ByteArray(1024) { it.toByte() }))
        }
        return apk
    }

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun crcOf(apk: File, entry: String): Long? = ZipFile(apk).use { zip ->
        zip.getEntry(entry)?.let { zip.getInputStream(it).use { input -> CRC32().also { it.update(input.readBytes()) }.value } }
    }

    private fun PackageBlock.string(qualifiers: String, name: String) =
        getEntry(qualifiers, "string", name)?.takeUnless { it.isNull }?.resValue?.valueAsString

    // ==================== Tests ====================

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `edited resources are encoded over the input table`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (apk, coder) = decode(fullRebuild, tempDir)

        // Strings: modify, delete, add, in one configuration only.
        coder.getFile("res/values/strings.xml", null, false).edit {
            it.replace(">Hello<", ">Hello!<")
                .replace(Regex("""\s*<string name="keep">[^<]*</string>"""), "")
                .replace("</resources>", """<string name="added">Added \'here\'</string></resources>""")
        }
        // A bag gains an item, an array is rebuilt.
        coder.getFile("res/values/attrs.xml", null, false).edit {
            it.replace("</resources>", """<attr name="newAttr" format="boolean"/></resources>""")
        }
        coder.getFile("res/values/styles.xml", null, false).edit {
            it.replace("</style>", """<item name="newAttr">true</item><item name="android:textColor">@color/accent</item></style>""")
        }
        coder.getFile("res/values/arrays.xml", null, false).edit {
            it.replace(Regex("<item>.*</item>", RegexOption.DOT_MATCHES_ALL), "<item>uno</item><item>dos</item><item>tres</item>")
        }
        // A colour is added next to one defined by a file.
        coder.getFile("res/values/colors.xml", null, false).edit {
            it.replace("</resources>", """<color name="added_color">#ff00ff00</color></resources>""")
        }
        // A new file resource, a changed one with a new id, a deleted one.
        coder.getFile("res/drawable/new_icon.png", null, false).writeBytes("PNG new".toByteArray())
        coder.getFile("res/layout/main.xml", null, false).edit {
            it.replace("/>", """><View android:id="@+id/new_view"/></LinearLayout>""")
        }
        coder.getFile("res/drawable-xxhdpi/icon.png", null, false).delete()
        // A configuration of a patch.
        coder.getFile("res/values-mcc1001/colors.xml", null, false).apply {
            parentFile.mkdirs()
            writeText("""<resources><color name="accent">#ff0000ff</color></resources>""")
        }

        val output = encode(coder, fullRebuild, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertEquals("com.test.app", pkg.name)

            assertEquals("Hello!", pkg.string("", "hello"))
            assertEquals("Hallo", pkg.string("-de", "hello"))
            assertEquals("Behalten", pkg.string("-de", "keep"))
            assertNull(pkg.string("", "keep"), "a string the file no longer declares is gone")
            assertEquals("Added 'here'", pkg.string("", "added"))
            assertEquals("Test", pkg.string("", "app_name"))
            assertEquals("""It's "quoted"""", pkg.string("", "escaped"))
            val styled = pkg.getEntry("", "string", "styled")!!.resValue
            assertTrue(styled.dataAsPoolString.hasStyle(), "an untouched styled string keeps its spans")

            val style = pkg.getEntry("", "style", "S")!!
            assertTrue(style.isComplex)
            assertEquals(3, style.resTableMapEntry.value.size(), "a bag is replaced, not appended to")

            val array = pkg.getEntry("", "array", "arr")!!.resTableMapEntry.value
            assertEquals(listOf("uno", "dos", "tres"), array.listItems().map { it.valueAsString })

            assertNotNull(pkg.getEntry("", "attr", "newAttr"))
            assertEquals("#ff00ff00", pkg.getEntry("", "color", "added_color")!!.resValue.decodeValue())
            assertEquals("res/color/selector.xml", pkg.getEntry("", "color", "selector")!!.resValue.valueAsString)

            assertEquals("res/drawable/new_icon.png", pkg.getEntry("", "drawable", "new_icon")!!.resValue.valueAsString)
            assertEquals("res/drawable/icon.png", pkg.getEntry("", "drawable", "icon")!!.resValue.valueAsString)
            assertTrue(pkg.getEntry("-xxhdpi", "drawable", "icon")?.isNull ?: true, "a deleted file leaves no value")

            assertNotNull(pkg.getEntry("", "id", "new_view"), "an id a layout adds is allocated")
            assertEquals(ValueType.BOOLEAN, pkg.getEntry("", "id", "existing_view")!!.resValue.valueType)

            val patched = pkg.getOrCreateSpecTypePair("color").getTypeBlock(ResConfig.parse("-mcc1001"))
            assertNotNull(patched)
            assertEquals("#ff0000ff", patched.getEntry("accent")!!.resValue.decodeValue())

            assertEquals("com.test.app", module.androidManifest.packageName)

            val layout = module.getResFile("res/layout/main.xml")
            assertNotNull(layout)
            val document = ResXmlDocument().apply { readBytes(ByteArrayInputStream(layout.inputSource.openStream().readBytes())) }
            assertEquals("View", document.documentElement.elements.asSequence().single().name)
        }

        ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("res/drawable/new_icon.png"))
            assertNull(zip.getEntry("res/drawable-xxhdpi/icon.png"), "a deleted file is not carried over")
            assertNotNull(zip.getEntry("res/color/selector.xml"))
        }
        assertEquals(crcOf(apk, "assets/data.bin"), crcOf(output, "assets/data.bin"), "an untouched entry is reused")
        assertEquals(crcOf(apk, "res/drawable/icon.png"), crcOf(output, "res/drawable/icon.png"))
    }

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `an untouched app keeps its table`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (apk, coder) = decode(fullRebuild, tempDir)

        val output = encode(coder, fullRebuild, tempDir)

        val expected = ApkModule.loadApkFile(apk).use { it.tableBlock.pickOne().dump() }
        val actual = ApkModule.loadApkFile(output).use { it.tableBlock.pickOne().dump() }
        assertContentEquals(expected, actual)
        assertEquals(crcOf(apk, "res/layout/main.xml"), crcOf(output, "res/layout/main.xml"))
    }

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `a renamed package renames the table and the manifest`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (apk, coder) = decode(fullRebuild, tempDir)

        coder.getFile("AndroidManifest.xml", null, false).edit { it.replace("com.test.app", "app.morphe.test") }
        coder.getFile("res/values/strings.xml", null, false).edit { it.replace(">Hello<", ">Hello!<") }

        val output = encode(coder, fullRebuild, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            assertEquals("app.morphe.test", module.androidManifest.packageName)
            val pkg = module.tableBlock.pickOne()
            assertEquals("app.morphe.test", pkg.name)
            assertEquals("Hello!", pkg.string("", "hello"))
            assertEquals("Hallo", pkg.string("-de", "hello"))
        }
        assertEquals(crcOf(apk, "res/drawable/icon.png"), crcOf(output, "res/drawable/icon.png"))
    }

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `deleting some files of a sparse configuration leaves no phantom entries`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (_, coder) = decode(fullRebuild, tempDir)

        // A patch thinning drawables deletes some, not all, files of a density.
        assertTrue(coder.getFile("res/drawable-hdpi/sparse_a.png", null, false).delete())
        assertTrue(coder.getFile("res/drawable-hdpi/sparse_c.png", null, false).delete())

        val output = encode(coder, fullRebuild, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            val hdpi = pkg.getOrCreateSpecTypePair("drawable").getTypeBlock(ResConfig.parse("-hdpi"))
            assertNotNull(hdpi, "the configuration still defines a resource")
            // The full rebuild encodes every app configuration dense; only the incremental path keeps it sparse.
            if (!fullRebuild) assertTrue(hdpi.isSparse)
            assertEquals(
                listOf("sparse_b"),
                hdpi.listEntries(true).map { it.name },
                "only the surviving file has an entry; a phantom pair would resolve to it",
            )
            // A dense chunk, which the full rebuild produces, legitimately keeps null slots.
            if (!fullRebuild) assertEquals(1, hdpi.entryArray.size(), "a sparse chunk holds no null entries")
            assertEquals("res/drawable-hdpi/sparse_b.png", pkg.getEntry("-hdpi", "drawable", "sparse_b")!!.resValue.valueAsString)
            assertTrue(pkg.getEntry("-hdpi", "drawable", "sparse_a")?.isNull ?: true)
            assertTrue(pkg.getEntry("-hdpi", "drawable", "sparse_c")?.isNull ?: true)
        }
        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("res/drawable-hdpi/sparse_a.png"))
            assertNotNull(zip.getEntry("res/drawable-hdpi/sparse_b.png"))
        }
    }

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `a deleted values file takes its resources out of the configuration`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (_, coder) = decode(fullRebuild, tempDir)

        // Patches strip translations by deleting whole locale directories.
        assertTrue(coder.getFile("res/values-de/strings.xml", null, false).parentFile.deleteRecursively())
        coder.getFile("res/values/strings.xml", null, false).edit { it.replace(">Hello<", ">Hello!<") }

        val output = encode(coder, fullRebuild, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertNull(pkg.string("-de", "hello"), "the deleted locale defines nothing")
            assertNull(pkg.string("-de", "keep"))
            assertEquals("Hello!", pkg.string("", "hello"))
            assertEquals("Keep", pkg.string("", "keep"))
        }
    }

    @ParameterizedTest(name = "full rebuild = {0}")
    @ValueSource(booleans = [false, true])
    fun `an id a patch declares in public xml itself is honoured`(fullRebuild: Boolean, @TempDir tempDir: File) {
        val (_, coder) = decode(fullRebuild, tempDir)

        // Some patches append their own <public> declarations instead of relying on allocation.
        val publicXml = coder.getFile("res/values/public.xml", null, false)
        val stringIds = Regex("""<public id="(0x7f[0-9a-f]{6})" type="string"""").findAll(publicXml.readText())
            .map { it.groupValues[1].removePrefix("0x").toLong(16) }.toList()
        val manualId = stringIds.max() + 1
        publicXml.edit {
            it.replace("</resources>", """<public id="0x${manualId.toString(16)}" type="string" name="manual"/></resources>""")
        }
        coder.getFile("res/values/strings.xml", null, false).edit {
            it.replace("</resources>", """<string name="manual">Declared by hand</string></resources>""")
        }

        val output = encode(coder, fullRebuild, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            val entry = pkg.getEntry("", "string", "manual")
            assertNotNull(entry)
            assertEquals(manualId, entry.resourceId.toLong() and 0xffffffffL)
            assertEquals("Declared by hand", entry.resValue.valueAsString)
        }
    }

    private fun PackageBlock.dump(): List<String> = buildList {
        listSpecTypePairs().forEach { pair ->
            pair.forEach { typeBlock ->
                typeBlock.listEntries(true).forEach { entry ->
                    val value = if (entry.isComplex) {
                        entry.resTableMapEntry.value.listItems().joinToString { "${it.nameId}=${it.valueAsString}" }
                    } else {
                        "${entry.resValue.valueType}:${entry.resValue.valueAsString}"
                    }
                    add("${typeBlock.typeName}/${entry.name}[${typeBlock.resConfig.qualifiers}]=$value")
                }
            }
        }
        sort()
    }
}
