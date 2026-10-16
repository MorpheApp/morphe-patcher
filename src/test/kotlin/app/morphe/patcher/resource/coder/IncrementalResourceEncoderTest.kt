/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes a small APK, edits its resources the way patches do, encodes it again and checks the
 * result. Runs against the incremental encoder, against the full rebuild, and with resources decoded
 * on request, which must all agree.
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

    /**
     * How resources are decoded and encoded.
     */
    enum class Mode {
        /** Every resource is decoded up front, and the changed ones are encoded into the input table. */
        INCREMENTAL,

        /** Every resource is decoded up front, and the table is rebuilt from every values file. */
        FULL_REBUILD,

        /** Resources are decoded as they are requested, and the changed ones are encoded into the input table. */
        ON_REQUEST,
    }

    /** Decodes a fresh input APK with a coder on the requested path. */
    private fun decode(mode: Mode, tempDir: File): Pair<File, ArsclibResourceCoder> {
        val apk = ResourceTestApk.build(tempDir)
        val coder = ArsclibResourceCoder(
            tempDir.resolve("working").apply { mkdirs() },
            apk,
            fullResourceEncode = mode == Mode.FULL_REBUILD,
        )
        coder.decodeResources(onRequest = mode == Mode.ON_REQUEST)
        return apk to coder
    }

    /** Encodes, and on the incremental path checks it did not fall back to the full rebuild. */
    private fun encode(
        coder: ArsclibResourceCoder,
        mode: Mode,
        tempDir: File,
        omitEntry: (String) -> Boolean = { false },
    ): File {
        val output = coder.encodeResources(tempDir.resolve("out").apply { mkdirs() }, omitEntry)
        if (mode != Mode.FULL_REBUILD) assertTrue(
            warnings.none { it.message.orEmpty().contains("rebuilding the table") },
            "The incremental encoder fell back: ${warnings.map { it.message }}",
        )
        return output
    }

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun crcOf(apk: File, entry: String): Long? = ZipFile(apk).use { zip ->
        zip.getEntry(entry)?.let { zip.getInputStream(it).use { input -> CRC32().also { it.update(input.readBytes()) }.value } }
    }

    private fun PackageBlock.string(qualifiers: String, name: String) =
        getEntry(qualifiers, "string", name)?.takeUnless { it.isNull }?.resValue?.valueAsString

    // ==================== Tests ====================

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `edited resources are encoded over the input table`(mode: Mode, @TempDir tempDir: File) {
        val (apk, coder) = decode(mode, tempDir)

        // Strings: modify, delete, add, in one configuration only.
        coder.getFile("res/values/strings.xml", null, true).edit {
            it.replace(">Hello<", ">Hello!<")
                .replace(Regex("""\s*<string name="keep">[^<]*</string>"""), "")
                .replace("</resources>", """<string name="added">Added \'here\'</string></resources>""")
        }
        // A bag gains an item, an array is rebuilt.
        coder.getFile("res/values/attrs.xml", null, true).edit {
            it.replace("</resources>", """<attr name="newAttr" format="boolean"/></resources>""")
        }
        coder.getFile("res/values/styles.xml", null, true).edit {
            it.replace("</style>", """<item name="newAttr">true</item><item name="android:textColor">@color/accent</item></style>""")
        }
        coder.getFile("res/values/arrays.xml", null, true).edit {
            it.replace(Regex("<item>.*</item>", RegexOption.DOT_MATCHES_ALL), "<item>uno</item><item>dos</item><item>tres</item>")
        }
        // A colour is added next to one defined by a file.
        coder.getFile("res/values/colors.xml", null, true).edit {
            it.replace("</resources>", """<color name="added_color">#ff00ff00</color></resources>""")
        }
        // A new file resource, a changed one with a new id, a deleted one.
        coder.getFile("res/drawable/new_icon.png", null, true).writeBytes("PNG new".toByteArray())
        coder.getFile("res/layout/main.xml", null, true).edit {
            it.replace("/>", """><View android:id="@+id/new_view"/></LinearLayout>""")
        }
        coder.getFile("res/drawable-xxhdpi/icon.png", null, true).delete()
        // A configuration of a patch.
        coder.getFile("res/values-mcc1001/colors.xml", null, true).apply {
            parentFile.mkdirs()
            writeText("""<resources><color name="accent">#ff0000ff</color></resources>""")
        }

        val output = encode(coder, mode, tempDir)

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

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `an untouched app keeps its table`(mode: Mode, @TempDir tempDir: File) {
        val (apk, coder) = decode(mode, tempDir)

        val output = encode(coder, mode, tempDir)

        val expected = ApkModule.loadApkFile(apk).use { it.tableBlock.pickOne().dump() }
        val actual = ApkModule.loadApkFile(output).use { it.tableBlock.pickOne().dump() }
        assertContentEquals(expected, actual)
        assertEquals(crcOf(apk, "res/layout/main.xml"), crcOf(output, "res/layout/main.xml"))
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `entries the patched APK replaces are left out`(mode: Mode, @TempDir tempDir: File) {
        val (apk, coder) = decode(mode, tempDir)

        val output = encode(coder, mode, tempDir) { name -> name == "assets/data.bin" }

        assertNotNull(crcOf(apk, "assets/data.bin"))
        assertNull(crcOf(output, "assets/data.bin"))
        assertEquals(crcOf(apk, "res/layout/main.xml"), crcOf(output, "res/layout/main.xml"))
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `a renamed package renames the table and the manifest`(mode: Mode, @TempDir tempDir: File) {
        val (apk, coder) = decode(mode, tempDir)

        coder.getFile("AndroidManifest.xml", null, true).edit { it.replace("com.test.app", "app.morphe.test") }
        coder.getFile("res/values/strings.xml", null, true).edit { it.replace(">Hello<", ">Hello!<") }

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            assertEquals("app.morphe.test", module.androidManifest.packageName)
            val pkg = module.tableBlock.pickOne()
            assertEquals("app.morphe.test", pkg.name)
            assertEquals("Hello!", pkg.string("", "hello"))
            assertEquals("Hallo", pkg.string("-de", "hello"))
        }
        assertEquals(crcOf(apk, "res/drawable/icon.png"), crcOf(output, "res/drawable/icon.png"))
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `deleting some files of a sparse configuration leaves no phantom entries`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        // A patch thinning drawables deletes some, not all, files of a density.
        assertTrue(coder.getFile("res/drawable-hdpi/sparse_a.png", null, true).delete())
        assertTrue(coder.getFile("res/drawable-hdpi/sparse_c.png", null, true).delete())

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            val hdpi = pkg.getOrCreateSpecTypePair("drawable").getTypeBlock(ResConfig.parse("-hdpi"))
            assertNotNull(hdpi, "the configuration still defines a resource")
            // The full rebuild encodes every app configuration dense; only the incremental path keeps it sparse.
            if (mode != Mode.FULL_REBUILD) assertTrue(hdpi.isSparse)
            assertEquals(
                listOf("sparse_b"),
                hdpi.listEntries(true).map { it.name },
                "only the surviving file has an entry; a phantom pair would resolve to it",
            )
            // A dense chunk, which the full rebuild produces, legitimately keeps null slots.
            if (mode != Mode.FULL_REBUILD) assertEquals(1, hdpi.entryArray.size(), "a sparse chunk holds no null entries")
            assertEquals("res/drawable-hdpi/sparse_b.png", pkg.getEntry("-hdpi", "drawable", "sparse_b")!!.resValue.valueAsString)
            assertTrue(pkg.getEntry("-hdpi", "drawable", "sparse_a")?.isNull ?: true)
            assertTrue(pkg.getEntry("-hdpi", "drawable", "sparse_c")?.isNull ?: true)
        }
        ZipFile(output).use { zip ->
            assertNull(zip.getEntry("res/drawable-hdpi/sparse_a.png"))
            assertNotNull(zip.getEntry("res/drawable-hdpi/sparse_b.png"))
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `a deleted values file takes its resources out of the configuration`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        // Patches strip translations by deleting whole locale directories.
        assertTrue(coder.getFile("res/values-de/strings.xml", null, true).parentFile.deleteRecursively())
        coder.getFile("res/values/strings.xml", null, true).edit { it.replace(">Hello<", ">Hello!<") }

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            assertNull(pkg.string("-de", "hello"), "the deleted locale defines nothing")
            assertNull(pkg.string("-de", "keep"))
            assertEquals("Hello!", pkg.string("", "hello"))
            assertEquals("Keep", pkg.string("", "keep"))
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `strings no entry uses any more leave the string pool`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        assertTrue(coder.getFile("res/values-de/strings.xml", null, true).parentFile.deleteRecursively())
        coder.getFile("res/values/strings.xml", null, true).edit { it.replace(">Hello<", ">Hello!<") }

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pool = module.tableBlock.stringPool
            val strings = (0 until pool.size()).mapNotNull { pool[it]?.get() }.toSet()
            listOf("Hallo", "Behalten", "Hello").forEach {
                assertFalse(it in strings, "\"$it\" is no longer used but is still in the pool")
            }
            listOf("Hello!", "Keep", "res/drawable/icon.png").forEach {
                assertTrue(it in strings, "\"$it\" is still used but left the pool")
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `an id a patch declares in public xml itself is honoured`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        // Some patches append their own <public> declarations instead of relying on allocation.
        val publicXml = coder.getFile("res/values/public.xml", null, true)
        val stringIds = Regex("""<public id="(0x7f[0-9a-f]{6})" type="string"""").findAll(publicXml.readText())
            .map { it.groupValues[1].removePrefix("0x").toLong(16) }.toList()
        val manualId = stringIds.max() + 1
        publicXml.edit {
            it.replace("</resources>", """<public id="0x${manualId.toString(16)}" type="string" name="manual"/></resources>""")
        }
        coder.getFile("res/values/strings.xml", null, true).edit {
            it.replace("</resources>", """<string name="manual">Declared by hand</string></resources>""")
        }

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            val entry = pkg.getEntry("", "string", "manual")
            assertNotNull(entry)
            assertEquals(manualId, entry.resourceId.toLong() and 0xffffffffL)
            assertEquals("Declared by hand", entry.resValue.valueAsString)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `a resource sharing a file keeps its archive name when its values file is edited`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        // The decoded values file names the shared file by its alias, which is not in the archive.
        coder.getFile("res/values/colors.xml", null, true).edit { it.replace(">#ffff0000<", ">#ff00ff00<") }

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            listOf("shared_a", "shared_b").forEach { name ->
                val path = pkg.getEntry("", "color", name)!!.resValue.valueAsString
                assertTrue(module.zipEntryMap.contains(path), "$name names $path, which is not in the archive")
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mode::class)
    fun `overlayables a patch declares are added`(mode: Mode, @TempDir tempDir: File) {
        val (_, coder) = decode(mode, tempDir)

        // The app declares none, so there is no file to decode, and the patch writes a new one.
        coder.getFile("res/values/overlayable.xml", null, true).writeText(
            """<resources><overlayable name="Theme"><policy type="public">""" +
                    """<item type="color" name="accent"/></policy></overlayable></resources>"""
        )

        val output = encode(coder, mode, tempDir)

        ApkModule.loadApkFile(output).use { module ->
            val pkg = module.tableBlock.pickOne()
            val overlayable = pkg.overlayableList.get("Theme")
            assertNotNull(overlayable)
            assertEquals(1, pkg.overlayableList.size())
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
