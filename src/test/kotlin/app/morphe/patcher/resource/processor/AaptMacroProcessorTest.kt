/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object AaptMacroProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== aapt:attr extraction tests ====================

    @Test
    fun `process extracts aapt attr drawable into a new file`() {
        val addedResources = mutableSetOf<File>()
        val xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
            <!--
                Placeholder comment to ensure processor handles comments
            -->
            <vector xmlns:aapt="http://schemas.android.com/aapt"
                    xmlns:android="http://schemas.android.com/apk/res/android">
                <path>
                    <aapt:attr name="android:fillColor">
                        <gradient android:startColor="#FF0000" android:endColor="#0000FF"/>
                    </aapt:attr>
                </path>
            </vector>
        """.trimIndent()

        val (sourceFile, _) = setupAndProcess(xmlContent, addedResources)

        // Verify the aapt:attr was replaced with a reference
        val resultText = sourceFile.readText(Charsets.UTF_8)
        assertTrue(
            resultText.contains("@drawable/\$test_icon__0"),
            "Expected drawable reference, got: $resultText",
        )

        // Verify the aapt:attr element was removed from the source
        assertFalse(
            resultText.contains("aapt:attr"),
            "Expected aapt:attr to be removed from source, got: $resultText",
        )

        // Verify comment is preserved
        assertContains(resultText, "Placeholder comment to ensure processor handles comments")

        // Verify aapt namespace was removed
        assertFalse(resultText.contains("xmlns:aapt"), "Expected aapt namespace to be removed from source, got: $resultText")

        // Verify the extracted file was created
        assertTrue(addedResources.isNotEmpty(), "Expected extracted drawable file")
        val extractedFile = addedResources.last()
        assertTrue(extractedFile.exists(), "Extracted file should exist on disk")

        // Verify the extracted file contains the gradient
        val extractedContent = extractedFile.readText(Charsets.UTF_8)
        assertContains(extractedContent, "gradient")
        assertContains(extractedContent, "#FF0000")
    }

    @Test
    fun `process handles multiple aapt attrs in same file`() {
        val addedResources = mutableSetOf<File>()
        val xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
            <!--
                Placeholder comment to ensure processor handles comments
            -->
            <vector xmlns:aapt="http://schemas.android.com/aapt"
                    xmlns:android="http://schemas.android.com/apk/res/android">
                <path>
                    <aapt:attr name="android:fillColor">
                        <gradient android:startColor="#FF0000"/>
                    </aapt:attr>
                </path>
                <path>
                    <aapt:attr name="android:fillColor">
                        <gradient android:startColor="#00FF00"/>
                    </aapt:attr>
                </path>
            </vector>
        """.trimIndent()

        val (sourceFile, _) = setupAndProcess(xmlContent, addedResources)

        // Two extracted files should have been created
        assertTrue(
            addedResources.size >= 2,
            "Expected at least 2 extracted files, got ${addedResources.size}",
        )

        val resultText = sourceFile.readText(Charsets.UTF_8)
        assertContains(resultText, "\$test_icon__0")
        assertContains(resultText, "\$test_icon__1")

        // Verify all aapt:attr elements were removed from the source
        assertFalse(
            resultText.contains("aapt:attr"),
            "Expected all aapt:attr elements to be removed from source, got: $resultText",
        )
    }

    @Test
    fun `process skips elements without xmlns aapt`() {
        val addedResources = mutableSetOf<File>()
        val xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
            <!--
                Placeholder comment to ensure processor handles comments
            -->
            <vector xmlns:android="http://schemas.android.com/apk/res/android">
                <path android:fillColor="#FF0000"/>
            </vector>
        """.trimIndent()

        setupAndProcess(xmlContent, addedResources)

        // No files should have been extracted
        assertEquals(addedResources.size, 1, "Expected no extracted files when no aapt namespace")
    }

    // ==================== UTF-8 encoding tests ====================

    @Test
    fun `process writes extracted files in UTF-8`() {
        val addedResources = mutableSetOf<File>()
        // Use a non-ASCII character in an attribute value inside the aapt:attr
        val xmlContent = """<?xml version="1.0" encoding="UTF-8"?>
            <!--
                Placeholder comment to ensure processor handles comments
            -->
            <vector xmlns:aapt="http://schemas.android.com/aapt"
                    xmlns:android="http://schemas.android.com/apk/res/android">
                <path>
                    <aapt:attr name="android:drawable">
                        <shape android:label="café"/>
                    </aapt:attr>
                </path>
            </vector>
        """.trimIndent()

        setupAndProcess(xmlContent, addedResources)

        // Find the extracted file
        val extractedFile = addedResources.last()
        val bytes = extractedFile.readBytes()

        // é (U+00E9) in UTF-8 is 0xC3 0xA9
        val eAcuteUtf8 = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertTrue(
            containsSubArray(bytes, eAcuteUtf8),
            "Expected UTF-8 bytes C3 A9 for é in extracted file, bytes: ${bytes.hex()}",
        )
    }

    @Test
    fun `process writes extracted file containing U+2190 in UTF-8`() {
        val addedResources = mutableSetOf<File>()
        val xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<vector xmlns:aapt=\"http://schemas.android.com/aapt\"\n" +
            "        xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <path>\n" +
            "        <aapt:attr name=\"android:drawable\">\n" +
            "            <shape android:label=\"\u2190 back\"/>\n" +
            "        </aapt:attr>\n" +
            "    </path>\n" +
            "</vector>"

        setupAndProcess(xmlContent, addedResources)

        val extractedFile = addedResources.last()
        val bytes = extractedFile.readBytes()

        // ← (U+2190) in UTF-8 is E2 86 90
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())
        val corruptedArrow = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x3F.toByte())

        assertTrue(
            containsSubArray(bytes, arrowUtf8),
            "Expected UTF-8 bytes E2 86 90 for U+2190, bytes: ${bytes.hex()}",
        )
        assertFalse(
            containsSubArray(bytes, corruptedArrow),
            "Found corrupted bytes E2 86 3F — UTF-8 was not used",
        )
    }

    @Test
    fun `emulate Windows default charset corruption for extracted file and verify fix`() {
        // Demonstrate that writing via Windows-1252 corrupts, but the fixed
        // processor (which uses UTF-8) does not.
        val testContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<shape label=\"\u2190 back\"/>"

        // Step 1: Write UTF-8.
        val corruptedFile = tempDir.resolve("corrupted_aapt.xml")
        corruptedFile.writeText(testContent, Charsets.UTF_8)

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

        // Step 4: Now run the actual fixed processor and verify no corruption.
        val addedResources = mutableSetOf<File>()
        val xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<vector xmlns:aapt=\"http://schemas.android.com/aapt\"\n" +
            "        xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <path>\n" +
            "        <aapt:attr name=\"android:drawable\">\n" +
            "            <shape android:label=\"\u2190 back\"/>\n" +
            "        </aapt:attr>\n" +
            "    </path>\n" +
            "</vector>"

        setupAndProcess(xmlContent, addedResources)

        val extractedFile = addedResources.last()
        val fixedBytes = extractedFile.readBytes()
        val arrowUtf8 = byteArrayOf(0xE2.toByte(), 0x86.toByte(), 0x90.toByte())

        assertTrue(
            containsSubArray(fixedBytes, arrowUtf8),
            "Expected correct UTF-8 bytes E2 86 90, bytes: ${fixedBytes.hex()}",
        )
        assertFalse(
            containsSubArray(fixedBytes, corruptedArrow),
            "Found corrupted bytes E2 86 3F in extracted file — UTF-8 was not used",
        )
    }

    // ==================== Helpers ====================

    private fun setupAndProcess(
        xmlContent: String,
        addedResources: MutableSet<File>,
    ): Pair<File, File> {
        val resDir = tempDir.resolve("res_${System.nanoTime()}")
        val drawableDir = resDir.resolve("drawable")
        drawableDir.mkdirs()

        val sourceFile = drawableDir.resolve("test_icon.xml")
        sourceFile.writeText(xmlContent, Charsets.UTF_8)

        addedResources.add(sourceFile)
        val processor = AaptMacroProcessor(
            get = { path ->
                resDir.resolve(path.removePrefix("res/")).also { it.parentFile.mkdirs() }
            },
            modifiedResResources = addedResources
        )

        processor.process()
        return Pair(sourceFile, resDir)
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
