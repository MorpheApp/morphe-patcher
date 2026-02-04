package app.morphe.patcher

/**
 * String comparison type.
 *
 * All classes/parameters that use type declarations are parsed in the order of:
 * - Declaration starts with `L` _and_ ends with `;` are compared using [EQUALS].
 * - Declaration starts with `L` are compared using [STARTS_WITH].
 * - Declaration ends with `;` are compared using [ENDS_WITH].
 * - Declaration that starts with an array `[` are compared using [STARTS_WITH].
 * - All primitive types (`B`, `C`, `D`, `F`, `I`, `J`, `S`, `V`, `Z`) are compared using [EQUALS].
 * - All other declarations are compared using [CONTAINS].
 */
enum class StringComparisonType {
    EQUALS,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH;

    /**
     * @param targetString The target string to search
     * @param searchString To search for in the target string (or to compare entirely for equality).
     */
    fun compare(targetString: CharSequence, searchString: CharSequence): Boolean {
        return when (this) {
            EQUALS -> targetString == searchString
            CONTAINS -> targetString.contains(searchString)
            STARTS_WITH -> targetString.startsWith(searchString)
            ENDS_WITH -> targetString.endsWith(searchString)
        }
    }

    // @Deprecated("Here only for backwards compatibility") // TODO: Delete this on next major version.
    fun compare(targetString: String, searchString: String) =
        compare(targetString as CharSequence, searchString as CharSequence)

    internal companion object {
        fun typeDeclarationToComparison(type: CharSequence?): StringComparisonType {
            if (type == null) return EQUALS
            require(type.isNotEmpty()) {
                "type cannot be empty"
            }

            // Handle class types first.
            val firstChar = type[0]
            val startsWith = firstChar == 'L'
            val endsWith = type.endsWith(';')

            when {
                startsWith && endsWith -> return EQUALS
                startsWith -> return STARTS_WITH
                endsWith -> return ENDS_WITH
            }

            return when (firstChar) {
                '[' -> STARTS_WITH
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'V', 'Z' -> EQUALS
                else -> CONTAINS
            }
        }
    }
}
