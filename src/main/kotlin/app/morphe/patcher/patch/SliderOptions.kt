/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

@file:Suppress("unused", "DEPRECATION")

package app.morphe.patcher.patch

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.reflect.typeOf

/**
 * Largest deviation from an exact multiple of the step that is still accepted for
 * floating point options, expressed in steps. Float arithmetic makes exact multiples
 * unreachable, so a value one part in ten thousand off the scale still counts as on it.
 */
private const val FLOAT_STEP_TOLERANCE = 1e-4f

/**
 * Whether [value] lies within `[min, max]` and on the discrete scale defined by [step].
 */
private fun onScale(value: Int, min: Int, max: Int, step: Int) =
    value in min..max && (value - min) % step == 0

/**
 * Whether [value] lies within `[min, max]` and on the discrete scale defined by [step].
 * A null [step] means the slider is continuous, so only the bounds are checked.
 */
private fun onScale(value: Float, min: Float, max: Float, step: Float?): Boolean {
    if (value.isNaN() || value < min || value > max) return false
    if (step == null) return true

    val steps = (value - min) / step
    return abs(steps - steps.roundToInt()) <= FLOAT_STEP_TOLERANCE
}

/**
 * Rejects a bound declaration that no value could ever satisfy. Patch authors get the
 * error while their bundle is loading instead of when a value is first read.
 */
private fun requireValidBounds(min: Number, max: Number, step: Number?) {
    require(min.toDouble() < max.toDouble()) { "min ($min) must be less than max ($max)" }
    if (step != null) require(step.toDouble() > 0.0) { "step ($step) must be positive" }
}

/**
 * Rejects a discrete scale whose upper bound cannot be reached from the lower one.
 */
private fun requireReachableMax(min: Int, max: Int, step: Int) =
    require((max - min) % step == 0) {
        "max ($max) must be reachable from min ($min) in steps of $step"
    }

/**
 * Rejects a default that the option would reject as a value.
 */
private fun requireValidDefault(default: Int?, min: Int, max: Int, step: Int) =
    require(default == null || onScale(default, min, max, step)) {
        "default ($default) must be within [$min, $max] and on the scale of $step"
    }

private fun requireValidDefault(default: Float?, min: Float, max: Float, step: Float?) =
    require(default == null || onScale(default, min, max, step)) {
        "default ($default) must be within [$min, $max]" + (step?.let { " and on the scale of $it" } ?: "")
    }

/**
 * Rejects a range default that is not an ordered pair of accepted values.
 */
private fun requireValidRangeDefault(default: List<Int>?, min: Int, max: Int, step: Int) {
    require(default == null || default.size == 2) { "default must hold exactly two values" }
    if (default == null) return
    require(default[0] <= default[1]) { "default start (${default[0]}) must not exceed its end (${default[1]})" }
    default.forEach { requireValidDefault(it, min, max, step) }
}

private fun requireValidRangeDefault(default: List<Float>?, min: Float, max: Float, step: Float?) {
    require(default == null || default.size == 2) { "default must hold exactly two values" }
    if (default == null) return
    require(default[0] <= default[1]) { "default start (${default[0]}) must not exceed its end (${default[1]})" }
    default.forEach { requireValidDefault(it, min, max, step) }
}

/**
 * The bound check of a slider option, composed with the [user] validator so that both run
 * for every value. Declared outside the option classes because a superclass constructor
 * argument cannot read properties of the instance being constructed.
 */
private fun intScaleValidator(
    min: Int,
    max: Int,
    step: Int,
    user: Option<Int>.(Int?) -> Boolean,
): Option<Int>.(Int?) -> Boolean =
    { value -> value == null || (onScale(value, min, max, step) && user(this, value)) }

private fun floatScaleValidator(
    min: Float,
    max: Float,
    step: Float?,
    user: Option<Float>.(Float?) -> Boolean,
): Option<Float>.(Float?) -> Boolean =
    { value -> value == null || (onScale(value, min, max, step) && user(this, value)) }

