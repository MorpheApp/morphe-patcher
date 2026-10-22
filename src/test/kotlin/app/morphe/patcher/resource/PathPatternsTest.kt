/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class PathPatternsTest {
    private fun matches(pattern: String, path: String) = PathPatterns(listOf(pattern)).matches(path)

    @Test
    fun `a star matches within one segment`() {
        assertTrue(matches("res/values*/strings.xml", "res/values/strings.xml"))
        assertTrue(matches("res/values*/strings.xml", "res/values-de/strings.xml"))
        assertFalse(matches("res/values*/strings.xml", "res/values-de/colors.xml"))
        assertFalse(matches("res/*.xml", "res/values/strings.xml"))
        assertTrue(matches("res/drawable*/icon.*", "res/drawable-xxhdpi/icon.png"))
    }

    @Test
    fun `a double star matches across segments`() {
        assertTrue(matches("**", "AndroidManifest.xml"))
        assertTrue(matches("**", "res/values/strings.xml"))
        assertTrue(matches("**/strings.xml", "res/values/strings.xml"))
        assertTrue(matches("**/strings.xml", "strings.xml"))
        assertTrue(matches("res/**.xml", "res/layout/main.xml"))
        assertFalse(matches("res/**.xml", "assets/main.xml"))
        assertTrue(matches("res/**/main.xml", "res/main.xml"))
        assertTrue(matches("res/**/main.xml", "res/a/b/main.xml"))
        assertFalse(matches("res/**/main.xml", "res/a/bmain.xml"))
    }

    @Test
    fun `wildcards backtrack`() {
        assertTrue(matches("res/*a*b.xml", "res/xaab.xml"))
        assertTrue(matches("res/*.9.png", "res/icon.9.9.png"))
        assertFalse(matches("res/*.9.png", "res/icon.png"))
        assertTrue(matches("**a/b", "x/a/a/b"))
    }

    @Test
    fun `a question mark matches one character`() {
        assertTrue(matches("res/drawable-?dpi/icon.png", "res/drawable-hdpi/icon.png"))
        assertFalse(matches("res/drawable-?dpi/icon.png", "res/drawable-xxhdpi/icon.png"))
        assertFalse(matches("res?values/strings.xml", "res/values/strings.xml"))
    }

    @Test
    fun `a pattern matching a directory matches everything below it`() {
        assertTrue(matches("res/xml", "res/xml/main.xml"))
        assertTrue(matches("res/xml/", "res/xml/main.xml"))
        assertTrue(matches("res/values*", "res/values-de/strings.xml"))
        assertTrue(matches("assets", "assets/a/b/c.bin"))
        assertTrue(matches("", "res/values/strings.xml"))
        assertFalse(matches("res/xml", "res/xml-v21/main.xml"))
        assertFalse(matches("res/xm", "res/xml/main.xml"))
    }

    @Test
    fun `other characters match themselves`() {
        assertTrue(matches("res/values/strings.xml", "res/values/strings.xml"))
        assertFalse(matches("res/values/strings.xml", "res/values/stringsXxml"))
        assertTrue(matches("res/drawable/a+b (1).png", "res/drawable/a+b (1).png"))
        assertTrue(matches("res/values/strings.xml", "/res/values/strings.xml"))
    }

    @Test
    fun `any of several patterns matches`() {
        val patterns = PathPatterns(listOf("res/values*/colors.xml", "res/values*/styles.xml"))
        assertTrue(patterns.matches("res/values-night/styles.xml"))
        assertTrue(patterns.matches("res/values/colors.xml"))
        assertFalse(patterns.matches("res/values/strings.xml"))
    }

    @Test
    fun `a directory a match can be below is told apart`() {
        fun mayMatchBelow(pattern: String, directory: String) = PathPatterns(listOf(pattern)).mayMatchBelow(directory)

        assertTrue(mayMatchBelow("res/values*/colors.xml", "res"))
        assertTrue(mayMatchBelow("res/values*/colors.xml", "res/values-de"))
        assertFalse(mayMatchBelow("res/values*/colors.xml", "res/layout"))
        assertFalse(mayMatchBelow("res/values*/colors.xml", "assets"))
        assertTrue(mayMatchBelow("res/xml", "res/xml/sub"), "a pattern naming a directory matches below it")
        assertFalse(mayMatchBelow("res/xml", "res/xml-v21"))
        assertTrue(mayMatchBelow("res/**/main.xml", "res/a/b"))
        assertTrue(mayMatchBelow("**", "assets/a"))
        assertTrue(mayMatchBelow("", "assets/a"))
        assertTrue(mayMatchBelow("res/drawable-?dpi/icon.png", "res/drawable-hdpi"))
        assertFalse(mayMatchBelow("res/drawable-?dpi/icon.png", "res/drawable-xxhdpi"))
        assertTrue(PathPatterns(listOf("assets/a", "res/layout")).mayMatchBelow("res/layout"))
    }

    @Test
    fun `testing the paths in a directory gives the same result as matches`() {
        val patterns = listOf(
            "**", "", "res", "res/layout", "res/layout*/*", "res/drawable*/icon.*", "**/strings.xml",
            "res/**.xml", "res/*/main.xml", "res/drawable-?dpi/icon.png", "res/values-de", "assets",
            // File names without wildcards, in one directory and in several
            "res/drawable*/icon.png", "res/values/strings.xml", "AndroidManifest.xml", "res/*/icon.xml",
            "res/drawable-xxhdpi/ico?.png", "*.xml",
        )
        val paths = listOf(
            "AndroidManifest.xml", "assets/data.bin", "assets/sub/data.bin", "res/layout/main.xml",
            "res/layout-land/main.xml", "res/drawable/icon.xml", "res/drawable-xxhdpi/icon.png",
            "res/drawable-hdpi/icon.png", "res/values/strings.xml", "res/values-de/strings.xml", "res/xml/main.xml",
            "res/drawable-xxhdpi/icon.webp", "res/drawable/other.png",
        )

        // Each pattern alone, and several together the way a patch decodes a few files by name
        val patternLists = patterns.map { listOf(it) } + listOf(
            patterns.drop(4).take(3),
            listOf("res/drawable*/icon.png", "res/drawable*/other.*", "res/values/strings.xml"),
            listOf("res/drawable-?dpi/icon.png", "res/**.xml", "res/drawable/other.png"),
        )
        patternLists.forEach { patternList ->
            val matcher = PathPatterns(patternList)
            paths.forEach { path ->
                assertEquals(
                    matcher.matches(path),
                    matcher.matcherIn(path.substringBeforeLast('/', ""))(path),
                    "$patternList and $path",
                )
            }
        }
    }
}
