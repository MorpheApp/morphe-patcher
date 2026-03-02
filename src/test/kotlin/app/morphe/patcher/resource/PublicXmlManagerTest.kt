/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object PublicXmlManagerTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== Helper ====================

    /**
     * Creates a public.xml file in a unique subdirectory of [tempDir] and returns its [File] handle.
     *
     * @param entries list of triples (type, name, id) for `<public>` elements.
     * @param packageName optional `package` attribute on the `<resources>` element.
     * @param packageId optional `id` attribute on the `<resources>` element.
     */
    private fun createPublicXml(
        packageName: String = "com.example.app",
        packageId: String = "0x7f",
        entries: List<Triple<String, String, String>> = emptyList(),
    ): File {
        val dir = tempDir.resolve("env_${System.nanoTime()}")
        dir.mkdirs()
        val file = dir.resolve("public.xml")

        val attrs = buildString {
            append(" package=\"$packageName\"")
            append(" id=\"$packageId\"")
        }
        val body = entries.joinToString("\n") { (type, name, id) ->
            "  <public id=\"$id\" type=\"$type\" name=\"$name\" />"
        }
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources$attrs>\n$body\n</resources>",
            Charsets.UTF_8,
        )
        return file
    }

    // ==================== Constructor / init tests ====================

    @Test
    fun `constructor throws when file does not exist`() {
        val nonExistent = tempDir.resolve("does_not_exist.xml")
        assertThrows<IllegalArgumentException> {
            PublicXmlManager(nonExistent)
        }
    }

    @Test
    fun `constructor succeeds with a valid empty public xml`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)
        manager.close()
    }

    @Test
    fun `constructor succeeds with a populated public xml`() {
        val file = createPublicXml(
            entries = listOf(
                Triple("anim", "fade_in", "0x7f010000"),
                Triple("anim", "fade_out", "0x7f010001"),
                Triple("string", "app_name", "0x7f020000"),
            ),
        )
        val manager = PublicXmlManager(file)
        manager.close()
    }

    // ==================== readExistingIds tests ====================

    @Test
    fun `readExistingIds returns empty set for empty resources`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            val ids = manager.getDefinedIds()
            assertTrue(ids.isEmpty(), "Expected no IDs in an empty public.xml")
        }
    }

    @Test
    fun `readExistingIds returns all entries`() {
        val file = createPublicXml(
            entries = listOf(
                Triple("anim", "fade_in", "0x7f010000"),
                Triple("string", "app_name", "0x7f020000"),
                Triple("drawable", "icon", "0x7f030000"),
            ),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            val ids = manager.getDefinedIds()
            assertEquals(3, ids.size)
            assertTrue(ids.contains(Pair("anim", "fade_in")))
            assertTrue(ids.contains(Pair("string", "app_name")))
            assertTrue(ids.contains(Pair("drawable", "icon")))
        }
    }

    @Test
    fun `readExistingIds throws on non-hex id`() {
        val dir = tempDir.resolve("env_${System.nanoTime()}")
        dir.mkdirs()
        val file = dir.resolve("public.xml")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <resources>
              <public id="not_hex" type="string" name="bad" />
            </resources>""".trimIndent(),
            Charsets.UTF_8,
        )

        assertThrows<IllegalStateException> {
            PublicXmlManager(file)
        }
    }

    // ==================== idExists tests ====================

    @Test
    fun `idExists returns true for existing entry`() {
        val file = createPublicXml(
            entries = listOf(Triple("string", "app_name", "0x7f020000")),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            assertTrue(manager.idExists("string", "app_name"))
        }
    }

    @Test
    fun `idExists returns false for non-existing entry`() {
        val file = createPublicXml(
            entries = listOf(Triple("string", "app_name", "0x7f020000")),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            assertFalse(manager.idExists("string", "missing"))
            assertFalse(manager.idExists("drawable", "app_name"))
        }
    }

    @Test
    fun `idExists distinguishes entries with same name but different type`() {
        val file = createPublicXml(
            entries = listOf(
                Triple("string", "app_name", "0x7f020000"),
                Triple("id", "app_name", "0x7f030000"),
            ),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            assertTrue(manager.idExists("string", "app_name"))
            assertTrue(manager.idExists("id", "app_name"))
            assertFalse(manager.idExists("drawable", "app_name"))
        }
    }

    // ==================== createPublicId tests ====================

    @Test
    fun `createPublicId adds a new entry`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            assertFalse(manager.idExists("string", "new_string"))
            manager.createPublicId("string", "new_string")
            assertTrue(manager.idExists("string", "new_string"))
        }
    }

    @Test
    fun `createPublicId does not duplicate existing entry`() {
        val file = createPublicXml(
            entries = listOf(Triple("string", "app_name", "0x7f020000")),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.createPublicId("string", "app_name")
            // Re-reading should still show exactly one entry for this type+name pair.
            val ids = manager.getDefinedIds()
            val count = ids.count { it.key == Pair("string", "app_name") }
            assertEquals(1, count, "Duplicate entry should not be created")
        }
    }

    @Test
    fun `createPublicId increments id correctly within the same type`() {
        val file = createPublicXml(
            entries = listOf(
                Triple("string", "first", "0x7f020001"),
                Triple("string", "second", "0x7f020003"),
            ),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.createPublicId("string", "third")
            assertTrue(manager.idExists("string", "third"))
        }

        // Verify persisted XML contains the new entry with incremented id.
        val content = file.readText()
        // The highest existing id for "string" is 0x7f020003, so the new one should be 0x7f020004.
        assertTrue(content.contains("0x7f020004"), "Expected new id 0x7f020004 in the file, got:\n$content")
        assertTrue(content.contains("name=\"third\""), "Expected name=\"third\" in the file")
    }

    @Test
    fun `createPublicId assigns id 1 for a brand new type`() {
        val file = createPublicXml(
            entries = listOf(Triple("string", "existing", "0x7f020001")),
        )
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.createPublicId("color", "primary")
            assertTrue(manager.idExists("color", "primary"))
        }

        val content = file.readText()
        assertTrue(content.contains("0x1"), "Expected new id 0x1 for the brand-new type 'color'")
        assertTrue(content.contains("name=\"primary\""))
    }

    @Test
    fun `createPublicId adds multiple entries for different types`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.createPublicId("string", "hello")
            manager.createPublicId("drawable", "icon")
            manager.createPublicId("color", "primary")

            assertTrue(manager.idExists("string", "hello"))
            assertTrue(manager.idExists("drawable", "icon"))
            assertTrue(manager.idExists("color", "primary"))
        }
    }

    @Test
    fun `createPublicId persists entries after close`() {
        val file = createPublicXml(
            entries = listOf(Triple("string", "existing", "0x7f020001")),
        )

        PublicXmlManager(file).use { manager ->
            manager.createPublicId("string", "new_one")
            manager.createPublicId("drawable", "new_icon")
        }

        // Re-open and verify the entries are present.
        PublicXmlManager(file).use { manager ->
            assertTrue(manager.idExists("string", "existing"))
            assertTrue(manager.idExists("string", "new_one"))
            assertTrue(manager.idExists("drawable", "new_icon"))
        }
    }

    // ==================== changePackageName tests ====================

    @Test
    fun `changePackageName sets the package attribute on resources element`() {
        val file = createPublicXml(packageName = "com.original.app")
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.changePackageName("com.new.app")
        }

        val content = file.readText()
        assertTrue(content.contains("package=\"com.new.app\""), "Expected updated package name in:\n$content")
        assertFalse(content.contains("package=\"com.original.app\""), "Old package name should not be present")
    }

    @Test
    fun `changePackageName adds package attribute when not previously set`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.changePackageName("com.brand.new")
        }

        val content = file.readText()
        assertTrue(content.contains("package=\"com.brand.new\""), "Expected package attribute in:\n$content")
    }

    // ==================== changePackageId tests ====================

    @Test
    fun `changePackageId sets the id attribute on resources element`() {
        val file = createPublicXml(packageId = "0x7f")
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.changePackageId(0x80)
        }

        val content = file.readText()
        assertTrue(content.contains("id=\"0x80\""), "Expected updated package id in:\n$content")
    }

    @Test
    fun `changePackageId adds id attribute when not previously set`() {
        val file = createPublicXml()
        val manager = PublicXmlManager(file)

        manager.use { manager ->
            manager.changePackageId(0x7f)
        }

        val content = file.readText()
        assertTrue(content.contains("id=\"0x7f\""), "Expected id attribute in:\n$content")
    }

    // ==================== Combined / integration tests ====================

    @Test
    fun `full workflow with realistic public xml`() {
        val file = createPublicXml(
            entries = listOf(
                Triple("anim", "fade_in", "0x7f010000"),
                Triple("anim", "fade_out", "0x7f010001"),
                Triple("string", "app_name", "0x7f020000"),
                Triple("string", "greeting", "0x7f020001"),
                Triple("drawable", "icon", "0x7f030000"),
            ),
            packageName = "com.example.app",
            packageId = "0x7f",
        )

        PublicXmlManager(file).use { manager ->
            // Verify pre-existing entries.
            assertTrue(manager.idExists("anim", "fade_in"))
            assertTrue(manager.idExists("string", "app_name"))
            assertFalse(manager.idExists("string", "farewell"))

            // Add new entries.
            manager.createPublicId("string", "farewell")
            manager.createPublicId("anim", "slide_in")
            manager.createPublicId("color", "primary")

            assertTrue(manager.idExists("string", "farewell"))
            assertTrue(manager.idExists("anim", "slide_in"))
            assertTrue(manager.idExists("color", "primary"))

            // Change package info.
            manager.changePackageName("com.example.newapp")
            manager.changePackageId(0x80)
        }

        // Re-read and verify everything persisted.
        PublicXmlManager(file).use { manager ->
            assertTrue(manager.idExists("anim", "fade_in"))
            assertTrue(manager.idExists("anim", "fade_out"))
            assertTrue(manager.idExists("anim", "slide_in"))
            assertTrue(manager.idExists("string", "app_name"))
            assertTrue(manager.idExists("string", "greeting"))
            assertTrue(manager.idExists("string", "farewell"))
            assertTrue(manager.idExists("drawable", "icon"))
            assertTrue(manager.idExists("color", "primary"))
        }

        val content = file.readText()
        assertTrue(content.contains("package=\"com.example.newapp\""))
        assertTrue(content.contains("id=\"0x80\""))
    }
}