private fun intRangeValidator(
    min: Int,
    max: Int,
    step: Int,
    user: Option<List<Int>>.(List<Int>?) -> Boolean,
): Option<List<Int>>.(List<Int>?) -> Boolean = { value ->
    value == null || (
            value.size == 2 &&
                    value[0] <= value[1] &&
                    value.all { onScale(it, min, max, step) } &&
                    user(this, value)
            )
}

private fun floatRangeValidator(
    min: Float,
    max: Float,
    step: Float?,
    user: Option<List<Float>>.(List<Float>?) -> Boolean,
): Option<List<Float>>.(List<Float>?) -> Boolean = { value ->
    value == null || (
            value.size == 2 &&
                    value[0] <= value[1] &&
                    value.all { onScale(it, min, max, step) } &&
                    user(this, value)
            )
}

/**
 * An [Option] whose [Int] value is constrained to `[min, max]`. Signals to editors that this
 * option should be edited with a slider rather than a numeric text field.
 *
 * The stored value is a plain [Int], so consumers that treat the option as `Option<Int>` see
 * and mutate the same value as with a raw [intOption].
 *
 * The bounds are enforced by the option itself and not only by the editor, so a value coming
 * from a command line or a configuration file is rejected the same way.
 *
 * @property min The smallest accepted value, and the left end of the slider.
 * @property max The largest accepted value, and the right end of the slider.
 * @property step The distance between two adjacent positions on the slider.
 */
class IntSliderOption @PublishedApi internal constructor(
    key: String,
    val min: Int,
    val max: Int,
    default: Int? = null,
    val step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Int>.(Int?) -> Boolean = { true },
) : Option<Int>(
    key,
    default,
    null,
    title,
    description,
    required,
    typeOf<Int>(),
    intScaleValidator(min, max, step, validator),
) {
    init {
        requireValidBounds(min, max, step)
        requireReachableMax(min, max, step)
        requireValidDefault(default, min, max, step)
    }
}

/**
 * An [Option] whose [Float] value is constrained to `[min, max]`. Signals to editors that this
 * option should be edited with a slider rather than a numeric text field.
 *
 * @property min The smallest accepted value, and the left end of the slider.
 * @property max The largest accepted value, and the right end of the slider.
 * @property step The distance between two adjacent positions on the slider,
 *   or null for a continuous slider.
 */
class FloatSliderOption @PublishedApi internal constructor(
    key: String,
    val min: Float,
    val max: Float,
    default: Float? = null,
    val step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Float>.(Float?) -> Boolean = { true },
) : Option<Float>(
    key,
    default,
    null,
    title,
    description,
    required,
    typeOf<Float>(),
    floatScaleValidator(min, max, step, validator),
) {
    init {
        requireValidBounds(min, max, step)
        requireValidDefault(default, min, max, step)
    }
}

/**
 * An [Option] holding a closed range of [Int]s within `[min, max]`. Signals to editors that
 * this option should be edited with a range slider.
 *
 * The stored value is a `List<Int>` of exactly two elements, `[start, end]`, because that is
 * what every consumer of an option value already understands. Use [range] to read it as an
 * [IntRange].
 *
 * @property min The smallest accepted value, and the left end of the slider.
 * @property max The largest accepted value, and the right end of the slider.
 * @property step The distance between two adjacent positions on the slider.
 */
class IntRangeOption @PublishedApi internal constructor(
    key: String,
    val min: Int,
    val max: Int,
    default: List<Int>? = null,
    val step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Int>>.(List<Int>?) -> Boolean = { true },
) : Option<List<Int>>(
    key,
    default,
    null,
    title,
    description,
    required,
    typeOf<List<Int>>(),
    intRangeValidator(min, max, step, validator),
) {
    /** The current value as an [IntRange], or null when unset. */
    val range: IntRange? get() = value?.let { it[0]..it[1] }

    init {
        requireValidBounds(min, max, step)
        requireReachableMax(min, max, step)
        requireValidRangeDefault(default, min, max, step)
    }
}

