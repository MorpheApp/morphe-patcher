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

internal object StringsXmlUnEscapeProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== Unescape logic tests ====================

    @Test
    fun `process unescapes backslash sequences in string values`() {
        val result = processAndRead("""<resources><string name="t">a\\b</string></resources>""")
        assertContains(result, "a\\b")
    }

    @Test
    fun `process unescapes escaped single quote`() {
        val result = processAndRead("""<resources><string name="t">it\'s</string></resources>""")
        assertContains(result, "it's")
    }

    @Test
    fun `process unescapes escaped double quotes`() {
        val result = processAndRead("""<resources><string name="t">say \"hi\"</string></resources>""")
        assertContains(result, "say \"hi\"")
    }

    @Test
    fun `process unescapes escaped newline and tab`() {
        val result = processAndRead("""<resources><string name="t">line1\nline2\ttab</string></resources>""")
        // After unescaping, \n becomes actual newline and \t becomes actual tab
        assertTrue(
            result.contains("\n") || result.contains("&#10;"),
            "Expected unescaped newline in output",
        )
    }

    @Test
    fun `process unescapes unicode escape sequences`() {
        // \u2190 is ← (LEFTWARDS ARROW)
        val result = processAndRead("""<resources><string name="t">\u2190 Back</string></resources>""")
        // After unescaping, \u2190 should become the literal ← character
        assertTrue(
            result.contains("\u2190") || result.contains("&#8592;"),
            "Expected unescaped U+2190 (←) in output, but got: $result",
        )
    }

    @Test
    fun `process unescapes carriage return`() {
        val result = processAndRead("""<resources><string name="t">line1\rline2</string></resources>""")
        assertTrue(
            result.contains("\r") || result.contains("&#13;"),
            "Expected unescaped carriage return in output",
        )
    }

    @Test
    fun `process handles invalid unicode escape gracefully`() {
        // \uZZZZ is not valid hex — should be left as-is
        val result = processAndRead("""<resources><string name="t">\uZZZZ</string></resources>""")
        assertContains(result, "\\u")
    }

    @Test
    fun `process handles truncated unicode escape gracefully`() {
        // \u21 is too short — should be left as-is
        val result = processAndRead("""<resources><string name="t">\u21</string></resources>""")
        assertContains(result, "\\u")
    }

    @Test
    fun `process strips surrounding double quotes from string values`() {
        val result = processAndRead("""<resources><string name="t">"hello world"</string></resources>""")
        // The unescaper strips surrounding quotes
        assertContains(result, "hello world")
    }

    @Test
    fun `process does not unescape text outside string tags`() {
        val result = processAndRead("""<resources><other name="t">it\'s</other></resources>""")
        // Text outside <string> should not be unescaped
        assertContains(result, "\\'")
    }

    @Test
    fun `process handles empty string elements`() {
        // Just verify no crash
        processAndRead("""<resources><string name="empty"></string></resources>""")
    }

    @Test
    fun `process handles multiple string elements`() {
        val input = """<resources><string name="a">hello\\world</string><string name="b">foo\'bar</string></resources>"""
        val result = processAndRead(input)
        assertContains(result, "hello\\world")
        assertContains(result, "foo'bar")
    }

    @Test
    fun `process preserves unknown escape sequences`() {
        // \z is not a known escape — keep the backslash
        val result = processAndRead("""<resources><string name="t">\z</string></resources>""")
        assertContains(result, "\\")
    }

    // ==================== Namespace preservation tests ====================

    @Test
    fun `process preserves XML namespace prefix declarations`() {
        val result = processAndRead(
            """<resources xmlns:android="http://schemas.android.com/apk/res/android"><string name="t" android:translatable="false">it\'s</string></resources>""",
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
    fun `process reads and writes UTF-8 preserving non-ASCII in attributes`() {
        // The attribute name contains é (U+00E9).
        val input = "<resources><string name=\"café\">hello</string></resources>"
        val (_, bytes) = processAndReadBytes(input)

        // é in UTF-8 is 0xC3 0xA9 — verify it survived
        val eAcuteUtf8 = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertTrue(
            containsSubArray(bytes, eAcuteUtf8),
            "Expected UTF-8 bytes C3 A9 for é in attribute value, bytes: ${bytes.hex()}",
        )
    }

    @Test
    fun `process writes unescaped Unicode characters as valid UTF-8`() {
        // Input has \u2190 which should be unescaped to ← (U+2190, UTF-8: E2 86 90)
        val input = """<resources><string name="t">\u2190</string></resources>"""
        val (_, bytes) = processAndReadBytes(input)

        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        assertTrue(
            containsSubArray(bytes, arrowUtf8),
            "Expected UTF-8 bytes E2 86 90 for U+2190 in output, bytes: ${bytes.hex()}",
        )
    }

    @Test
    fun `emulate Windows default charset corruption and verify fix`() {
        // Demonstrate the Windows corruption scenario for the unescape processor.
        // Use U+2190 (←) which is NOT representable in Windows-1252.
        val xmlContent = "<resources><string name=\"caf\u2190\">hello</string></resources>"

        // Step 1: Write proper UTF-8.
        val corruptedFile = tempDir.resolve("corrupted_unescape.xml")
        corruptedFile.writeText(xmlContent, Charsets.UTF_8)

        // Step 2: Emulate buggy Windows-1252 read/write round-trip.
        val windows1252 = charset("Windows-1252")
        val readVia1252 = corruptedFile.readText(windows1252)
        corruptedFile.writeText(readVia1252, windows1252)

        // Step 3: Verify corruption. U+2190 UTF-8 byte 0x90 is undefined in Windows-1252
        // and gets replaced with '?' (0x3F).
        val corruptedBytes = corruptedFile.readBytes()
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())
        assertTrue(
            containsSubArray(corruptedBytes, corruptedArrow),
            "Expected corruption (E2 86 3F) when round-tripping through Windows-1252, " +
                "bytes: ${corruptedBytes.hex()}",
        )

        // Step 4: Verify the fixed processor preserves UTF-8.
        val packageDir = tempDir.resolve("com.test.unescape.win")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlUnEscapeProcessor(
            get = { _, _ -> stringsXml },
            packageDirectories = mapOf("com.test.unescape.win" to packageDir),
        )
        processor.process()

        val fixedBytes = stringsXml.readBytes()

        // ← (U+2190) in UTF-8 is E2 86 90
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        assertTrue(
            containsSubArray(fixedBytes, arrowUtf8),
            "Expected UTF-8 bytes E2 86 90 for U+2190 after processor, bytes: ${fixedBytes.hex()}",
        )
        assertFalse(
            containsSubArray(fixedBytes, corruptedArrow),
            "Found corrupted bytes E2 86 3F — UTF-8 was not used",
        )
    }

    // ==================== Helpers ====================

    private fun processAndRead(xmlContent: String): String {
        val packageDir = tempDir.resolve("pkg_${System.nanoTime()}")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlUnEscapeProcessor(
            get = { _, _ -> stringsXml },
            packageDirectories = mapOf("com.test" to packageDir),
        )

        processor.process()
        return stringsXml.readText(Charsets.UTF_8)
    }

    private fun processAndReadBytes(xmlContent: String): Pair<String, ByteArray> {
        val packageDir = tempDir.resolve("pkg_${System.nanoTime()}")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlUnEscapeProcessor(
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

