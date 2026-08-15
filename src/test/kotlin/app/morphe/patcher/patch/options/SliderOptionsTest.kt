/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.patch.options

import app.morphe.patcher.patch.FloatRangeOption
import app.morphe.patcher.patch.FloatSliderOption
import app.morphe.patcher.patch.IntRangeOption
import app.morphe.patcher.patch.IntSliderOption
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.floatRangeOption
import app.morphe.patcher.patch.floatSliderOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.intRangeOption
import app.morphe.patcher.patch.intSliderOption
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object SliderOptionsTest {
    private val sliderPatch = bytecodePatch(default = true) {
        intSliderOption("volume", min = 0, max = 100, default = 50, step = 5)
        floatSliderOption("speed", min = 0.5f, max = 2f, default = 1f)
        intRangeOption("hours", min = 0, max = 24, default = listOf(8, 17))
        floatRangeOption("opacity", min = 0f, max = 1f, default = listOf(0.2f, 0.8f), step = 0.1f)

        // Legacy numeric option, must NOT be promoted to a slider
        intOption("legacyNumber", default = 7)
    }

    @Test
    fun `intSliderOption carries its bounds and keeps Int as the value type`() {
        val option = sliderPatch.options["volume"]
        assertIs<IntSliderOption>(option)
        assertEquals(0, option.min)
        assertEquals(100, option.max)
        assertEquals(5, option.step)
        assertEquals(50, option.default)
        assertEquals(typeOf<Int>(), option.type)
    }

    @Test
    fun `intSliderOption accepts values on the scale`() {
        val option = sliderPatch.options["volume"] as IntSliderOption
        option.reset()

        sliderPatch.options["volume"] = 0
        assertEquals(0, option.value)
        sliderPatch.options["volume"] = 100
        assertEquals(100, option.value)
        sliderPatch.options["volume"] = 35
        assertEquals(35, option.value)

        option.reset()
    }

    @Test
    fun `intSliderOption rejects values outside its bounds`() {
        val option = sliderPatch.options["volume"] as IntSliderOption

        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["volume"] = 101
        }
        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["volume"] = -5
        }

        option.reset()
    }

    @Test
    fun `intSliderOption rejects values off the step scale`() {
        val option = sliderPatch.options["volume"] as IntSliderOption

        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["volume"] = 33
        }

        option.reset()
    }

    @Test
    fun `an unset value is still allowed when the option is not required`() {
        val option = sliderPatch.options["volume"] as IntSliderOption

        sliderPatch.options["volume"] = null
        assertNull(option.value)

        option.reset()
    }

    @Test
    fun `the declared validator runs on top of the bound check`() {
        val option = intSliderOption("even", min = 0, max = 10, default = 0) { it == null || it % 4 == 0 }

        option.value = 8
        assertEquals(8, option.value)

        // Within bounds, but rejected by the patch author's own validator
        assertFailsWith<OptionException.ValueValidationException> { option.value = 5 }
        // Rejected by the bound check before the author's validator is reached
        assertFailsWith<OptionException.ValueValidationException> { option.value = 12 }
    }

    @Test
    fun `floatSliderOption without a step is continuous within its bounds`() {
        val option = sliderPatch.options["speed"] as FloatSliderOption
        assertNull(option.step)

        sliderPatch.options["speed"] = 1.37f
        assertEquals(1.37f, option.value)

        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["speed"] = 2.5f
        }

        option.reset()
    }

    @Test
    fun `floatSliderOption with a step tolerates float arithmetic`() {
        val option = floatSliderOption("gain", min = 0f, max = 1f, default = 0f, step = 0.1f)

        // 0.7f is not an exact multiple of 0.1f in binary floating point
        option.value = 0.7f
        assertEquals(0.7f, option.value)

        assertFailsWith<OptionException.ValueValidationException> { option.value = 0.75f }
    }

    @Test
    fun `intRangeOption stores an ordered pair and exposes it as an IntRange`() {
        val option = sliderPatch.options["hours"]
        assertIs<IntRangeOption>(option)
        assertEquals(typeOf<List<Int>>(), option.type)
        assertEquals(8..17, option.range)

        sliderPatch.options["hours"] = listOf(2, 4)
        assertEquals(2..4, option.range)

        option.reset()
    }

    @Test
    fun `intRangeOption rejects a reversed, oversized or out of bounds pair`() {
        val option = sliderPatch.options["hours"] as IntRangeOption

        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["hours"] = listOf(17, 8)
        }
        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["hours"] = listOf(1, 2, 3)
        }
        assertFailsWith<OptionException.ValueValidationException> {
            sliderPatch.options["hours"] = listOf(0, 25)
        }

        option.reset()
    }

    @Test
    fun `floatRangeOption exposes its value as a closed range`() {
        val option = sliderPatch.options["opacity"]
        assertIs<FloatRangeOption>(option)
        assertEquals(0.2f..0.8f, option.range)

        option.reset()
    }

    @Test
    fun `a legacy intOption is not promoted to a slider`() {
        val option = sliderPatch.options["legacyNumber"]
        assertTrue(option !is IntSliderOption, "intOption must not be promoted to IntSliderOption")
        assertIs<Option<*>>(option)
        assertEquals(typeOf<Int>(), option.type)
    }

    @Test
    fun `standalone builders return the correct subclass`() {
        assertIs<IntSliderOption>(intSliderOption("a", min = 0, max = 1))
        assertIs<FloatSliderOption>(floatSliderOption("b", min = 0f, max = 1f))
        assertIs<IntRangeOption>(intRangeOption("c", min = 0, max = 1))
        assertIs<FloatRangeOption>(floatRangeOption("d", min = 0f, max = 1f))
    }

    @Test
    fun `an impossible declaration fails while the patch is being built`() {
        // min is not below max
        assertFailsWith<IllegalArgumentException> { intSliderOption("a", min = 10, max = 10) }
        // step is not positive
        assertFailsWith<IllegalArgumentException> { intSliderOption("b", min = 0, max = 10, step = 0) }
        // max is unreachable from min in whole steps
        assertFailsWith<IllegalArgumentException> { intSliderOption("c", min = 0, max = 10, step = 3) }
        // default is outside the bounds
        assertFailsWith<IllegalArgumentException> { intSliderOption("d", min = 0, max = 10, default = 11) }
        // default is off the step scale
        assertFailsWith<IllegalArgumentException> { intSliderOption("e", min = 0, max = 10, default = 3, step = 5) }
        // range default is not a pair
        assertFailsWith<IllegalArgumentException> { intRangeOption("f", min = 0, max = 10, default = listOf(1)) }
        // range default is reversed
        assertFailsWith<IllegalArgumentException> { intRangeOption("g", min = 0, max = 10, default = listOf(5, 1)) }
    }
}
