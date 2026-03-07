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
     * Single-pass escape (fast)
     */
    override fun processString(text: String): String {
        val sb = StringBuilder(text.length)

        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> {
                    val code = ch.code
                    if (code in 0x20..0x7E) {
                        sb.append(ch)
                    } else {
                        appendUnicode(sb, code)
                    }
                }
            }
        }

        return sb.toString()
    }

    private fun appendUnicode(sb: StringBuilder, code: Int) {
        sb.append("\\u")
        val hex = code.toString(16).uppercase()
        repeat(4 - hex.length) { sb.append('0') }
        sb.append(hex)
    }
}
