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
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "strings.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <string name="app_name">My App</string>
                        <string name="greeting">Hello</string>
                    </resources>
                """.trimIndent(),
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(publicXmlManager.idExists("string", "app_name"), "Expected public ID for string/app_name")
            assertTrue(publicXmlManager.idExists("string", "greeting"), "Expected public ID for string/greeting")
        }
    }

    @Test
    fun `process handles arrays`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "arrays.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <array name="auto_dark_option_values">
                            <item>@string/option_value_off</item>
                            <item>@string/option_value_os_setting</item>
                            <item>@string/option_value_sunrise_sunset</item>
                        </array>
                    </resources>
                """.trimIndent(),
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(publicXmlManager.idExists("array", "auto_dark_option_values"), "Expected public ID for array/auto_dark_option_values")
        }
    }

    @Test
    fun `process converts @+id references to @id references and adds to ids xml`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
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

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
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
        }
    }

    @Test
    fun `process creates public IDs for file-based resources`() {
        val (publicXmlManager, resDir, _) = setupEnvironment()

        // Create a drawable file resource
        val drawableDir = resDir.resolve("res/drawable")
        drawableDir.mkdirs()
        val drawableFile = drawableDir.resolve("ic_launcher.xml")
        drawableFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><vector/>""",
            Charsets.UTF_8,
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = setOf(drawableFile),
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("drawable", "ic_launcher"),
                "Expected public ID for drawable/ic_launcher",
            )
        }
    }

    @Test
    fun `process does not duplicate existing public IDs`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
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

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            // Should still exist, but not be duplicated
            assertTrue(publicXmlManager.idExists("string", "existing_name"))
        }

        // Verify the public.xml file doesn't have duplicate entries
        val publicContent = resDir.resolve("res/values/public.xml").readText(Charsets.UTF_8)
        val count = Regex("existing_name").findAll(publicContent).count()
        assertEquals(1, count, "Expected exactly 1 entry for existing_name, found $count")
    }

    @Test
    fun `process resolves item tag with type attribute to correct resource type`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "dimens.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <item type="dimen" name="morphed_zero_padding">0.0dip</item>
                    </resources>
                """.trimIndent(),
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("dimen", "morphed_zero_padding"),
                "Expected public ID for dimen/morphed_zero_padding but it was not created",
            )
            assertFalse(
                publicXmlManager.idExists("item", "morphed_zero_padding"),
                "Resource should NOT be registered under type 'item'",
            )
        }
    }

    @Test
    fun `process resolves item tag with bool type attribute`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "bools.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <item type="bool" name="is_feature_enabled">true</item>
                    </resources>
                """.trimIndent(),
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("bool", "is_feature_enabled"),
                "Expected public ID for bool/is_feature_enabled",
            )
            assertFalse(
                publicXmlManager.idExists("item", "is_feature_enabled"),
                "Resource should NOT be registered under type 'item'",
            )
        }
    }

    @Test
    fun `process handles mix of regular tags and item tags with type attribute`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "dimens.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <resources>
                        <dimen name="regular_dimen">16dp</dimen>
                        <item type="dimen" name="item_dimen">0.0dip</item>
                        <item type="dimen" name="another_item_dimen">8dp</item>
                    </resources>
                """.trimIndent(),
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("dimen", "regular_dimen"),
                "Expected public ID for dimen/regular_dimen",
            )
            assertTrue(
                publicXmlManager.idExists("dimen", "item_dimen"),
                "Expected public ID for dimen/item_dimen",
            )
            assertTrue(
                publicXmlManager.idExists("dimen", "another_item_dimen"),
                "Expected public ID for dimen/another_item_dimen",
            )
            assertFalse(
                publicXmlManager.idExists("item", "item_dimen"),
                "Resource should NOT be registered under type 'item'",
            )
        }
    }

    @Test
    fun `process handles values XML with non-ASCII resource names`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            valuesXml = mapOf(
                "strings.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources><string name=\"grüße\">Hello</string></resources>",
            ),
        )

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            assertTrue(
                publicXmlManager.idExists("string", "grüße"),
                "Expected public ID for non-ASCII resource name",
            )
        }
    }

    // ==================== Theme attribute reference expansion tests ====================

    @Test
    fun `process expands shorthand theme attribute references without package prefix`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:background="?myThemeColor">
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            assertContains(layoutResult, "?attr/myThemeColor")
            assertFalse(
                Regex("""\?myThemeColor(?![a-zA-Z0-9_])""").containsMatchIn(layoutResult),
                "Expected shorthand ?myThemeColor to be expanded to ?attr/myThemeColor",
            )
        }
    }

    @Test
    fun `process expands shorthand theme attribute references with android package prefix`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:background="?android:windowBackground">
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            assertContains(layoutResult, "?android:attr/windowBackground")
            assertFalse(
                layoutResult.contains("?android:windowBackground"),
                "Expected shorthand ?android:windowBackground to be expanded to ?android:attr/windowBackground",
            )
        }
    }

    @Test
    fun `process does not modify already expanded theme attribute references`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:background="?attr/myThemeColor"
                        android:textColor="?android:attr/textColorPrimary">
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            assertContains(layoutResult, "?attr/myThemeColor")
            assertContains(layoutResult, "?android:attr/textColorPrimary")
            // Ensure no double-expansion like ?attr/attr/myThemeColor
            assertFalse(
                layoutResult.contains("?attr/attr/"),
                "Should not double-expand already expanded theme attribute references",
            )
            assertFalse(
                layoutResult.contains("?android:attr/attr/"),
                "Should not double-expand already expanded theme attribute references",
            )
        }
    }

    @Test
    fun `process expands multiple shorthand theme attribute references in same file`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:background="?colorPrimary">
                        <TextView
                            android:textColor="?android:textColorPrimary"
                            android:background="?colorAccent"/>
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            assertContains(layoutResult, "?attr/colorPrimary")
            assertContains(layoutResult, "?android:attr/textColorPrimary")
            assertContains(layoutResult, "?attr/colorAccent")
        }
    }

    @Test
    fun `process handles both @+id and shorthand theme attribute references in same element`() {
        val (publicXmlManager, resDir, createdResourceFiles) = setupEnvironment(
            layoutXml = mapOf(
                "activity_main.xml" to """<?xml version="1.0" encoding="UTF-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                        <Button
                            android:id="@+id/my_button"
                            android:background="?selectableItemBackground"/>
                    </LinearLayout>
                """.trimIndent(),
            ),
        )

        val layoutFile = resDir.resolve("res/layout/activity_main.xml")

        publicXmlManager.use { publicXmlManager ->
            val processor = ResourceIdProcessor(
                get = { path -> resDir.resolve(path).also { it.parentFile?.mkdirs() } },
                publicIdManager = publicXmlManager,
                modifiedResResources = createdResourceFiles,
            )

            processor.process()

            val layoutResult = layoutFile.readText(Charsets.UTF_8)
            // Check @+id was handled
            assertContains(layoutResult, "@id/my_button")
            assertFalse(layoutResult.contains("@+id/my_button"))
            // Check theme attr was expanded
            assertContains(layoutResult, "?attr/selectableItemBackground")
        }
    }

    // ==================== Helpers ====================

    private data class TestEnvironment(
        val publicXmlManager: PublicXmlManager,
        val resDir: File,
        val createdResourceFiles: Set<File>,
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

        val createdResourceFiles = mutableSetOf<File>()

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
            val file = valuesDir.resolve(name)
            file.writeText(content, Charsets.UTF_8)
            // ids.xml is always opened directly by ResourceIdProcessor via get("res/values/ids.xml"),
            // so it must not also appear in addedResources (which would cause a concurrent Document access).
            if (name != "ids.xml") {
                createdResourceFiles.add(file)
            }
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
                val file = layoutDir.resolve(name)
                file.writeText(content, Charsets.UTF_8)
                createdResourceFiles.add(file)
            }
        }

        return TestEnvironment(
            publicXmlManager = PublicXmlManager(publicXml),
            // The get lambda receives paths like "res" or "res/values/ids.xml",
            // so resolve them relative to baseDir.
            resDir = baseDir,
            createdResourceFiles = createdResourceFiles,
        )
    }
}






