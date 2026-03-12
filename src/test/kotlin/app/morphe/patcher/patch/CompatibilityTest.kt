/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch

import com.android.tools.smali.dexlib2.AccessFlags
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals

internal object CompatibilityTest {

    @Test
    fun `legacy compatibility`() {
        val patch = bytecodePatch(name = "Test") {
            compatibleWith(
                "compatible.package"("1.0.0"),
            )
        }

        assertEquals(1, patch.compatiblePackages!!.size)
        assertEquals("compatible.package", patch.compatiblePackages!!.first().first)
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
