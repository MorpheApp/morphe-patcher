/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

internal object CompatibilityTest {

    @Test
    fun `legacy usage`() {
        val patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package"("1.0.0"),
            )
        }

        assertEquals(1, patch.compatiblePackages!!.size)
        assertEquals("compatible.package", patch.compatiblePackages!!.first().first)
    }

    @Test
    fun `legacy to Compatibility`() {
        var patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package"("1.0.0"),
            )
        }
        assertEquals(
            listOf(AppTarget(version = "1.0.0")),
            patch.compatibility!!.first().targets
        )

        patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package",
            )
        }
        assertEquals(
            listOf(AppTarget(version = null)),
            patch.compatibility!!.first().targets
        )

        var compatibility = Compatibility(
            name ="Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null),
                AppTarget(version = "1.1.0"),
                AppTarget(version = "1.0.0")
            )
        )
        assertEquals(null, compatibility.legacy!!.second)

        compatibility = Compatibility(
            name ="Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = null, isExperimental = true),
                AppTarget(version = "1.1.0"),
                AppTarget(version = "1.0.0")
            )
        )
        assertEquals(setOf("1.1.0", "1.0.0"), compatibility.legacy!!.second)
    }

    @Test
    fun `legacy experimental only declaration`() {
        val compatibility = Compatibility(
            name ="Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.0", isExperimental = true)
            )
        )

        // Experimental is included since no non-experimental declarations exist.
        assertEquals(setOf("1.1.0", "1.0.0"), compatibility.legacy!!.second)
    }


    @Test
    fun `legacy experimental declaration`() {
        val compatibility = Compatibility(
            name ="Example app",
            packageName = "compatible.package",
            targets = listOf(
                AppTarget(version = "1.1.0", isExperimental = true),
                AppTarget(version = "1.0.1"),
                AppTarget(version = "1.0.0")
            )
        )

        // Only non-experimental is included.
        assertEquals(setOf("1.0.1", "1.0.0"), compatibility.legacy!!.second)
    }

    @Test
    fun `duplicate versions`() {
        assertThrows<Exception> {
            Compatibility(
                name ="Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.0.0"),
                    AppTarget(version = "1.0.0")
                )
            )
        }

        assertThrows<Exception> {
            Compatibility(
                name ="Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = "1.0.0", isExperimental = true),
                    AppTarget(version = "1.0.0", isExperimental = false)
                )
            )
        }

        assertThrows<Exception> {
            Compatibility(
                name ="Example app",
                packageName = "compatible.package",
                targets = listOf(
                    AppTarget(version = null, isExperimental = true),
                    AppTarget(version = null, isExperimental = false)
                )
            )
        }
    }
}
