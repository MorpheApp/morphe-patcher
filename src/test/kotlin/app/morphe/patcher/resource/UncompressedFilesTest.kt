/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object UncompressedFilesTest {

    // ==================== JSON parsing tests ====================

    @Test
    fun `parses paths from JSON`() {
        val json = """
            {
                "extensions": [],
                "paths": ["lib/arm64-v8a/libfoo.so", "assets/config.bin"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("lib/arm64-v8a/libfoo.so" in set)
        assertTrue("assets/config.bin" in set)
        assertFalse("other/file.txt" in set)
    }

    @Test
    fun `parses extensions from JSON`() {
        val json = """
            {
                "extensions": [".png", ".webp"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("res/icon.png" in set)
        assertTrue("res/background.webp" in set)
        assertFalse("res/layout.xml" in set)
    }

    @Test
    fun `parses both paths and extensions`() {
        val json = """
            {
                "extensions": [".png"],
                "paths": ["resources.arsc", "lib/x86_64/libfoo.so"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        // Exact path match
        assertTrue("resources.arsc" in set)
        assertTrue("lib/x86_64/libfoo.so" in set)

        // Extension match
        assertTrue("res/icon.png" in set)

        // No match
        assertFalse("res/layout.xml" in set)
    }

    @Test
    fun `handles empty JSON arrays`() {
        val json = """
            {
                "extensions": [],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertEquals(0, set.size)
        assertFalse("anything.png" in set)
    }

    @Test
    fun `handles missing extensions array`() {
        val json = """
            {
                "paths": ["resources.arsc"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("resources.arsc" in set)
        assertFalse("icon.png" in set)
    }

    @Test
    fun `handles missing paths array`() {
        val json = """
            {
                "extensions": [".png"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertEquals(0, set.size)
        assertTrue("icon.png" in set)
    }

    // ==================== Extension matching tests ====================

    @Test
    fun `extension matching is case-sensitive`() {
        val json = """
            {
                "extensions": [".png"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("icon.png" in set)
        assertFalse("icon.PNG" in set)
    }

    @Test
    fun `extensions without leading dot are normalized`() {
        val json = """
            {
                "extensions": ["jpg"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("photo.jpg" in set)
    }

    @Test
    fun `extension matching uses last dot in filename`() {
        val json = """
            {
                "extensions": [".png"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("res/icon.9.png" in set)
    }

    @Test
    fun `files without extension do not match any extension rule`() {
        val json = """
            {
                "extensions": [".png", ".webp"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertFalse("res/some_file" in set)
    }

    @Test
    fun `dot-only filename does not match`() {
        val json = """
            {
                "extensions": [".hidden"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        // A file like ".hidden" starts with a dot but has no extension in the traditional sense.
        // The dot index is 0, which is <= 0, so it should not match.
        assertFalse(".hidden" in set)
    }

    // ==================== Path normalization tests ====================

    @Test
    fun `paths with backslashes are normalized to forward slashes`() {
        val json = """
            {
                "extensions": [],
                "paths": ["lib/arm64-v8a/libfoo.so"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        // On Windows, paths might come in with backslashes
        assertTrue("lib\\arm64-v8a\\libfoo.so" in set)
    }

    @Test
    fun `lookup paths with backslashes are normalized`() {
        val json = """
            {
                "extensions": [".so"],
                "paths": []
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("lib\\arm64-v8a\\libfoo.so" in set)
    }

    @Test
    fun `leading slashes are stripped from paths`() {
        val json = """
            {
                "extensions": [],
                "paths": ["assets/data.bin"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("/assets/data.bin" in set)
    }

    @Test
    fun `paths in JSON with leading slashes are normalized`() {
        val json = """
            {
                "extensions": [],
                "paths": ["/assets/data.bin"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertTrue("assets/data.bin" in set)
    }

    // ==================== Set behavior tests ====================

    @Test
    fun `size reflects number of explicit paths`() {
        val json = """
            {
                "extensions": [".png", ".webp", ".jpg"],
                "paths": ["resources.arsc", "lib/arm64-v8a/libfoo.so"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        assertEquals(2, set.size)
    }

    @Test
    fun `iterator returns explicit paths`() {
        val json = """
            {
                "extensions": [".png"],
                "paths": ["resources.arsc", "lib/arm64-v8a/libfoo.so"]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)
        val collected = set.toSet()

        assertEquals(setOf("resources.arsc", "lib/arm64-v8a/libfoo.so"), collected)
    }

    @Test
    fun `can be used as Set String parameter`() {
        val json = """
            {
                "extensions": [".png"],
                "paths": ["resources.arsc"]
            }
        """.trimIndent()

        val doNotCompress: Set<String> = UncompressedFiles(json)

        // Simulate the check in ApkUtils: path !in resources.doNotCompress
        val pngPath = "res/icon.png"
        val xmlPath = "res/layout.xml"
        val arscPath = "resources.arsc"

        assertTrue(pngPath in doNotCompress, "PNG should be uncompressed (extension match)")
        assertFalse(xmlPath in doNotCompress, "XML should be compressed")
        assertTrue(arscPath in doNotCompress, "resources.arsc should be uncompressed (exact match)")
    }

    // ==================== Real-world JSON structure test ====================

    @Test
    fun `handles real-world JSON structure`() {
        val json = """
            {
                "extensions": [
                    ".jpg",
                    ".mp3",
                    ".mp4",
                    ".png",
                    ".wav",
                    ".webp"
                ],
                "paths": [
                    "lib/x86_64/libfoo.so",
                    "assets/dexopt/baseline.prof",
                    "res/icon.png",
                    "okhttp3/internal/publicsuffix/publicsuffixes.gz",
                    "resources.arsc"
                ]
            }
        """.trimIndent()

        val set = UncompressedFiles(json)

        // Exact path matches
        assertTrue("lib/x86_64/libfoo.so" in set)
        assertTrue("assets/dexopt/baseline.prof" in set)
        assertTrue("resources.arsc" in set)
        assertTrue("okhttp3/internal/publicsuffix/publicsuffixes.gz" in set)

        // Extension matches (not in paths but matches extension rule)
        assertTrue("res/new_image.png" in set)
        assertTrue("res/photo.jpg" in set)
        assertTrue("res/background.webp" in set)
        assertTrue("assets/sound.mp3" in set)
        assertTrue("assets/video.mp4" in set)
        assertTrue("res/alert.wav" in set)

        // No match
        assertFalse("res/layout.xml" in set)
        assertFalse("res/strings.json" in set)
        assertFalse("lib/x86_64/libbar.txt" in set)
    }

    // ==================== PathMap integration tests ====================

    @Test
    fun `contains resolves alias to original name via path map`() {
        val uncompressedJson = """
            {
                "extensions": [],
                "paths": ["res/-5N.png"]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // Query with the on-disk alias path — should resolve to the original name
        assertTrue("res/drawable-mdpi/drawable_0x7f080695.png" in set,
            "Alias path should match after resolving to original name via path map")

        // Direct original name should also still work
        assertTrue("res/-5N.png" in set,
            "Original name should match directly")
    }

    @Test
    fun `contains works for unmapped paths with path map present`() {
        val uncompressedJson = """
            {
                "extensions": [],
                "paths": ["lib/x86_64/libfoo.so", "resources.arsc"]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // Unmapped paths (same name on disk and in APK) should still work
        assertTrue("lib/x86_64/libfoo.so" in set)
        assertTrue("resources.arsc" in set)
    }

    @Test
    fun `contains still matches extensions when path map has no mapping`() {
        val uncompressedJson = """
            {
                "extensions": [".png"],
                "paths": []
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/zzy.xml", "alias": "res/drawable/drawable_0x7f080441.xml"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // Extension matching should work even for alias paths not in the paths set
        assertTrue("res/drawable-mdpi/drawable_0x7f080695.png" in set,
            "Extension match should work for alias paths")

        // And for original names too
        assertTrue("res/-5N.png" in set)
    }

    @Test
    fun `contains resolves multiple aliases correctly`() {
        val uncompressedJson = """
            {
                "extensions": [],
                "paths": ["res/-5N.png", "res/zzy.xml", "res/-v_.webp"]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"},
            {"name": "res/zzy.xml", "alias": "res/drawable/drawable_0x7f080441.xml"},
            {"name": "res/-v_.webp", "alias": "res/drawable-hdpi/drawable_0x7f080123.webp"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        assertTrue("res/drawable-mdpi/drawable_0x7f080695.png" in set)
        assertTrue("res/drawable/drawable_0x7f080441.xml" in set)
        assertTrue("res/drawable-hdpi/drawable_0x7f080123.webp" in set)

        // Unknown alias should not match
        assertFalse("res/drawable/unknown.xml" in set)
    }

    @Test
    fun `contains does not match alias path that resolves to a name not in paths`() {
        val uncompressedJson = """
            {
                "extensions": [],
                "paths": ["res/-5N.png"]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"},
            {"name": "res/zzH.xml", "alias": "res/layout/layout_0x7f0e0027.xml"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // This alias resolves to res/zzH.xml which is NOT in the uncompressed paths
        assertFalse("res/layout/layout_0x7f0e0027.xml" in set,
            "Alias resolving to a name not in the paths set should not match")
    }

    @Test
    fun `contains with path map and backslash alias lookup`() {
        val uncompressedJson = """
            {
                "extensions": [],
                "paths": ["res/-5N.png"]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // Windows-style backslash alias should still resolve
        assertTrue("res\\drawable-mdpi\\drawable_0x7f080695.png" in set)
    }

    @Test
    fun `real-world scenario with mixed mapped and unmapped paths`() {
        val uncompressedJson = """
            {
                "extensions": [".png", ".webp"],
                "paths": [
                    "res/-5N.png",
                    "lib/x86_64/libfoo.so",
                    "resources.arsc",
                    "assets/config.bin"
                ]
            }
        """.trimIndent()

        val pathMapJson = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(pathMapJson)
        val set = UncompressedFiles(uncompressedJson, pathMap)

        // Mapped path: lookup by alias resolves to original name in paths
        assertTrue("res/drawable-mdpi/drawable_0x7f080695.png" in set)

        // Unmapped paths: same name on disk and in APK
        assertTrue("lib/x86_64/libfoo.so" in set)
        assertTrue("resources.arsc" in set)
        assertTrue("assets/config.bin" in set)

        // Extension match for a newly added file (not in paths, not in path map)
        assertTrue("res/drawable-xxhdpi/new_icon.png" in set)
        assertTrue("res/drawable/new_bg.webp" in set)

        // No match
        assertFalse("res/layout/activity_main.xml" in set)
    }
}
