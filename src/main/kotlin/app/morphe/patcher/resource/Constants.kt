/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource

internal val resourceTypes = mapOf(
    "attrs" to Pair("attr", "attr"),
    "bools" to Pair("bool", "bool"),
    "colors" to Pair("color", "color"),
    "dimens" to Pair("dimen", "dimen"),
    "drawables" to Pair("drawable", "drawable"),
    "fonts" to Pair("font", "font"),
    "fractions" to Pair("fraction", "fraction"),
    "ids" to Pair("id", "id"),
    "integers" to Pair("integer", "integer"),
    "layouts" to Pair("layout", "layout"),
    "plurals" to Pair("plurals", "plurals"),
    "strings" to Pair("string", "string"),
    "styles" to Pair("style", "style"),
    "arrays" to Pair("string-array", "array")
)

internal val fileResourceTypes = listOf(
    "anim",
    "color",
    "drawable",
    "font",
    "interpolator",
    "layout",
    "menu",
    "mipmap",
    "raw",
    "transition",
    "xml"
)