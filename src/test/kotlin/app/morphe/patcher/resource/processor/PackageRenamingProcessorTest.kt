/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object PackageRenamingProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== Package renaming logic tests ====================

    @Test
    fun `processFile replaces package name in attribute references`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                    <TextView android:text="@com.original.app:string/hello"/>
                </LinearLayout>
            """.trimIndent(),
        )

        assertContains(result, "@com.new.app:string/hello")
        assertFalse(result.contains("@com.original.app:string/hello"))
    }

    @Test
    fun `processFile replaces package name in question-mark references`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <View xmlns:android="http://schemas.android.com/apk/res/android"
                    android:background="?com.original.app:attr/colorPrimary"/>
            """.trimIndent(),
        )

        assertContains(result, "?com.new.app:attr/colorPrimary")
        assertFalse(result.contains("?com.original.app:attr/colorPrimary"))
    }

    @Test
    fun `processFile does not replace non-matching attributes`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <View xmlns:android="http://schemas.android.com/apk/res/android"
                    android:text="hello world"/>
            """.trimIndent(),
        )

        assertContains(result, "hello world")
    }

    @Test
    fun `processFile replaces package name in text content`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <resources package="com.example.package" id="0x7f">
                    <item>@com.original.app:id/myId</item>
                </resources>
            """.trimIndent(),
        )

        assertContains(result, "@com.new.app:id/myId")
    }

    @Test
    fun `processFile is a no-op when text does not match pattern`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <resources package="com.example.package" id="0x7f">
                    <item>plain text no refs</item>
                </resources>
            """.trimIndent(),
        )

        assertContains(result, "plain text no refs")
    }

    @Test
    fun `processFile handles multiple attributes with package references`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <View xmlns:android="http://schemas.android.com/apk/res/android"
                    android:text="@com.original.app:string/a"
                    android:hint="@com.original.app:string/b"/>
            """.trimIndent(),
        )

        assertContains(result, "@com.new.app:string/a")
        assertContains(result, "@com.new.app:string/b")
        assertFalse(result.contains("com.original.app"))
    }

    // ==================== Namespace preservation tests ====================

    @Test
    fun `processFile preserves XML namespace prefix declarations`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="utf-8" standalone="no"?><selector xmlns:android="http://schemas.android.com/apk/res/android">
  <item android:color="?com.original.app:attr/rdt_ds_color_tone2" android:state_enabled="false"/>
  <item android:color="?com.original.app:attr/rdt_ds_color_primary"/>
