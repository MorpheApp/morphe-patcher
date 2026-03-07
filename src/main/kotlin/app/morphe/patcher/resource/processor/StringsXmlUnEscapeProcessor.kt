/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import java.io.File

internal class StringsXmlUnEscapeProcessor(
    get: (String, String) -> File,
    packageDirectories: Map<String, File>,
) : StringsXmlProcessor(get, packageDirectories, "Unescaping strings") {

    /**
     * Single-pass unescape of string content.
     */
    override fun processString(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"'  -> { sb.append('"'); i += 2 }
                    'n'  -> { sb.append('\n'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    'u'  -> {
                        // \uXXXX
                        if (i + 5 < text.length) {
                            val hex = text.substring(i + 2, i + 6)
                            try {
                                val code = hex.toInt(16)
                                sb.append(code.toChar())
                                i += 6
                            } catch (_: NumberFormatException) {
                                // Invalid escape → leave as-is
                                sb.append("\\u")
                                i += 2
                            }
                        } else {
                            sb.append("\\u")
                            i += 2
                        }
                    }
                    else -> {
                        // Unknown escape → keep as-is
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }

        // Strip surrounding quotes if present
        if (sb.length >= 2 && sb[0] == '"' && sb[sb.length - 1] == '"') {
            return sb.substring(1, sb.length - 1)
        }

        return sb.toString()
    }
}
