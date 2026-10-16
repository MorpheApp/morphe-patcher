/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import java.io.File
import java.util.logging.Logger

internal class StringsXmlSanitizeProcessor(
    internal val get: (String, String) -> File,
    internal val packageDirectories: Map<String, File>,
) {
    private val logger = Logger.getLogger(StringsXmlSanitizeProcessor::class.java.name)

    /** Every strings file of every package. */
    fun stringsFiles(): List<File> = buildList {
        packageDirectories.forEach { (_, rootDir) ->
            rootDir.resolve("res").listFiles { it.isDirectory }?.forEach { dir ->
                dir.listFiles { it.name == "strings.xml" }?.forEach { file ->
                    add(file)
                }
            }
        }
    }

    /**
     * @param files The strings files to process.
     * @param log Whether to log that strings are processed, which decoding single files on request does not.
     */
    fun process(files: Collection<File> = stringsFiles(), log: Boolean = true) {
        if (log) logger.info("Sanitizing unpatched strings")

        files.parallelStream().forEach { file ->
            val rawXml = file.readText(Charsets.UTF_8)
            val sanitizedXml = sanitizeXmlText(rawXml)
            if (sanitizedXml !== rawXml) {
                file.writeText(sanitizedXml, Charsets.UTF_8)
            }
        }
    }
}


/**
 * Clean both forms of invalid XML characters:
 * 1. Remove invalid numeric character references (e.g., &#65535;), which would
 *    otherwise cause the XML parser to fail before the document can be loaded.
 * 2. Remove any literal invalid Unicode characters that may appear directly in
 *    the file (e.g., U+FFFF), ensuring the resulting text always conforms to
 *    XML 1.0's allowed character ranges.
 */
internal fun sanitizeXmlText(input: String): String {
    fun isValidXmlChar(code: Int): Boolean =
        code == 0x9 ||
                code == 0xA ||
                code == 0xD ||
                (code in 0x20..0xD7FF) ||
                (code in 0xE000..0xFFFD) ||
                (code in 0x10000..0x10FFFF)

    // Most strings.xml files are valid. Scan once and allocate only if an invalid literal or numeric
    // reference is encountered. Code-point iteration also preserves valid supplementary Unicode characters.
    var output: StringBuilder? = null
    var index = 0
    while (index < input.length) {
        if (input[index] == '&' && index + 2 < input.length && input[index + 1] == '#') {
            val semicolon = input.indexOf(';', index + 2)
            if (semicolon >= 0) {
                val digitsStart = index + 2
                var numeric = digitsStart < semicolon
                var digitIndex = digitsStart
                while (numeric && digitIndex < semicolon) {
                    numeric = input[digitIndex].isDigit()
                    digitIndex++
                }
                if (numeric) {
                    val value = input.substring(digitsStart, semicolon).toLongOrNull()
                    if (value == null || value > Int.MAX_VALUE || !isValidXmlChar(value.toInt())) {
                        if (output == null) {
                            output = StringBuilder(input.length).append(input, 0, index)
                        }
                        index = semicolon + 1
                        continue
                    }
                }
            }
        }

        val codePoint = Character.codePointAt(input, index)
        val charCount = Character.charCount(codePoint)
        if (isValidXmlChar(codePoint)) {
            output?.append(input, index, index + charCount)
        } else if (output == null) {
            output = StringBuilder(input.length).append(input, 0, index)
        }
        index += charCount
    }

    return output?.toString() ?: input
}