</selector>""",
        )

        // Namespace prefix must be preserved as "android", not renamed to "n0", "n1", etc.
        assertContains(result, "xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        assertContains(result, "android:color=\"?com.new.app:attr/rdt_ds_color_tone2\"")
        assertContains(result, "android:state_enabled=")
        assertContains(result, "android:color=\"?com.new.app:attr/rdt_ds_color_primary\"")
        assertFalse(result.contains("com.original.app"), "Original package name should be replaced")
        assertFalse(result.contains("xmlns:n0"), "Namespace prefix should not be auto-generated as n0")
        assertFalse(result.contains("xmlns:n1"), "Namespace prefix should not be auto-generated as n1")
    }

    @Test
    fun `processFile preserves multiple namespace prefixes`() {
        val result = processFileAndRead(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    android:layout_width="match_parent"
                    app:layout_constraintTop_toTopOf="parent">
                    <TextView android:text="@com.original.app:string/hello"
                        app:someAttr="@com.original.app:attr/custom"/>
                </LinearLayout>
            """.trimIndent(),
        )

        assertContains(result, "xmlns:android=")
        assertContains(result, "xmlns:app=")
        assertContains(result, "android:layout_width=")
        assertContains(result, "app:layout_constraintTop_toTopOf=")
        assertContains(result, "@com.new.app:string/hello")
        assertContains(result, "@com.new.app:attr/custom")
    }

    // ==================== UTF-8 encoding tests ====================

    @Test
    fun `processFile preserves UTF-8 characters in text and attributes`() {
        val (_, bytes) = processFileAndReadBytes(
            xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
                <resources package="com.example.package" id="0x7f">
                    <item name="café">@com.original.app:string/héllo</item>
                </resources>
            """.trimIndent(),
        )

        // é (U+00E9) in UTF-8 is 0xC3 0xA9
        val eAcuteUtf8 = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertTrue(
            containsSubArray(bytes, eAcuteUtf8),
            "Expected UTF-8 bytes C3 A9 for é, bytes: ${bytes.hex()}",
        )
    }

    @Test
    fun `processFile preserves U+2190 in UTF-8 output`() {
        val (text, bytes) = processFileAndReadBytes(
            xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources package=\"com.example.package\" id=\"0x7f\"><item name=\"\u2190\">@com.original.app:string/back</item></resources>",
        )

        // ← (U+2190) in UTF-8 is E2 86 90
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        assertTrue(
            containsSubArray(bytes, arrowUtf8),
            "Expected UTF-8 bytes E2 86 90 for U+2190, bytes: ${bytes.hex()}",
        )

        // Verify the replacement also happened
        assertContains(text, "@com.new.app:string/back")
    }

    @Test
    fun `emulate Windows default charset corruption and verify fix`() {
        val xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<resources package=\"com.example.package\" id=\"0x7f\"><item name=\"café\">\u2190 text</item></resources>"

        // Step 1: Write UTF-8.
        val corruptedFile = tempDir.resolve("corrupted_rename.xml")
        corruptedFile.writeText(xmlContent, Charsets.UTF_8)

        // Step 2: Emulate Windows-1252 round-trip.
        val windows1252 = charset("Windows-1252")
        val readVia1252 = corruptedFile.readText(windows1252)
        corruptedFile.writeText(readVia1252, windows1252)

        // Step 3: Verify corruption.
        val corruptedBytes = corruptedFile.readBytes()
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())
        assertTrue(
            containsSubArray(corruptedBytes, corruptedArrow),
            "Expected corruption (E2 86 3F) when emulating Windows-1252, bytes: ${corruptedBytes.hex()}",
        )

        // Step 4: Verify the fixed processor preserves UTF-8.
        val (_, fixedBytes) = processFileAndReadBytes(
            xmlContent = xmlContent,
        )

        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        assertTrue(
            containsSubArray(fixedBytes, arrowUtf8),
            "Expected correct UTF-8 bytes E2 86 90 for U+2190, bytes: ${fixedBytes.hex()}",
        )
        assertFalse(
            containsSubArray(fixedBytes, corruptedArrow),
            "Found corrupted bytes E2 86 3F — UTF-8 was not used",
        )
    }

    // ==================== Helpers ====================

    private fun processFileAndRead(xmlContent: String): String {
        return processFileAndReadBytes(xmlContent).first
    }

    private fun processFileAndReadBytes(xmlContent: String): Pair<String, ByteArray> {
        val originalPackageName = "com.original.app"
        val newPackageName = "com.new.app"

        // PackageRenamingProcessor.process() filters out the original package, so we need
        // to set up a "different" package directory that contains the XML.
        val otherPkgName = "com.other.pkg"
        val packageDir = tempDir.resolve("pkg_${System.nanoTime()}")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        // Create the XML file (not named strings.xml, because process() excludes those)
        val xmlFile = resValuesDir.resolve("attrs.xml")
        xmlFile.writeText(xmlContent, Charsets.UTF_8)

        // Create a minimal public.xml for the PublicXmlManager
        val publicXmlDir = tempDir.resolve("public_${System.nanoTime()}")
        publicXmlDir.mkdirs()
        val publicXmlFile = publicXmlDir.resolve("public.xml")
        publicXmlFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><resources package="$originalPackageName" id="0x7f"></resources>""",
            Charsets.UTF_8,
        )

        // Create a package.json
        val packageJsonFile = packageDir.resolve("package.json")
        packageJsonFile.writeText("""{"package_name":"$originalPackageName"}""", Charsets.UTF_8)

        val publicXmlManager = app.morphe.patcher.resource.PublicXmlManager(publicXmlFile)

        val processor = PackageRenamingProcessor(
            get = { path, _ ->
                if (path == "package.json") packageJsonFile
                else throw IllegalArgumentException("Unexpected get call: $path")
            },
            publicXmlManager = publicXmlManager,
            packageDirectories = mapOf(
                // The original package is skipped, so we put the test data under a different key
                originalPackageName to packageDir,
                otherPkgName to packageDir,
            ),
            originalPackageName = originalPackageName,
            newPackageName = newPackageName,
        )

        processor.process()
        publicXmlManager.close()

        val bytes = xmlFile.readBytes()
        return Pair(String(bytes, Charsets.UTF_8), bytes)
    }

    private fun containsSubArray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            if (haystack.sliceArray(i until i + needle.size).contentEquals(needle)) return true
        }
        return false
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
}

