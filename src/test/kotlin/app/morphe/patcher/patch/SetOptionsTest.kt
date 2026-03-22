/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.patch

import kotlin.test.Test
import kotlin.test.assertEquals

internal class OptionsTest {
    @Test
    fun `serializes and deserializes`() {
        val options = mapOf(
            "Test patch" to mapOf("key1" to "test", "key2" to false),
        )

        val patch = bytecodePatch("Test patch") {
            stringOption("key1")
            booleanOption("key2", true)
        }
        val duplicatePatch = bytecodePatch("Test patch") {
            stringOption("key1")
        }
        val unnamedPatch = bytecodePatch(default = true) {
            booleanOption("key1")
        }

        setOf(patch, duplicatePatch, unnamedPatch).setOptions(options)

        assert(patch.options["key1"].value == "test")
        assert(patch.options["key2"].value == false)

        assertEquals(patch.options["key1"].value, duplicatePatch.options["key1"].value)

        assert(unnamedPatch.options["key1"].value == null)
    }
}