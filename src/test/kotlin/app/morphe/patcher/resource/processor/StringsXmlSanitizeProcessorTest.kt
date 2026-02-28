/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

internal object StringsXmlSanitizeProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== sanitizeXmlText tests ====================

    @Test
    fun `sanitizeXmlText preserves valid Unicode characters including U+2190`() {
        // U+2190 is LEFTWARDS ARROW (←), a perfectly valid XML character.
        val input = "<resources><string name=\"arrow\">\u2190 Back</string></resources>"
        val result = sanitizeXmlText(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitizeXmlText removes invalid XML character reference`() {
        // &#65535; is U+FFFF which is not a valid XML 1.0 character.
        val input = "<resources><string name=\"bad\">&#65535;</string></resources>"
        val expected = "<resources><string name=\"bad\"></string></resources>"
        assertEquals(expected, sanitizeXmlText(input))
    }

    @Test
    fun `sanitizeXmlText removes literal invalid Unicode character U+FFFF`() {
        val input = "<resources><string name=\"bad\">\uFFFF</string></resources>"
        val expected = "<resources><string name=\"bad\"></string></resources>"
        assertEquals(expected, sanitizeXmlText(input))
    }

    @Test
    fun `sanitizeXmlText preserves valid numeric character references`() {
        // &#8592; is U+2190 (←)
        val input = "<resources><string name=\"arrow\">&#8592;</string></resources>"
        val result = sanitizeXmlText(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitizeXmlText preserves standard XML whitespace characters`() {
        val input = "<resources>\t\n\r</resources>"
        assertEquals(input, sanitizeXmlText(input))
    }

    // ==================== StringsXmlSanitizeProcessor round-trip tests ====================

    @Test
    fun `process reads and writes strings xml as UTF-8 preserving U+2190`() {
        // Arrange: create a fake package directory structure with a strings.xml
        // that contains U+2190 (←), written as proper UTF-8 bytes.
        val packageDir = tempDir.resolve("com.test.app")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        val xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<resources><string name=\"arrow\">\u2190 Back</string></resources>"
        // Write with explicit UTF-8 to set up the test fixture.
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlSanitizeProcessor(
            get = { _, _ -> tempDir },
            packageDirectories = mapOf("com.test.app" to packageDir),
        )

        // Act
        processor.process()

        // Assert: read the raw bytes back and verify the UTF-8 encoding of ← (U+2190)
        // is intact: 0xE2 0x86 0x90 — NOT 0xE2 0x86 0x3F (which would indicate
        // a default-charset corruption on Windows).
        val resultBytes = stringsXml.readBytes()
        val resultText = String(resultBytes, Charsets.UTF_8)

        // The arrow character must survive the round-trip.
        assert(resultText.contains("\u2190")) {
            "Expected U+2190 (←) to be preserved, but got: $resultText"
        }

        // Verify at the byte level that 0xE2 0x86 0x90 appears and 0x3F does not
        // replace the third byte.
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())

        assert(containsSubArray(resultBytes, arrowUtf8)) {
            "Expected UTF-8 bytes E2 86 90 for U+2190, but they were not found."
        }
        assert(!containsSubArray(resultBytes, corruptedArrow)) {
            "Found corrupted bytes E2 86 3F — indicates default charset was used instead of UTF-8."
        }
    }

    @Test
    fun `emulate Windows default charset corruption of U+2190 and verify fix`() {
        // On Windows, the default charset is typically Windows-1252. When UTF-8 bytes
        // are read through Windows-1252 and written back, multi-byte sequences get
        // corrupted. This test emulates that exact scenario on any platform.
        val xmlContent = "<resources><string name=\"arrow\">\u2190 Back</string></resources>"

        // Step 1: Write the file as proper UTF-8 (this is the starting state on disk).
        val corruptedFile = tempDir.resolve("corrupted_strings.xml")
        corruptedFile.writeText(xmlContent, Charsets.UTF_8)

        // Step 2: Emulate the OLD buggy code path that used platform default charset.
        // On Windows, the default charset would be Windows-1252. We simulate this by
        // explicitly using Windows-1252 for the read/write round-trip.
        val windows1252 = charset("Windows-1252")
        val readViaWindows1252 = corruptedFile.readText(windows1252)
        corruptedFile.writeText(sanitizeXmlText(readViaWindows1252), windows1252)

        // Step 3: Verify the corruption actually happened.
        // U+2190 in UTF-8 is 0xE2 0x86 0x90. When these bytes are interpreted as
        // Windows-1252, byte 0x90 is undefined and gets replaced with '?' (0x3F)
        // during encoding back to Windows-1252.
        val corruptedBytes = corruptedFile.readBytes()
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())
        assert(containsSubArray(corruptedBytes, corruptedArrow)) {
            "Expected corruption (E2 86 3F) when emulating Windows-1252 round-trip, " +
                "but bytes were: ${corruptedBytes.joinToString(" ") { "%02X".format(it) }}"
        }

        // Step 4: Now verify the FIXED code path (explicit UTF-8) preserves U+2190.
        val packageDir = tempDir.resolve("com.test.windows")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlSanitizeProcessor(
            get = { _, _ -> tempDir },
            packageDirectories = mapOf("com.test.windows" to packageDir),
        )

        processor.process()

        val fixedBytes = stringsXml.readBytes()
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        assert(containsSubArray(fixedBytes, arrowUtf8)) {
            "Expected correct UTF-8 bytes E2 86 90 for U+2190 after processor, " +
                "but bytes were: ${fixedBytes.joinToString(" ") { "%02X".format(it) }}"
        }
        assert(!containsSubArray(fixedBytes, corruptedArrow)) {
            "Found corrupted bytes E2 86 3F after processor — UTF-8 was not used."
        }
    }

    @Test
    fun `process removes invalid characters and writes result as UTF-8`() {
        val packageDir = tempDir.resolve("com.test.app2")
        val resValuesDir = packageDir.resolve("res/values")
        resValuesDir.mkdirs()

        val stringsXml = resValuesDir.resolve("strings.xml")
        // Include both a valid char (←) and an invalid one (U+FFFF).
        val xmlContent = "<resources><string name=\"mixed\">\u2190\uFFFF</string></resources>"
        stringsXml.writeText(xmlContent, Charsets.UTF_8)

        val processor = StringsXmlSanitizeProcessor(
            get = { _, _ -> tempDir },
            packageDirectories = mapOf("com.test.app2" to packageDir),
        )

        processor.process()

        val resultText = stringsXml.readText(Charsets.UTF_8)

        // U+2190 should be preserved, U+FFFF should be removed.
        assert(resultText.contains("\u2190")) { "U+2190 should be preserved" }
        assert(!resultText.contains("\uFFFF")) { "U+FFFF should be removed" }
    }

    // Helper to check if a byte array contains a sub-array.
    private fun containsSubArray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            if (haystack.sliceArray(i until i + needle.size).contentEquals(needle)) {
                return true
            }
        }
        return false
    }
}


