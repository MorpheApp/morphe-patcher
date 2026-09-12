package app.morphe.patcher.resource

/**
 * The type of an Android resource, as it appears in the resource table and in `public.xml`.
 *
 * @param value The type name, e.g. `layout`.
 */
enum class ResourceType(val value: String) {
    ANIM("anim"),
    ANIMATOR("animator"),
    ARRAY("array"),
    ATTR("attr"),
    BOOL("bool"),
    COLOR("color"),
    DIMEN("dimen"),
    DRAWABLE("drawable"),
    FONT("font"),
    FRACTION("fraction"),
    ID("id"),
    INTEGER("integer"),
    INTERPOLATOR("interpolator"),
    LAYOUT("layout"),
    MENU("menu"),
    MIPMAP("mipmap"),
    NAVIGATION("navigation"),
    PLURALS("plurals"),
    RAW("raw"),
    STRING("string"),
    STYLE("style"),
    STYLEABLE("styleable"),
    TRANSITION("transition"),
    VALUES("values"),
    XML("xml");

    companion object {
        private val VALUE_MAP: Map<String, ResourceType> = entries.associateBy { it.value }

        /**
         * @param value The type name, e.g. `layout`.
         * @return The resource type, or `null` if the name is not a known type.
         */
        fun fromValueOrNull(value: String) = VALUE_MAP[value]

        /**
         * @param value The type name, e.g. `layout`.
         * @throws IllegalArgumentException If the name is not a known type.
         */
        fun fromValue(value: String) = fromValueOrNull(value)
            ?: throw IllegalArgumentException("Unknown resource type: $value")
    }
}
