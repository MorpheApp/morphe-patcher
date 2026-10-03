/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import java.io.File

internal class StringsXmlEscapeProcessor(
    get: (String, String) -> File,
    packageDirectories: Map<String, File>,
) : StringsXmlProcessor(get, packageDirectories, "Escaping strings") {

    /**
     * Single-pass escape of backslashes, quotes and line breaks, which is the form older patches expect.
     * Control characters and surrogates are written as unicode escapes, and other characters are kept.
     */
    override fun processString(text: String): String {
        // Most strings need no escaping, so only copy a string that does.
        var index = 0
        while (index < text.length && !needsEscape(text[index])) index++
        if (index == text.length) return text

        val sb = StringBuilder(text.length + 16).append(text, 0, index)

        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> if (needsEscape(ch)) appendUnicode(sb, ch.code) else sb.append(ch)
            }
        }

        return sb.toString()
    }

    private fun needsEscape(ch: Char) =
        ch == '\\' || ch == '\'' || ch == '"' || ch.code < 0x20 || ch.code == 0x7F || ch.isSurrogate()

    private fun appendUnicode(sb: StringBuilder, code: Int) {
        sb.append("\\u")
        for (shift in 12 downTo 0 step 4) {
            sb.append(HEX_DIGITS[(code shr shift) and 0xF])
        }
    }

    private companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
