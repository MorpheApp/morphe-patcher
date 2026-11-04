@file:Suppress("DEPRECATION_ERROR")

package app.morphe.patcher.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object PatchTest {
    @Test
    fun `can create patch with name`() {
        val patch = bytecodePatch(name = "Test") {}

        assertEquals("Test", patch.name)
    }

    @Test
    fun `can create patch with compatible packages`() {
        val patch = bytecodePatch(name = "Test", default = false) {
            compatibleWith(
                Compatibility("compatible.package", "1.0.0"),
            )
        }

        assertEquals(1, patch.compatibility!!.size)
        assertEquals("compatible.package", patch.compatibility.first().packageName)
    }

    @Test
    fun `can create patch with dependencies`() {
        val patch = bytecodePatch(name = "Test") {
            dependsOn(resourcePatch(default = false) {})
        }

        assertEquals(1, patch.dependencies.size)
    }

    @Test
    fun `can create patch with options`() {
        val patch = bytecodePatch(name = "Test") {
            val print by stringOption("print")
            val custom = option<String>("custom")()

            execute {
                println(print)
                println(custom.value)
            }
        }

        assertEquals(2, patch.options.size)
    }

    @Test
    fun `unnamed patches not loaded from a property go by the file that declares their blocks`() {
        assertEquals("PatchTest", bytecodePatch { execute { } }.toString())
        assertEquals("PatchTest", resourcePatch { finalize { } }.toString())
        assertTrue(bytecodePatch { }.toString().startsWith("BytecodePatch@"))
    }
}
