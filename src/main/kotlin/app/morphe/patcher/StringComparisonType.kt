package app.morphe.patcher

/**
 * String comparison type. Enum is explicitly used for [string()] and other Instruction filter
 * declarations, and implicitly used for [Fingerprint] and [InstructionFilter] type declarations.
 *
 * For type declarations such as [Fingerprint.definingClass], type semantics are parsed in the order of:
 * - All single character primitive types (`B`, `C`, `D`, `F`, `I`, `J`, `S`, `V`, `Z`) are compared using [EQUALS].
 * - Declaration starts with `L` _and_ ends with `;` are compared using [EQUALS].
 * - Declaration starts with `L` are compared using [STARTS_WITH].
 * - Declaration ends with `;` are compared using [ENDS_WITH].
 * - Declaration that starts with an array `[` are compared using [STARTS_WITH].
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
        internal fun typeDeclarationToComparison(type: Iterable<CharSequence>?): List<StringComparisonType> {
            return type?.map(::typeDeclarationToComparison).orEmpty()
        }

        fun typeDeclarationToComparison(type: CharSequence?): StringComparisonType {
            if (type == null) return EQUALS
            require(type.isNotEmpty()) {
                "type cannot be empty"
            }

            val firstChar = type[0]

            // First handle single character declarations.
            if (type.length == 1) {
                return when (firstChar) {
                    'B', 'C', 'D', 'F', 'I', 'J', 'S', 'V', 'Z' -> EQUALS
                    'L' -> STARTS_WITH
                    '[' -> STARTS_WITH
                    else -> throw IllegalArgumentException("Unknown type declaration: $type")
                }
            }

            val endsWithSemicolon = type.endsWith(';')

            if (firstChar == '[') {
                return if (endsWithSemicolon) EQUALS else STARTS_WITH
            }

            val startsWithL = (firstChar == 'L')

            when {
                startsWithL && endsWithSemicolon -> return EQUALS
                startsWithL -> return STARTS_WITH
                endsWithSemicolon -> return ENDS_WITH
            }

            return CONTAINS
        }
    }
}

/**
 * Matches two lists of parameters.
 *
 * @param targetMethodParameters Method parameters to search in.
 * @param fingerprintParameters Parameters to check. Uses [StringComparisonType] type semantics.
 */
fun parametersMatch(
    targetMethodParameters: Iterable<CharSequence>,
    fingerprintParameters: Iterable<CharSequence>,
): Boolean {
    if (fingerprintParameters.count() != targetMethodParameters.count()) return false
    val fingerprintIterator = fingerprintParameters.iterator()

    targetMethodParameters.forEach { targetParameter ->
        val fingerprintParameter = fingerprintIterator.next()
        if (!StringComparisonType.typeDeclarationToComparison(fingerprintParameter)
                .compare(targetParameter, fingerprintParameter)) return false
    }

    return true
}

internal fun parametersMatch(
    targetMethodParameters: Iterable<CharSequence>,
    fingerprintParameters: Iterable<CharSequence>,
    stringComparisonType: Iterable<StringComparisonType>
): Boolean {
    if (targetMethodParameters.count() != fingerprintParameters.count()) return false
    val fingerprintIterator = fingerprintParameters.iterator()
    val comparisonIterator = stringComparisonType.iterator()

    targetMethodParameters.forEach { targetParameter ->
        val comparison = comparisonIterator.next()
        val fingerprintParameter = fingerprintIterator.next()
        if (!comparison.compare(targetParameter, fingerprintParameter)) return false
    }

    return true
}
