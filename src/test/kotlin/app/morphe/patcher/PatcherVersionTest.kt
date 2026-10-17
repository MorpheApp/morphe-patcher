/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PatcherVersionTest {
    private fun version(text: String) = PatcherVersion.parse(text)!!

    @Test
    fun `versions are ordered as semantic versions`() {
        val ordered = listOf(
            "1.9.9",
            "1.15.1",
            "1.16.0-dev.1",
            "1.16.0-dev.2",
            "1.16.0-dev.10",
            "1.16.0",
            "1.16.1",
            "2.0.0-alpha",
            "2.0.0-alpha.1",
            "2.0.0-beta",
            "2.0.0",
        ).map(::version)

        assertEquals(ordered, ordered.shuffled().sorted())
        ordered.zipWithNext().forEach { (older, newer) -> assertTrue(older < newer, "$older < $newer") }
    }

    @Test
    fun `build metadata does not change the order`() {
        assertEquals(version("1.16.0"), version("1.16.0+build.5"))
        assertEquals(version("1.16.0"), version(" 1.16.0 "))
        assertEquals("1.16.0-dev.2", version("1.16.0-dev.2").toString())
    }

    @Test
    fun `text that is not a version is not parsed`() {
        listOf(null, "", "na", "1.16", "1.16.0.1", "v1.16.0", "1.16.0-", "9b742c412d").forEach {
            assertNull(PatcherVersion.parse(it), "\"$it\" is not a version")
        }
    }
}