/**
 * An [Option] holding a closed range of [Float]s within `[min, max]`. Signals to editors that
 * this option should be edited with a range slider.
 *
 * The stored value is a `List<Float>` of exactly two elements, `[start, end]`. Use [range] to
 * read it as a closed range.
 *
 * @property min The smallest accepted value, and the left end of the slider.
 * @property max The largest accepted value, and the right end of the slider.
 * @property step The distance between two adjacent positions on the slider,
 *   or null for a continuous slider.
 */
class FloatRangeOption @PublishedApi internal constructor(
    key: String,
    val min: Float,
    val max: Float,
    default: List<Float>? = null,
    val step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Float>>.(List<Float>?) -> Boolean = { true },
) : Option<List<Float>>(
    key,
    default,
    null,
    title,
    description,
    required,
    typeOf<List<Float>>(),
    floatRangeValidator(min, max, step, validator),
) {
    /** The current value as a closed range, or null when unset. */
    val range: ClosedFloatingPointRange<Float>? get() = value?.let { it[0]..it[1] }

    init {
        requireValidBounds(min, max, step)
        requireValidRangeDefault(default, min, max, step)
    }
}

// region Builders

/**
 * Create a new [IntSliderOption].
 */
fun intSliderOption(
    key: String,
    min: Int,
    max: Int,
    default: Int? = null,
    step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Int>.(Int?) -> Boolean = { true },
) = IntSliderOption(key, min, max, default, step, title, description, required, validator)

/**
 * Create a new [IntSliderOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.intSliderOption(
    key: String,
    min: Int,
    max: Int,
    default: Int? = null,
    step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Int>.(Int?) -> Boolean = { true },
): IntSliderOption = app.morphe.patcher.patch.intSliderOption(
    key, min, max, default, step, title, description, required, validator,
).also { it() }

/**
 * Create a new [FloatSliderOption].
 */
fun floatSliderOption(
    key: String,
    min: Float,
    max: Float,
    default: Float? = null,
    step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Float>.(Float?) -> Boolean = { true },
) = FloatSliderOption(key, min, max, default, step, title, description, required, validator)

/**
 * Create a new [FloatSliderOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.floatSliderOption(
    key: String,
    min: Float,
    max: Float,
    default: Float? = null,
    step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<Float>.(Float?) -> Boolean = { true },
): FloatSliderOption = app.morphe.patcher.patch.floatSliderOption(
    key, min, max, default, step, title, description, required, validator,
).also { it() }

/**
 * Create a new [IntRangeOption].
 */
fun intRangeOption(
    key: String,
    min: Int,
    max: Int,
    default: List<Int>? = null,
    step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Int>>.(List<Int>?) -> Boolean = { true },
) = IntRangeOption(key, min, max, default, step, title, description, required, validator)

/**
 * Create a new [IntRangeOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.intRangeOption(
    key: String,
    min: Int,
    max: Int,
    default: List<Int>? = null,
    step: Int = 1,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Int>>.(List<Int>?) -> Boolean = { true },
): IntRangeOption = app.morphe.patcher.patch.intRangeOption(
    key, min, max, default, step, title, description, required, validator,
).also { it() }

/**
 * Create a new [FloatRangeOption].
 */
fun floatRangeOption(
    key: String,
    min: Float,
    max: Float,
    default: List<Float>? = null,
    step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Float>>.(List<Float>?) -> Boolean = { true },
) = FloatRangeOption(key, min, max, default, step, title, description, required, validator)

/**
 * Create a new [FloatRangeOption] and add it to the current [PatchBuilder].
 */
fun PatchBuilder<*>.floatRangeOption(
    key: String,
    min: Float,
    max: Float,
    default: List<Float>? = null,
    step: Float? = null,
    title: String? = null,
    description: String? = null,
    required: Boolean = false,
    validator: Option<List<Float>>.(List<Float>?) -> Boolean = { true },
): FloatRangeOption = app.morphe.patcher.patch.floatRangeOption(
    key, min, max, default, step, title, description, required, validator,
).also { it() }

// endregion
