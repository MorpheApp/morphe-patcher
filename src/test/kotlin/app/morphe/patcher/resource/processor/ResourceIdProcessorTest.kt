/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.PublicXmlManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object ResourceIdProcessorTest {

    @TempDir
    lateinit var tempDir: File

    // ==================== ID generation tests ====================

    @Test
    fun `process creates public ID entries for resources in values XML`() {
        val (publicXmlManager, resDir) = setupEnvironment(
            valuesXml = mapOf(
                "strings.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <string name="app_name">My App</string>
                        <string name="greeting">Hello</string>
                    </resources>
                """.trimIndent(),
            ),
        )

        try {
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResources = emptySet(),
                addedResources = emptySet(),
            )

            processor.process()

            assertTrue(publicXmlManager.idExists("string", "app_name"), "Expected public ID for string/app_name")
            assertTrue(publicXmlManager.idExists("string", "greeting"), "Expected public ID for string/greeting")
        } finally {
            publicXmlManager.close()
        }
    }

    @Test
    fun `process converts @+id references to @id references and adds to ids xml`() {
        val (publicXmlManager, resDir) = setupEnvironment(
            valuesXml = mapOf(
                "ids.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources></resources>
                """.trimIndent(),
            ),
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                        <Button android:id="@+id/my_button"/>
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")
        val idsXmlFile = resDir.resolve("res/values/ids.xml")

        try {
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResources = setOf(layoutFile),
                addedResources = emptySet(),
            )

            processor.process()

            // The @+id/my_button should be converted to @id/my_button
            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            assertContains(layoutResult, "@id/my_button")
            assertFalse(
                layoutResult.contains("@+id/my_button"),
                "Expected @+id to be converted to @id",
            )

            // The ids.xml should have a new <id> entry
            val idsResult = idsXmlFile.readText(Charsets.UTF_8)
            assertContains(idsResult, "my_button")
        } finally {
            publicXmlManager.close()
        }
    }

    @Test
    fun `process creates public IDs for file-based resources`() {
        val (publicXmlManager, resDir) = setupEnvironment()

        // Create a drawable file resource
        val drawableDir = resDir.resolve("res/drawable")
        drawableDir.mkdirs()
        val drawableFile = drawableDir.resolve("ic_launcher.xml")
        drawableFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><vector/>""",
            Charsets.UTF_8,
        )

        try {
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResources = emptySet(),
                addedResources = emptySet(),
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("drawable", "ic_launcher"),
                "Expected public ID for drawable/ic_launcher",
            )
        } finally {
            publicXmlManager.close()
        }
    }

    @Test
    fun `process does not duplicate existing public IDs`() {
        val (publicXmlManager, resDir) = setupEnvironment(
            publicXmlEntries = listOf(
                Triple("string", "existing_name", "0x1"),
            ),
            valuesXml = mapOf(
                "strings.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <string name="existing_name">Already here</string>
                    </resources>
                """.trimIndent(),
            ),
        )

        try {
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResources = emptySet(),
                addedResources = emptySet(),
            )

            processor.process()

            // Should still exist, but not be duplicated
            assertTrue(publicXmlManager.idExists("string", "existing_name"))
        } finally {
            publicXmlManager.close()
        }

        // Verify the public.xml file doesn't have duplicate entries
        val publicContent = resDir.resolve("res/values/public.xml").readText(Charsets.UTF_8)
        val count = Regex("existing_name").findAll(publicContent).count()
        assertEquals(1, count, "Expected exactly 1 entry for existing_name, found $count")
    }

    @Test
    fun `process handles values XML with non-ASCII resource names`() {
        val (publicXmlManager, resDir) = setupEnvironment(
            valuesXml = mapOf(
                "strings.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources><string name=\"grüße\">Hello</string></resources>",
            ),
        )

        try {
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResources = emptySet(),
                addedResources = emptySet(),
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("string", "grüße"),
                "Expected public ID for non-ASCII resource name",
            )
        } finally {
            publicXmlManager.close()
        }
    }

    // ==================== Helpers ====================

    private data class TestEnvironment(
        val publicXmlManager: PublicXmlManager,
        val resDir: File,
    )

    private fun setupEnvironment(
        publicXmlEntries: List<Triple<String, String, String>> = emptyList(),
        valuesXml: Map<String, String> = emptyMap(),
        layoutXml: Map<String, String> = emptyMap(),
    ): TestEnvironment {
        val baseDir = tempDir.resolve("env_${System.nanoTime()}")
        val resDir = baseDir.resolve("res")
        val valuesDir = resDir.resolve("values")
        valuesDir.mkdirs()

        // Create public.xml
        val publicEntries = publicXmlEntries.joinToString("\n") { (type, name, id) ->
            "    <public id=\"$id\" type=\"$type\" name=\"$name\"/>"
        }
        val publicXml = valuesDir.resolve("public.xml")
        publicXml.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources package=\"com.example.app\" id=\"0x7f\">\n$publicEntries\n</resources>",
            Charsets.UTF_8,
        )

        // Create values XML files
        valuesXml.forEach { (name, content) ->
            valuesDir.resolve(name).writeText(content, Charsets.UTF_8)
        }

        // Always create ids.xml if not explicitly provided, since process() always opens it
        if (!valuesDir.resolve("ids.xml").exists()) {
            valuesDir.resolve("ids.xml").writeText(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources></resources>",
                Charsets.UTF_8,
            )
        }

        // Create layout XML files
        if (layoutXml.isNotEmpty()) {
            val layoutDir = resDir.resolve("layout")
            layoutDir.mkdirs()
            layoutXml.forEach { (name, content) ->
                layoutDir.resolve(name).writeText(content, Charsets.UTF_8)
            }
        }

        return TestEnvironment(
            publicXmlManager = PublicXmlManager(publicXml),
            // The get lambda receives paths like "res" or "res/values/ids.xml",
            // so resolve them relative to baseDir.
            resDir = baseDir,
        )
    }
}






