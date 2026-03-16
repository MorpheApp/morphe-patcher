/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal object PathMapTest {

    // ==================== Basic lookup tests ====================

    @Test
    fun `getOriginalName returns original name for known alias`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/-5N.png", pathMap.getOriginalName("res/drawable-mdpi/drawable_0x7f080695.png"))
    }

    @Test
    fun `getOriginalName returns null for unknown alias`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertNull(pathMap.getOriginalName("res/layout/activity_main.xml"))
    }

    @Test
    fun `getAlias returns alias for known name`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/drawable-mdpi/drawable_0x7f080695.png", pathMap.getAlias("res/-5N.png"))
    }

    @Test
    fun `getAlias returns null for unknown name`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertNull(pathMap.getAlias("res/unknown.png"))
    }

    // ==================== Multiple entries ====================

    @Test
    fun `handles multiple entries`() {
        val json = """[
            {"name": "res/zzy.xml", "alias": "res/drawable/drawable_0x7f080441.xml"},
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"},
            {"name": "res/color/abc_hint_foreground_material_dark.xml", "alias": "res/color/color_0x7f06000c.xml"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/zzy.xml", pathMap.getOriginalName("res/drawable/drawable_0x7f080441.xml"))
        assertEquals("res/-5N.png", pathMap.getOriginalName("res/drawable-mdpi/drawable_0x7f080695.png"))
        assertEquals(
            "res/color/abc_hint_foreground_material_dark.xml",
            pathMap.getOriginalName("res/color/color_0x7f06000c.xml")
        )
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty array produces empty map`() {
        val pathMap = PathMap("[]")

        assertNull(pathMap.getOriginalName("anything"))
        assertNull(pathMap.getAlias("anything"))
    }

    @Test
    fun `EMPTY singleton has no mappings`() {
        assertNull(PathMap.EMPTY.getOriginalName("res/foo.png"))
        assertNull(PathMap.EMPTY.getAlias("res/foo.png"))
    }

    // ==================== Path normalization ====================

    @Test
    fun `lookup normalizes backslashes to forward slashes`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/-5N.png", pathMap.getOriginalName("res\\drawable-mdpi\\drawable_0x7f080695.png"))
    }

    @Test
    fun `lookup strips leading slashes`() {
        val json = """[
            {"name": "res/-5N.png", "alias": "res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/-5N.png", pathMap.getOriginalName("/res/drawable-mdpi/drawable_0x7f080695.png"))
    }

    @Test
    fun `JSON paths with leading slashes are normalized`() {
        val json = """[
            {"name": "/res/-5N.png", "alias": "/res/drawable-mdpi/drawable_0x7f080695.png"}
        ]"""

        val pathMap = PathMap(json)

        assertEquals("res/-5N.png", pathMap.getOriginalName("res/drawable-mdpi/drawable_0x7f080695.png"))
        assertEquals("res/drawable-mdpi/drawable_0x7f080695.png", pathMap.getAlias("res/-5N.png"))
    }
}

