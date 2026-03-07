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

internal object StringsXmlEscapeProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== Escape logic tests ====================

    @Test
    fun `process escapes backslash in string values`() {
        val result = processAndRead("""<resources><string name="t">a\b</string></resources>""")
        assertContains(result, """a\\b""")
    }

    @Test
    fun `process escapes single quotes in string values`() {
        val result = processAndRead("""<resources><string name="t">it's</string></resources>""")
        assertContains(result, """it\'s""")
    }

    @Test
    fun `process escapes double quotes in string values`() {
        val result = processAndRead("""<resources><string name="t">say "hi"</string></resources>""")
        assertContains(result, """say \"hi\"""")
    }

    @Test
    fun `process escapes newlines and tabs in string values`() {
        val result = processAndRead("<resources><string name=\"t\">line1\nline2\ttab</string></resources>")
        assertContains(result, "\\n")
        assertContains(result, "\\t")
    }

    @Test
    fun `process escapes non-ASCII Unicode characters as unicode escapes`() {
        // U+2190 (←) should become \u2190
        val result = processAndRead("<resources><string name=\"t\">\u2190 Back</string></resources>")
        assertContains(result, "\\u2190")
    }

    @Test
    fun `process does not escape text outside string tags`() {
        val result = processAndRead("<resources><other name=\"t\">it's fine</other></resources>")
        // Text outside <string> tags should not be escaped
        assertFalse(result.contains("\\'"), "Text outside <string> should not be escaped")
    }

    @Test
    fun `process handles empty string elements`() {
        // Just verify it doesn't crash
        processAndRead("""<resources><string name="empty"></string></resources>""")
    }

    @Test
    fun `process handles multiple string elements`() {
        val input = """<resources><string name="a">hello</string><string name="b">world</string></resources>"""
        val result = processAndRead(input)
        assertContains(result, "hello")
        assertContains(result, "world")
    }

    // ==================== Namespace preservation tests ====================

    @Test
    fun `process preserves XML namespace prefix declarations`() {
        val result = processAndRead(
            """<resources xmlns:android="http://schemas.android.com/apk/res/android"><string name="t" android:translatable="false">it's</string></resources>""",
        )

        assertContains(result, "xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        assertContains(result, "android:translatable=")
        assertFalse(result.contains("xmlns:n0"), "Namespace prefix should not be auto-generated as n0")
        assertFalse(result.contains("xmlns:n1"), "Namespace prefix should not be auto-generated as n1")
    }

    @Test
    fun `process preserves multiple namespace prefixes`() {
        val result = processAndRead(
            """<resources xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"><string name="t" tools:ignore="Typos" android:translatable="false">hello</string></resources>""",
        )

        assertContains(result, "xmlns:android=")
        assertContains(result, "xmlns:tools=")
        assertContains(result, "android:translatable=")
        assertContains(result, "tools:ignore=")
        assertFalse(result.contains("xmlns:n0"), "Namespace prefix should not be auto-generated")
        assertFalse(result.contains("xmlns:n1"), "Namespace prefix should not be auto-generated")
    }

    // ==================== UTF-8 encoding tests ====================

    @Test
    fun `process writes output as UTF-8`() {
        // The escape processor converts non-ASCII to \uXXXX, but the XML
        // structure itself (tags, attributes) must still be valid UTF-8.
        val input = "<resources><string name=\"t\">\u2190 Back</string></resources>"
        val (_, bytes) = processAndReadBytes(input)

        // The output should be valid UTF-8
        val decoded = String(bytes, Charsets.UTF_8)
        assertContains(decoded, "\\u2190")
    }

    @Test
    fun `process preserves UTF-8 encoding for attribute values containing non-ASCII`() {
        val input = "<resources><string name=\"arrów\">text</string></resources>"
        val (_, bytes) = processAndReadBytes(input)

        // Verify the attribute name survives as UTF-8 (ó is U+00F3, encoded as 0xC3 0xB3)
        val oAcuteUtf8 = byteArrayOf(0xC3.toByte(), 0xB3.toByte())
        assertTrue(
            containsSubArray(bytes, oAcuteUtf8),
            "Expected UTF-8 bytes C3 B3 for ó in attribute value, but bytes were: ${bytes.hex()}",
        )
    }

    @Test
    fun `emulate Windows default charset corruption and verify fix`() {
        // Simulate the Windows scenario: write UTF-8 bytes, read back through
        // Windows-1252, and verify it corrupts. Then verify the fixed processor doesn't.
        // Use U+2190 (←) which is NOT representable in Windows-1252, so the round-trip
        // will corrupt it. (Characters like ó are in Windows-1252 and survive.)
        val xmlContent = "<resources><string name=\"arr\u2190w\">plain text</string></resources>"

        // Step 1: Write proper UTF-8 to disk.
        val corruptedFile = tempDir.resolve("corrupted_escape.xml")
        corruptedFile.writeText(xmlContent, Charsets.UTF_8)

        // Step 2: Emulate the buggy read → write through Windows-1252.
        val windows1252 = charset("Windows-1252")
        val readVia1252 = corruptedFile.readText(windows1252)
        corruptedFile.writeText(readVia1252, windows1252)

        // Step 3: U+2190 in UTF-8 is 0xE2 0x86 0x90. Byte 0x90 is undefined in
        // Windows-1252, so it gets replaced with '?' (0x3F).
        val corruptedBytes = corruptedFile.readBytes()
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())
        assertTrue(
            containsSubArray(corruptedBytes, corruptedArrow),
            "Expected corruption (E2 86 3F) when round-tripping through Windows-1252, " +
                "bytes: ${corruptedBytes.hex()}",
        )

        // Step 4: Now verify the FIXED processor preserves UTF-8.
        val packageDir = tempDir.resolve("com.test.escape.win")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlEscapeProcessor(
            get = { _, _ -> stringsXml },
            packageDirectories = mapOf("com.test.escape.win" to packageDir),
        )
        processor.process()

        val fixedBytes = stringsXml.readBytes()
        // The escape processor converts ← to \u2190 in string text, but in the attribute
        // it should still survive as UTF-8 bytes. The key: no corruption bytes (E2 86 3F).
        assertFalse(
            containsSubArray(fixedBytes, corruptedArrow),
            "Found corrupted bytes E2 86 3F — UTF-8 was not used",
        )
    }

    // ==================== Helpers ====================

    /**
     * Create a strings.xml file with the given content, run the escape processor, and return the result as text.
     */
    private fun processAndRead(xmlContent: String): String {
        val packageDir = tempDir.resolve("pkg_${System.nanoTime()}")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlEscapeProcessor(
            get = { _, _ -> stringsXml },
            packageDirectories = mapOf("com.test" to packageDir),
        )

        processor.process()
        return stringsXml.readText(Charsets.UTF_8)
    }

    /**
     * Like processAndRead but also returns raw bytes for byte-level assertions.
     */
    private fun processAndReadBytes(xmlContent: String): Pair<String, ByteArray> {
        val packageDir = tempDir.resolve("pkg_${System.nanoTime()}")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlEscapeProcessor(
            get = { _, _ -> stringsXml },
            packageDirectories = mapOf("com.test" to packageDir),
        )

        processor.process()
        val bytes = stringsXml.readBytes()
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


